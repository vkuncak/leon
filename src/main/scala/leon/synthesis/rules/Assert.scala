/* Copyright 2009-2015 EPFL, Lausanne */

package leon
package synthesis
package rules

import purescala.ExprOps._
import purescala.Extractors._
import purescala.Constructors._

/** Moves the preconditions without output variables to the precondition. */
case object Assert extends NormalizingRule("Assert") {
  def instantiateOn(implicit hctx: SearchContext, p: Problem): Traversable[RuleInstantiation] = {
    p.phi match {
      case TopLevelAnds(exprs) =>
        val xsSet = p.xs.toSet
        val (exprsA, others) = exprs.partition(e => (variablesOf(e) & xsSet).isEmpty)

        if (exprsA.nonEmpty) {
          // If there is no more postcondition, then output the solution.
          if (others.isEmpty) {
            Some(solve(Solution(pre=andJoin(exprsA), defs=Set(), term=tupleWrap(p.xs.map(id => simplestValue(id.getType))))))
          } else {
            val sub = p.copy(pc = andJoin(p.pc +: exprsA), phi = andJoin(others), eb = p.qeb.filterIns(andJoin(exprsA)))

            Some(decomp(List(sub), {
              case (s @ Solution(pre, defs, term)) :: Nil => Some(Solution(pre=andJoin(exprsA :+ pre), defs, term, s.isTrusted))
              case _ => None
            }, "Assert "+andJoin(exprsA).asString))
          }
        } else {
          None
        }
      case _ =>
        None
    }
  }
}

