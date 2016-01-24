/* Copyright 2009-2015 EPFL, Lausanne */

package leon
package synthesis
package rules

import purescala.Expressions._
import purescala.Common._
import purescala.Types._
import purescala.ExprOps._
import purescala.Extractors._
import purescala.Constructors._
import purescala.Definitions._

/** Abstract data type split. If a variable is typed as an abstract data type, then
  * it will create a match case statement on all known subtypes. */
case object ADTSplit extends Rule("ADT Split.") {
  def instantiateOn(implicit hctx: SearchContext, p: Problem): Traversable[RuleInstantiation] = {
    // We approximate knowledge of types based on facts found at the top-level
    // we don't care if the variables are known to be equal or not, we just
    // don't want to split on two variables for which only one split
    // alternative is viable. This should be much less expensive than making
    //  calls to a solver for each pair.
    var facts = Map[Identifier, CaseClassType]()

    def addFacts(e: Expr): Unit = e match {
      case Equals(Variable(a), CaseClass(cct, _))         => facts += a -> cct
      case IsInstanceOf(Variable(a), cct: CaseClassType)  => facts += a -> cct
      case _ =>
    }

    val TopLevelAnds(as) = and(p.pc, p.phi)
    for (e <- as) {
      addFacts(e)
    }

    val candidates = p.as.collect {
      case IsTyped(id, act @ AbstractClassType(cd, tpes)) =>

        val optCases = cd.knownDescendants.sortBy(_.id.name).collect {
          case ccd : CaseClassDef =>
            val cct = CaseClassType(ccd, tpes)

            if (facts contains id) {
              if (cct == facts(id)) {
                Seq(ccd)
              } else {
                Nil
              }
            } else {
              Seq(ccd)
            }
        }

        val cases = optCases.flatten

        if (cases.nonEmpty) {
          Some((id, act, cases))
        } else {
          None
        }
    }

    candidates.collect {
      case Some((id, act, cases)) =>
        val oas = p.as.filter(_ != id)

        val subInfo = for(ccd <- cases) yield {
           val cct    = CaseClassType(ccd, act.tps)

           val args   = cct.fields.map { vd => FreshIdentifier(vd.id.name, vd.getType, true) }.toList

           val subPhi = subst(id -> CaseClass(cct, args.map(Variable)), p.phi)
           val subPC  = subst(id -> CaseClass(cct, args.map(Variable)), p.pc)
           val subWS  = subst(id -> CaseClass(cct, args.map(Variable)), p.ws)

           val eb2 = p.qeb.mapIns { inInfo =>
              inInfo.toMap.apply(id) match {
                case CaseClass(`cct`, vs) =>
                  List(vs ++ inInfo.filter(_._1 != id).map(_._2))
                case _ =>
                  Nil
              }
           }

           val subProblem = Problem(args ::: oas, subWS, subPC, subPhi, p.xs, eb2)
           val subPattern = CaseClassPattern(None, cct, args.map(id => WildcardPattern(Some(id))))

           (cct, subProblem, subPattern)
        }


        val onSuccess: List[Solution] => Option[Solution] = {
          case sols =>
            var globalPre = List[Expr]()

            val cases = for ((sol, (cct, problem, pattern)) <- sols zip subInfo) yield {
              if (sol.pre != BooleanLiteral(true)) {
                val substs = (for ((field,arg) <- cct.classDef.fields zip problem.as ) yield {
                  (arg, caseClassSelector(cct, id.toVariable, field.id))
                }).toMap
                globalPre ::= and(IsInstanceOf(Variable(id), cct), replaceFromIDs(substs, sol.pre))
              } else {
                globalPre ::= BooleanLiteral(true)
              }

              SimpleCase(pattern, sol.term)
            }

            Some(Solution(orJoin(globalPre), sols.flatMap(_.defs).toSet, matchExpr(Variable(id), cases), sols.forall(_.isTrusted)))
        }

        decomp(subInfo.map(_._2).toList, onSuccess, s"ADT Split on '${id.asString}'")
    }
  }
}
