/* Copyright 2009-2015 EPFL, Lausanne */

package leon
package synthesis
package graph

import leon.utils.StreamUtils.cartesianProduct
import leon.utils.DebugSectionSynthesis

sealed class Graph(val cm: CostModel, problem: Problem) {
  val root = new RootNode(cm, problem)

  // Returns closed/total
  def getStats(from: Node = root): (Int, Int) = {
    val isClosed = from.isDeadEnd || from.isSolved
    val self = (if (isClosed) 1 else 0, 1)

    if (!from.isExpanded) {
      self
    } else {
      from.descendants.foldLeft(self) {
        case ((c,t), d) =>
          val (sc, st) = getStats(d)
          (c+sc, t+st)
      }
    }
  }
}

sealed abstract class Node(cm: CostModel, val parent: Option[Node]) {
  var parents: List[Node]     = parent.toList
  var descendants: List[Node] = Nil

  def asString(implicit ctx: LeonContext): String

  // indicates whether this particular node has already been expanded
  var isExpanded: Boolean = false
  def expand(hctx: SearchContext)

  val p: Problem

  var isSolved: Boolean   = false
  def onSolved(desc: Node)

  // Solutions this terminal generates (!= None for terminals)
  var solutions: Option[Stream[Solution]] = None
  var selectedSolution = -1

  // Costs
  var cost: Cost = computeCost()

  def isDeadEnd: Boolean = {
    cm.isImpossible(cost)
  }

  // For non-terminals, selected childs for solution
  var selected: List[Node] = Nil

  def composeSolutions(sols: List[Stream[Solution]]): Stream[Solution]

  // Generate solutions given selection+solutions
  def generateSolutions(): Stream[Solution] = {
    solutions.getOrElse {
      composeSolutions(selected.map(n => n.generateSolutions()))
    }
  }

  def computeCost(): Cost = solutions match {
    case Some(sols) if sols.isEmpty =>
      cm.impossible

    case Some(sols) =>
      if (sols.hasDefiniteSize) {
        sols.map { sol => cm.solution(sol) } .min
      } else {
        cm.solution(sols.head)
      }

    case None =>
      val costs = if (isExpanded) {
        Some(descendants.map { _.cost })
      } else {
        None
      }

      this match {
        case an: AndNode =>
          cm.andNode(an, costs)

        case on: OrNode =>
          costs.map(_.min).getOrElse(cm.problem(on.p))
      }
  }

  def updateCost(): Unit = {
    cost = computeCost()
    parents.foreach(_.updateCost())
  }
}

/** Represents the conjunction of search nodes.
  * @param cm The cost model used when prioritizing, evaluating and expanding
  * @param parent Some node. None if it is the root node.
  * @param ri The rule instantiation that created this AndNode.
  **/
class AndNode(cm: CostModel, parent: Option[Node], val ri: RuleInstantiation) extends Node(cm, parent) {
  val p = ri.problem

  override def asString(implicit ctx: LeonContext) = "\u2227 "+ri.asString

  def expand(hctx: SearchContext): Unit = {
    require(!isExpanded)
    isExpanded = true

    implicit val ctx = hctx.sctx.context

    import hctx.sctx.reporter.info

    val prefix = f"[${Option(ri.rule).getOrElse("?")}%-20s] "

    info(prefix+ri.problem.asString)

    ri.apply(hctx) match {
      case RuleClosed(sols) =>
        solutions = Some(sols)
        selectedSolution = 0

        updateCost()

        isSolved = sols.nonEmpty

        if (sols.isEmpty) {
          info(prefix+"Failed")
        } else {
          val sol = sols.head
          info(prefix+"Solved"+(if(sol.isTrusted) "" else " (untrusted)")+" with: "+sol.asString+"...")
        }

        parents.foreach{ p =>
          p.updateCost()
          if (isSolved) {
            p.onSolved(this)
          }
        }

      case RuleExpanded(probs) =>
        info(prefix+"Decomposed into:")
        for(p <- probs) {
          info(prefix+"     - "+p.asString)
        }

        descendants = probs.map(p => new OrNode(cm, Some(this), p))

        selected = descendants

        updateCost()
    }
  }

  def composeSolutions(solss: List[Stream[Solution]]): Stream[Solution] = {
    cartesianProduct(solss).flatMap {
      sols => ri.onSuccess(sols)
    }
  }

  private var solveds = Set[Node]()

  def onSolved(desc: Node): Unit = {
    // We store everything within solveds
    solveds += desc

    // Everything is solved correctly
    if (solveds.size == descendants.size) {
      isSolved = true
      parents.foreach(_.onSolved(this))
    }
  }

}

class OrNode(cm: CostModel, parent: Option[Node], val p: Problem) extends Node(cm, parent) {

  override def asString(implicit ctx: LeonContext) = "\u2228 "+p.asString

  implicit val debugSection = DebugSectionSynthesis
  
  def getInstantiations(hctx: SearchContext): List[RuleInstantiation] = {
    val rules = hctx.sctx.rules

    val rulesPrio = rules.groupBy(_.priority).toSeq.sortBy(_._1)

    for ((_, rs) <- rulesPrio) {
      
      val results = rs.flatMap{ r =>
        hctx.context.reporter.ifDebug(printer => printer("Testing rule: " + r))
        hctx.context.timers.synthesis.instantiations.get(r.asString(hctx.sctx.context)).timed {
          r.instantiateOn(hctx, p)
        }
      }.toList

      if (results.nonEmpty) {
        return results
      }
    }
    Nil
  }

  def expand(hctx: SearchContext): Unit = {
    require(!isExpanded)

    val ris = getInstantiations(hctx)

    descendants = ris.map(ri => new AndNode(cm, Some(this), ri))
    selected = List()

    updateCost()

    isExpanded = true
  }

  def onSolved(desc: Node): Unit = {
    isSolved = true
    selected = List(desc)
    parents.foreach(_.onSolved(this))
  }

  def composeSolutions(solss: List[Stream[Solution]]): Stream[Solution] = {
    solss.toStream.flatten
  }
}

class RootNode(cm: CostModel, p: Problem) extends OrNode(cm, None, p)
