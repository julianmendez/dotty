package dotty.tools
package dotc
package core

import Types._, Contexts._, Symbols._, Flags._
import collection.mutable
import util.SimpleMap

object TypeComparers {

  /** Constraints over undetermined type parameters
   *  @param map  a map from PolyType to the type bounds that constrain the
   *              polytype's type parameters. A type parameter that does not
   *              have a constraint is represented by a `NoType` in the corresponding
   *              array entry.
   */
  class Constraints(val map: SimpleMap[PolyType, Array[Type]]) extends AnyVal {

    /** Does the constraint's domain contain the type parameters of `pt`? */
    def contains(pt: PolyType): Boolean = map(pt) != null

    /** The constraints for given type parameter `param`, or NoType if `param` is not part of
     *  the constraint domain.
     */
    def apply(param: PolyParam): Type = {
      val entries = map(param.binder)
      if (entries == null) NoType else entries(param.paramNum)
    }

    /** The constraints for the type parameters of `pt`.
     *  @pre  The polytype's type parameters are contained in the constraint's domain.
     */
    def apply(pt: PolyType): Array[Type] = map(pt)

    /** A new constraint which is derived from this constraint by adding or replacing
     *  the entries corresponding to `pt` with `entries`.
     */
    def updated(pt: PolyType, entries: Array[Type]) =
      new Constraints(map.updated(pt, entries))

    /** A new constraint which is derived from this constraint by removing
     *  the type parameter `param` from the domain.
     */
    def - (param: PolyParam) = {
      val pt = param.binder
      val pnum = param.paramNum
      val entries = map(pt)
      var noneLeft = true
      var i = 0
      while (noneLeft && (i < entries.length)) {
        noneLeft = (entries(i) eq NoType) || i == pnum
        i += 1
      }
      new Constraints(
        if (noneLeft) map remove pt
        else {
          val newEntries = entries.clone
          newEntries(pnum) = NoType
          map.updated(pt, newEntries)
        })
    }

    def + (pt: PolyType) =
      new Constraints(map.updated(pt, pt.paramBounds.toArray))

    /** A new constraint which is derived from this constraint by removing
     *  the type parameter `param` from the domain and replacing all occurrences
     *  of the parameter elsewhere in the constraint by type `tpe`.
     */
    def replace(param: PolyParam, tpe: Type)(implicit ctx: Context) = {
      def subst(entries: Array[Type]) = {
        var result = entries
        var i = 0
        while (i < entries.length) {
          entries(i) match {
            case oldBounds: TypeBounds =>
              val newBounds = oldBounds.subst(param, tpe)
              if (oldBounds ne newBounds) {
                if (result eq entries) result = entries.clone
                result(i) = newBounds
              }
            case _ =>
          }
          i += 1
        }
        result
      }
      new Constraints((this - param).map mapValues subst)
    }
  }

  /** Provides methods to compare types.
   *  @param  constraints The initial constraint which is assumed to hold for the comparisons.
   *                      The constraint set is updated when undetermined type parameters
   *                      in the constraint's domain are compared.
   */
  class TypeComparer(initConstraints: Constraints = new Constraints(SimpleMap.Empty))
                    (implicit val ctx: Context) extends DotClass {

    private var constrs = initConstraints

    final def constraints = constrs
    private def constraints_=(c: Constraints) = constrs = c

    private var pendingSubTypes: mutable.Set[(Type, Type)] = null
    private var recCount = 0

    /** Add the constraint `<bounds.lo <: param <: bounds.hi>`
     *  to `constraints`.
     *  @pre `param` is in the constraint's domain
     */
    def addConstraint(param: PolyParam, bounds: TypeBounds): Boolean = {
      val pt = param.binder
      val pnum = param.paramNum
      val oldEntries = constraints(pt)
      val oldBounds = oldEntries(pnum).asInstanceOf[TypeBounds]
      val newBounds = oldBounds & bounds
      if (oldBounds ne newBounds) {
        val newEntries = oldEntries.clone
        newEntries(pnum) = newBounds
        constraints.updated(pt, newEntries)
      }
      isSubType(newBounds.lo, newBounds.hi)
    }

    /** Add all parameters in given polytype `pt` to the constraint's domain.
     *  If the constraint contains already some of these parameters in its domain,
     *  make a copy of the polytype and add the copy's type parameters instead.
     *  Return either the original polytype, or the copy, if one was made.
     */
    def track(pt: PolyType)(implicit ctx: Context): PolyType = {
      val tracked =
        if (constraints contains pt) pt.copy(pt.paramNames, pt.paramBounds, pt.resultType)
        else pt
      constraints = constraints + tracked
      tracked
    }

    /** Solve constraints for given type parameter `param`.
     *  If `fromBelow` is true the parameter is approximated by its lower bound,
     *  otherwise it is approximated by its upper bound. However, any occurrences
     *  of the parameter in a refinement somewhere in the bound are removed.
     *  (Such occurrences can arise for F-bounded types).
     *  The type parameter is removed from the constraint's domain and all its
     *  occurrences are replaced by its approximation.
     *  @return the instantiating type
     *  @pre `param` is associated with type bounds in the current constraint.
     */
    def approximate(param: PolyParam, fromBelow: Boolean): Type = {
      val removeParam = new TypeMap {
        override def apply(tp: Type) = mapOver {
          tp match {
            case tp: RefinedType if param occursIn tp.refinedInfo => tp.parent
            case _ => tp
          }
        }
      }
      val bounds = constraints(param).asInstanceOf[TypeBounds]
      val bound = if (fromBelow) bounds.lo else bounds.hi
      val inst = removeParam(bound)
      constraints = constraints.replace(param, inst)
      inst
    }

    def isSubType(tp1: Type, tp2: Type): Boolean =
      if (tp1 == NoType || tp2 == NoType) false
      else if (tp1 eq tp2) true
      else {
        val cs = constraints
        try {
          recCount += 1
          val result =
            if (recCount < LogPendingSubTypesThreshold) firstTry(tp1, tp2)
            else monitoredIsSubType(tp1, tp2)
          recCount -= 1
          if (!result) constraints = cs
          result
        } catch {
          case ex: Throwable =>
            recCount -= 1
            constraints = cs
            throw ex
        }
      }

    def monitoredIsSubType(tp1: Type, tp2: Type) = {
      if (pendingSubTypes == null) {
        pendingSubTypes = new mutable.HashSet[(Type, Type)]
        ctx.log(s"!!! deep subtype recursion involving $tp1 <:< $tp2")
      }
      val p = (tp1, tp2)
      !pendingSubTypes(p) && {
        try {
          pendingSubTypes += p
          firstTry(tp1, tp2)
        } finally {
          pendingSubTypes -= p
        }
      }
    }

    def firstTry(tp1: Type, tp2: Type): Boolean = ctx.debugTraceIndented(s"$tp1 <:< $tp2") {
      tp2 match {
        case tp2: NamedType =>
          tp1 match {
            case tp1: NamedType =>
              val sym1 = tp1.symbol
              val sym2 = tp2.symbol
              val pre1 = tp1.prefix
              val pre2 = tp2.prefix
              if (sym1 == sym2) (
                ctx.erasedTypes
                || sym1.isStaticOwner
                || isSubType(pre1, pre2))
              else (
                tp1.name == tp2.name && isSubType(pre1, pre2)
                || sym2.isClass && {
                  val base = tp1.baseType(sym2)
                  (base ne tp1) && isSubType(base, tp2)
                }
                || thirdTryNamed(tp1, tp2))
            case _ =>
              secondTry(tp1, tp2)
          }
        case WildcardType | ErrorType =>
          true
        case tp2: TypeVar =>
          firstTry(tp1, tp2.thisInstance)
        case tp2: PolyParam =>
          constraints(tp2) match {
            case TypeBounds(lo, _) => isSubType(tp1, lo) || addConstraint(tp2, TypeBounds.lower(tp1))
            case _ => secondTry(tp1, tp2)
          }
       case _ =>
          secondTry(tp1, tp2)
      }
    }

    def secondTry(tp1: Type, tp2: Type): Boolean = tp1 match {
      case WildcardType | ErrorType =>
        true
      case tp1: TypeVar =>
        secondTry(tp1.thisInstance, tp2)
      case tp1: PolyParam =>
        constraints(tp1) match {
          case TypeBounds(_, hi) => isSubType(hi, tp2) || addConstraint(tp1, TypeBounds.upper(tp2))
          case _ => thirdTry(tp1, tp2)
        }
      case _ =>
        thirdTry(tp1, tp2)
    }

    def thirdTryNamed(tp1: Type, tp2: NamedType): Boolean = tp2.info match {
      case TypeBounds(lo, _) =>
        isSubType(tp1, lo)
      case _ =>
        val cls2 = tp2.symbol
        (  cls2 == defn.SingletonClass && tp1.isStable
        || cls2 == defn.NotNullClass && tp1.isNotNull
        || (defn.hkTraits contains cls2) && isSubTypeHK(tp1, tp2)
        || fourthTry(tp1, tp2)
        )
    }

    def thirdTry(tp1: Type, tp2: Type): Boolean = tp2 match {
      case tp2: NamedType =>
        thirdTryNamed(tp1, tp2)
      case tp2: RefinedType =>
        isSubType(tp1, tp2.parent) &&
        isSubType(tp1.member(tp2.refinedName).info, tp2.refinedInfo)
      case AndType(tp21, tp22) =>
        isSubType(tp1, tp21) && isSubType(tp1, tp22)
      case OrType(tp21, tp22) =>
        isSubType(tp1, tp21) || isSubType(tp1, tp22)
      case tp2 @ MethodType(_, formals1) =>
        tp1 match {
          case tp1 @ MethodType(_, formals2) =>
            tp1.signature == tp2.signature &&
            matchingParams(formals1, formals2, tp1.isJava, tp2.isJava) &&
            tp1.isImplicit == tp2.isImplicit && // needed?
            isSubType(tp1.resultType, tp2.resultType.subst(tp2, tp1))
          case _ =>
            false
        }
      case tp2: PolyType =>
        tp1 match {
          case tp1: PolyType =>
            tp1.signature == tp2.signature &&
            (tp1.paramBounds corresponds tp2.paramBounds)((b1, b2) =>
              isSameType(b1, b2.subst(tp2, tp1))) &&
            isSubType(tp1.resultType, tp2.resultType.subst(tp2, tp1))
          case _ =>
            false
        }
      case tp2 @ ExprType(restpe1) =>
        tp1 match {
          case tp1 @ ExprType(restpe2) =>
            isSubType(restpe1, restpe2)
          case _ =>
            false
        }
      case TypeBounds(lo2, hi2) =>
        tp1 match {
          case TypeBounds(lo1, hi1) =>
            isSubType(lo2, lo1) && isSubType(hi1, hi2)
          case tp1: ClassInfo =>
            val tt = tp1.typeConstructor // was typeTemplate
            isSubType(lo2, tt) && isSubType(tt, hi2)
          case _ =>
            false
        }
/* needed?
       case ClassInfo(pre2, denot2) =>
        tp1 match {
          case ClassInfo(pre1, denot1) =>
            (denot1 eq denot2) && isSubType(pre2, pre1) // !!! or isSameType?
        }
*/
     case _ =>
        fourthTry(tp1, tp2)
    }

    def fourthTry(tp1: Type, tp2: Type): Boolean =  tp1 match {
      case tp1: TypeRef =>
        (  (tp1 eq defn.NothingType)
        || (tp1 eq defn.NullType) && tp2.dealias.typeSymbol.isNonValueClass
        || !tp1.symbol.isClass && isSubType(tp1.info.bounds.hi, tp2)
        )
      case tp1: SingletonType =>
        isSubType(tp1.underlying, tp2)
      case tp1: RefinedType =>
        isSubType(tp1.parent, tp2)
      case AndType(tp11, tp12) =>
        isSubType(tp11, tp2) || isSubType(tp12, tp2)
      case OrType(tp11, tp12) =>
        isSubType(tp11, tp2) && isSubType(tp12, tp2)
      case _ =>
        false
    }
/* not needed
    def isSubArgs(tps1: List[Type], tps2: List[Type], tparams: List[TypeSymbol]): Boolean = tparams match {
      case tparam :: tparams1 =>
        val variance = tparam.variance
        val t1 = tps1.head
        val t2 = tps2.head
        (variance > 0 || isSubType(t2, t1)) &&
        (variance < 0 || isSubType(t1, t2)) &&
        isSubArgs(tps1.tail, tps2.tail, tparams1)
      case _ =>
        assert(tps1.isEmpty && tps2.isEmpty)
        true
    }
*/
    /** Is `tp1` a subtype of a type `tp2` of the form
     *  `scala.HigerKindedXYZ { ... }?
     *  This is the case if `tp1` and `tp2` have the same number
     *  of type parameters, the bounds of tp1's paremeters
     *  are contained in the corresponding bounds of tp2's parameters
     *  and the variances of correesponding parameters agree.
     */
    def isSubTypeHK(tp1: Type, tp2: Type): Boolean = {
      val tparams = tp1.typeParams
      val hkArgs = tp2.typeArgs
      (hkArgs.length == tparams.length) && {
        val base = ctx.newSkolemSingleton(tp1)
        (tparams, hkArgs).zipped.forall { (tparam, hkArg) =>
          base.memberInfo(tparam) <:< hkArg.bounds // TODO: base.memberInfo needed?
        } &&
        (tparams, tp2.typeSymbol.typeParams).zipped.forall { (tparam, tparam2) =>
          tparam.variance == tparam2.variance
        }
      }
    }

    /** A function implementing `tp1` matches `tp2`. */
    final def matchesType(tp1: Type, tp2: Type, alwaysMatchSimple: Boolean): Boolean = tp1 match {
      case tp1: MethodType =>
        tp2 match {
          case tp2: MethodType =>
            tp1.isImplicit == tp2.isImplicit &&
              matchingParams(tp1.paramTypes, tp2.paramTypes, tp1.isJava, tp2.isJava) &&
              matchesType(tp1.resultType, tp2.resultType.subst(tp2, tp1), alwaysMatchSimple)
          case tp2: ExprType =>
            tp1.paramNames.isEmpty &&
              matchesType(tp1.resultType, tp2.resultType, alwaysMatchSimple)
          case _ =>
            false
        }
      case tp1: ExprType =>
        tp2 match {
          case tp2: MethodType =>
            tp2.paramNames.isEmpty &&
              matchesType(tp1.resultType, tp2.resultType, alwaysMatchSimple)
          case tp2: ExprType =>
            matchesType(tp1.resultType, tp2.resultType, alwaysMatchSimple)
          case _ =>
            false // was: matchesType(tp1.resultType, tp2, alwaysMatchSimple)
        }
      case tp1: PolyType =>
        tp2 match {
          case tp2: PolyType =>
            sameLength(tp1.paramNames, tp2.paramNames) &&
              matchesType(tp1.resultType, tp2.resultType.subst(tp2, tp1), alwaysMatchSimple)
          case _ =>
            false
        }
      case _ =>
        tp2 match {
          case _: MethodType | _: PolyType =>
            false
          case tp2: ExprType =>
            false // was: matchesType(tp1, tp2.resultType, alwaysMatchSimple)
          case _ =>
            alwaysMatchSimple || isSameType(tp1, tp2)
        }
      }

    /** Are `syms1` and `syms2` parameter lists with pairwise equivalent types? */
    private def matchingParams(formals1: List[Type], formals2: List[Type], isJava1: Boolean, isJava2: Boolean): Boolean = formals1 match {
      case formal1 :: rest1 =>
        formals2 match {
          case formal2 :: rest2 =>
            (  isSameType(formal1, formal2)
            || isJava1 && formal2 == defn.ObjectType && formal1 == defn.AnyType
            || isJava2 && formal1 == defn.ObjectType && formal2 == defn.AnyType
            ) && matchingParams(rest1, rest2, isJava1, isJava2)
          case nil =>
            false
        }
      case nil =>
        formals2.isEmpty
    }

    def isSameType(tp1: Type, tp2: Type): Boolean =
      if (tp1 == NoType || tp2 == NoType) false
      else if (tp1 eq tp2) true
      else isSubType(tp1, tp2) && isSubType(tp2, tp1)
  }
}