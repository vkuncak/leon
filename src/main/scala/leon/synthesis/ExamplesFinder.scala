/* Copyright 2009-2015 EPFL, Lausanne */

package leon
package synthesis

import purescala.Expressions._
import purescala.Definitions._
import purescala.ExprOps._
import purescala.Types.TypeTree
import purescala.Common._
import purescala.Constructors._
import purescala.Extractors._
import evaluators._
import grammars._
import bonsai.enumerators._
import codegen._
import datagen._
import solvers._
import solvers.z3._

class ExamplesFinder(ctx0: LeonContext, program: Program) {

  lazy val evaluator = new DefaultEvaluator(ctx, program)

  implicit val ctx = ctx0

  val reporter = ctx.reporter

  def extractFromFunDef(fd: FunDef, partition: Boolean): ExamplesBank = fd.postcondition match {
    case Some(Lambda(Seq(ValDef(id, _)), post)) =>
      // @mk FIXME: make this more general
      val tests = extractTestsOf(post)

      val insIds  = fd.params.map(_.id).toSet
      val outsIds = Set(id)
      val allIds  = insIds ++ outsIds

      val examples = tests.toSeq.flatMap { t =>
        val ids = t.keySet
        if ((ids & allIds) == allIds) {
          Some(InOutExample(fd.params.map(p => t(p.id)), Seq(t(id))))
        } else if ((ids & insIds) == insIds) {
          Some(InExample(fd.params.map(p => t(p.id))))
        } else {
          None
        }
      }

      def isValidTest(e: Example): Boolean = {
        e match {
          case InOutExample(ins, outs) =>
            evaluator.eval(Equals(outs.head, FunctionInvocation(fd.typed(fd.tparams.map(_.tp)), ins))) match {
              case EvaluationResults.Successful(BooleanLiteral(true)) => true
              case _                                                  => false
            }

          case _ => false
        }
      }

      if (partition) {
        val (v, iv) = examples.partition(isValidTest)
        ExamplesBank(v, iv)
      } else {
        ExamplesBank(examples, Nil)
      }
    case None =>
      ExamplesBank(Nil, Nil)
  }

  // Extract examples from the passes found in expression
  def extractFromProblem(p: Problem): ExamplesBank = {
    val testClusters = extractTestsOf(and(p.pc, p.phi))

    // Finally, we keep complete tests covering all as++xs
    val allIds  = (p.as ++ p.xs).toSet
    val insIds  = p.as.toSet

    val examples = testClusters.toSeq.flatMap { t =>
      val ids = t.keySet
      if ((ids & allIds) == allIds) {
        Some(InOutExample(p.as.map(t), p.xs.map(t)))
      } else if ((ids & insIds) == insIds) {
        Some(InExample(p.as.map(t)))
      } else {
        None
      }
    }

    def isValidExample(ex: Example): Boolean = {
      val (mapping, cond) = ex match {
        case io: InOutExample =>
          (Map((p.as zip io.ins) ++ (p.xs zip io.outs): _*), And(p.pc, p.phi))
        case i =>
          ((p.as zip i.ins).toMap, p.pc)
      }

      evaluator.eval(cond, mapping) match {
        case EvaluationResults.Successful(BooleanLiteral(true)) => true
        case _ => false
      }
    }

    ExamplesBank(examples.filter(isValidExample), Seq())
  }

  def generateForPC(ids: List[Identifier], pc: Expr, maxValid: Int = 400, maxEnumerated: Int = 1000): ExamplesBank = {

    val evaluator = new CodeGenEvaluator(ctx, program, CodeGenParams.default)
    val datagen   = new GrammarDataGen(evaluator, ValueGrammar)
    val solverDataGen = new SolverDataGen(ctx, program, (ctx, pgm) => SolverFactory(() => new FairZ3Solver(ctx, pgm)))

    val generatedExamples = datagen.generateFor(ids, pc, maxValid, maxEnumerated).map(InExample(_))

    val solverExamples    = solverDataGen.generateFor(ids, pc, maxValid, maxEnumerated).map(InExample(_))

    ExamplesBank(generatedExamples.toSeq ++ solverExamples.toList, Nil)
  }

  private def extractTestsOf(e: Expr): Set[Map[Identifier, Expr]] = {
    val allTests = collect[Map[Identifier, Expr]] {
      case Passes(ins, outs, cases) =>
        val infos   = extractIds(Tuple(Seq(ins, outs)))
        val ioPairs = cases.flatMap(caseToExamples(ins, outs, _))
        
        val exs     = ioPairs.map{ case (i, o) =>
          val test = Tuple(Seq(i, o))
          val ids  = variablesOf(test)

          // Test could contain expressions, we evaluate
          evaluator.eval(test, ids.map { (i: Identifier) => i -> i.toVariable }.toMap) match {
            case EvaluationResults.Successful(res) => res
            case _                                 => test
          }
        }

        // Check whether we can extract all ids from example
        val results = exs.collect { case e if infos.forall(_._2.isDefinedAt(e)) =>
          infos.map{ case (id, f) => id -> f(e) }.toMap
        }

        results.toSet

      case _ =>
        Set()
    }(e)


    consolidateTests(allTests)
  }


  private def caseToExamples(in: Expr, out: Expr, cs: MatchCase, examplesPerCase: Int = 5): Seq[(Expr,Expr)] = {

    def doSubstitute(subs : Seq[(Identifier, Expr)], e : Expr) = 
      subs.foldLeft(e) { 
        case (from, (id, to)) => replaceFromIDs(Map(id -> to), from)
      }

    if (cs.rhs == out) {
      // The trivial example
      Seq()
    } else {
      // The pattern as expression (input expression)(may contain free variables)
      val (pattExpr, ieMap) = patternToExpression(cs.pattern, in.getType)
      val freeVars = variablesOf(pattExpr).toSeq
      if (exists(_.isInstanceOf[NoTree])(pattExpr)) {
        reporter.warning(cs.pattern.getPos, "Unapply patterns are not supported in IO-example extraction")
        Seq()
      } else if (freeVars.isEmpty) {
        // The input contains no free vars. Trivially return input-output pair
        Seq((pattExpr, doSubstitute(ieMap,cs.rhs)))
      } else {
        // Extract test cases such as    case x if x == s =>
        ((pattExpr, ieMap, cs.optGuard) match {
          case (Variable(id), Seq(), Some(Equals(Variable(id2), s))) if id == id2 =>
            Some((Seq((s, doSubstitute(ieMap, cs.rhs)))))
          case (Variable(id), Seq(), Some(Equals(s, Variable(id2)))) if id == id2 =>
            Some((Seq((s, doSubstitute(ieMap, cs.rhs)))))
          case (a, b, c) =>
            None
        }) getOrElse {
          // If the input contains free variables, it does not provide concrete examples. 
          // We will instantiate them according to a simple grammar to get them.
          val enum = new MemoizedEnumerator[TypeTree, Expr](ValueGrammar.getProductions)
          val values = enum.iterator(tupleTypeWrap(freeVars.map { _.getType }))
          val instantiations = values.map {
            v => freeVars.zip(unwrapTuple(v, freeVars.size)).toMap
          }

          def filterGuard(e: Expr, mapping: Map[Identifier, Expr]): Boolean = cs.optGuard match {
            case Some(guard) =>
              // in -> e should be enough. We shouldn't find any subexpressions of in.
              evaluator.eval(replace(Map(in -> e), guard), mapping) match {
                case EvaluationResults.Successful(BooleanLiteral(true)) => true
                case _ => false
              }

            case None =>
              true
          }
          
          if(cs.optGuard == Some(BooleanLiteral(false))) {
            Nil
          } else (for {
            inst <- instantiations.toSeq
            inR = replaceFromIDs(inst, pattExpr)
            outR = replaceFromIDs(inst, doSubstitute(ieMap, cs.rhs))
            if filterGuard(inR, inst)
          } yield (inR, outR)).take(examplesPerCase)
        }
      }
    }
  }

  /** Check if two tests are compatible.
    * Compatible should evaluate to the same value for the same identifier
    */
  private def isCompatible(m1: Map[Identifier, Expr], m2: Map[Identifier, Expr]) = {
    val ks = m1.keySet & m2.keySet
    ks.nonEmpty && ks.map(m1) == ks.map(m2)
  }

  /** Merge tests t1 and t2 if they are compatible. Return m1 if not.
    */
  private def mergeTest(m1: Map[Identifier, Expr], m2: Map[Identifier, Expr]) = {
    if (!isCompatible(m1, m2)) {
      m1
    } else {
      m1 ++ m2
    }
  }

  /** we now need to consolidate different clusters of compatible tests together
    * t1: a->1, c->3
    * t1: a->1, c->3
    * t2: a->1, b->4
    *   => a->1, b->4, c->3
    */
  private def consolidateTests(ts: Set[Map[Identifier, Expr]]): Set[Map[Identifier, Expr]] = {

    var consolidated = Set[Map[Identifier, Expr]]()
    for (t <- ts) {
      consolidated += t

      consolidated = consolidated.map { c =>
        mergeTest(c, t)
      }
    }
    consolidated
  }

  /** Extract ids in ins/outs args, and compute corresponding extractors for values map
    *
    * Examples:
    * (a,b) =>
    *     a -> _.1
    *     b -> _.2
    *
    * Cons(a, Cons(b, c)) =>
    *     a -> _.head
    *     b -> _.tail.head
    *     c -> _.tail.tail
    */
  private def extractIds(e: Expr): Seq[(Identifier, PartialFunction[Expr, Expr])] = e match {
    case Variable(id) =>
      List((id, { case e => e }))
    case Tuple(vs) =>
      vs.map(extractIds).zipWithIndex.flatMap{ case (ids, i) =>
        ids.map{ case (id, e) =>
          (id, andThen({ case Tuple(vs) => vs(i) }, e))
        }
      }
    case CaseClass(cct, args) =>
      args.map(extractIds).zipWithIndex.flatMap { case (ids, i) =>
        ids.map{ case (id, e) =>
          (id, andThen({ case CaseClass(cct2, vs) if cct2 == cct => vs(i) } ,e))
        }
      }

    case _ =>
      reporter.warning("Unexpected pattern in test-ids extraction: "+e)
      Nil
  }

  // Compose partial functions
  private def andThen(pf1: PartialFunction[Expr, Expr], pf2: PartialFunction[Expr, Expr]): PartialFunction[Expr, Expr] = {
    Function.unlift(pf1.lift(_) flatMap pf2.lift)
  }
}
