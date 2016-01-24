/* Copyright 2009-2015 EPFL, Lausanne */

package leon
package synthesis
package rules

import leon.purescala.Common.Identifier
import purescala.Expressions._
import purescala.Extractors._
import purescala.Constructors._

import solvers._

import scala.concurrent.duration._

/** For every pair of input variables of the same type,
  * checks equality and output an If-Then-Else statement with the two new branches. */
case object EqualitySplit extends Rule("Eq. Split") {
  def instantiateOn(implicit hctx: SearchContext, p: Problem): Traversable[RuleInstantiation] = {
    // We approximate knowledge of equality based on facts found at the top-level
    // we don't care if the variables are known to be equal or not, we just
    // don't want to split on two variables for which only one split
    // alternative is viable. This should be much less expensive than making
    //  calls to a solver for each pair.
    var facts = Set[Set[Identifier]]()

    def addFacts(e: Expr): Unit = e match {
      case Not(e) => addFacts(e)
      case LessThan(Variable(a), Variable(b))      => facts += Set(a,b)
      case LessEquals(Variable(a), Variable(b))    => facts += Set(a,b)
      case GreaterThan(Variable(a), Variable(b))   => facts += Set(a,b)
      case GreaterEquals(Variable(a), Variable(b)) => facts += Set(a,b)
      case Equals(Variable(a), Variable(b))        => facts += Set(a,b)
      case _ =>
    }

    val TopLevelAnds(as) = and(p.pc, p.phi)
    for (e <- as) {
      addFacts(e)
    }

    val candidates = p.as.groupBy(_.getType).mapValues{ as =>
      as.combinations(2).filterNot(facts contains _.toSet)
    }.values.flatten

    candidates.flatMap {
      case List(a1, a2) =>

        val sub1 = p.copy(
          pc = and(Equals(Variable(a1), Variable(a2)), p.pc),
          eb = p.qeb.filterIns( (m: Map[Identifier, Expr]) => m(a1) == m(a2))
        )
        val sub2 = p.copy(
          pc = and(not(Equals(Variable(a1), Variable(a2))), p.pc),
          eb = p.qeb.filterIns( (m: Map[Identifier, Expr]) => m(a1) != m(a2))
        )

        val onSuccess: List[Solution] => Option[Solution] = {
          case List(s1, s2) =>
            Some(Solution(or(s1.pre, s2.pre), s1.defs ++ s2.defs, IfExpr(Equals(Variable(a1), Variable(a2)), s1.term, s2.term), s1.isTrusted && s2.isTrusted))
          case _ =>
            None
        }

        Some(decomp(List(sub1, sub2), onSuccess, s"Eq. Split on '$a1' and '$a2'"))
      case _ =>
        None
    }
  }
}
