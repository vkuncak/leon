/* Copyright 2009-2015 EPFL, Lausanne */

package leon
package solvers
package z3

import utils._
import _root_.z3.scala._

import purescala.Printable
import purescala.Common._
import purescala.Definitions._
import purescala.Expressions._
import purescala.Constructors._
import purescala.Quantification._
import purescala.ExprOps._
import purescala.Types._

import solvers.templates._
import Template._

import evaluators._

import termination._

class FairZ3Solver(val context: LeonContext, val program: Program)
  extends AbstractZ3Solver
     with Z3ModelReconstruction
     with FairZ3Component
     with EvaluatingSolver
     with QuantificationSolver {

  enclosing =>

  val feelingLucky      = context.findOptionOrDefault(optFeelingLucky)
  val checkModels       = context.findOptionOrDefault(optCheckModels)
  val useCodeGen        = context.findOptionOrDefault(optUseCodeGen)
  val evalGroundApps    = context.findOptionOrDefault(optEvalGround)
  val unrollUnsatCores  = context.findOptionOrDefault(optUnrollCores)
  val assumePreHolds    = context.findOptionOrDefault(optAssumePre)
  val disableChecks     = context.findOptionOrDefault(optNoChecks)

  assert(!checkModels || !disableChecks, "Options \"checkmodels\" and \"nochecks\" are mutually exclusive")

  protected val errors     = new IncrementalBijection[Unit, Boolean]()
  protected def hasError   = errors.getB(()) contains true
  protected def addError() = errors += () -> true

  protected[z3] def getEvaluator : Evaluator = evaluator

  private val terminator : TerminationChecker = new SimpleTerminationChecker(context, program)

  protected[z3] def getTerminator : TerminationChecker = terminator

  // FIXME: Dirty hack to bypass z3lib bug. Assumes context is the same over all instances of FairZ3Solver
  protected[leon] val z3cfg = context.synchronized { new Z3Config(
    "MODEL" -> true,
    "TYPE_CHECK" -> true,
    "WELL_SORTED_CHECK" -> true
  )}
  toggleWarningMessages(true)

  private def extractModel(model: Z3Model, ids: Set[Identifier]): HenkinModel = {
    def extract(b: Z3AST, m: Matcher[Z3AST]): Set[Seq[Expr]] = {
      val QuantificationTypeMatcher(fromTypes, _) = m.tpe
      val optEnabler = model.evalAs[Boolean](b)

      if (optEnabler == Some(true)) {
        val optArgs = (m.args zip fromTypes).map {
          p => softFromZ3Formula(model, model.eval(p._1.encoded, true).get, p._2)
        }

        if (optArgs.forall(_.isDefined)) {
          Set(optArgs.map(_.get))
        } else {
          Set.empty
        }
      } else {
        Set.empty
      }
    }

    val (typeInsts, partialInsts, lambdaInsts) = templateGenerator.manager.instantiations

    val typeDomains: Map[TypeTree, Set[Seq[Expr]]] = typeInsts.map {
      case (tpe, domain) => tpe -> domain.flatMap { case (b, m) => extract(b, m) }.toSet
    }

    val funDomains: Map[Identifier, Set[Seq[Expr]]] = partialInsts.flatMap {
      case (c, domain) => variables.getA(c).collect {
        case Variable(id) => id -> domain.flatMap { case (b, m) => extract(b, m) }.toSet
      }
    }

    val lambdaDomains: Map[Lambda, Set[Seq[Expr]]] = lambdaInsts.map {
      case (l, domain) => l -> domain.flatMap { case (b, m) => extract(b, m) }.toSet
    }

    val asMap = modelToMap(model, ids)
    val asDMap = purescala.Quantification.extractModel(asMap, funDomains, typeDomains, evaluator)
    val domains = new HenkinDomains(lambdaDomains, typeDomains)
    new HenkinModel(asDMap, domains)
  }

  implicit val z3Printable = (z3: Z3AST) => new Printable {
    def asString(implicit ctx: LeonContext) = z3.toString
  }

  val templateGenerator = new TemplateGenerator(new TemplateEncoder[Z3AST] {
    def encodeId(id: Identifier): Z3AST = {
      idToFreshZ3Id(id)
    }

    def encodeExpr(bindings: Map[Identifier, Z3AST])(e: Expr): Z3AST = {
      toZ3Formula(e, bindings)
    }

    def substitute(substMap: Map[Z3AST, Z3AST]): Z3AST => Z3AST = {
      val (from, to) = substMap.unzip
      val (fromArray, toArray) = (from.toArray, to.toArray)

      (c: Z3AST) => z3.substitute(c, fromArray, toArray)
    }

    def mkNot(e: Z3AST) = z3.mkNot(e)
    def mkOr(es: Z3AST*) = z3.mkOr(es : _*)
    def mkAnd(es: Z3AST*) = z3.mkAnd(es : _*)
    def mkEquals(l: Z3AST, r: Z3AST) = z3.mkEq(l, r)
    def mkImplies(l: Z3AST, r: Z3AST) = z3.mkImplies(l, r)
  }, assumePreHolds)


  initZ3()

  val solver = z3.mkSolver()

  private val freeVars    = new IncrementalSet[Identifier]()
  private val constraints = new IncrementalSeq[Expr]()

  val unrollingBank = new UnrollingBank(context, templateGenerator)

  private val incrementals: List[IncrementalState] = List(
    errors, freeVars, constraints, functions, generics, lambdas, sorts, variables,
    constructors, selectors, testers, unrollingBank
  )

  def push() {
    solver.push()
    incrementals.foreach(_.push())
  }

  def pop() {
    solver.pop(1)
    incrementals.foreach(_.pop())
  }

  override def check: Option[Boolean] = {
    if (hasError) {
      None
    } else {
      fairCheck(Set())
    }
  }

  override def checkAssumptions(assumptions: Set[Expr]): Option[Boolean] = {
    if (hasError) {
      None
    } else {
      fairCheck(assumptions)
    }
  }

  var foundDefinitiveAnswer = false
  var definitiveAnswer : Option[Boolean] = None
  var definitiveModel  : HenkinModel = HenkinModel.empty
  var definitiveCore   : Set[Expr] = Set.empty

  def assertCnstr(expression: Expr) {
    try {
      val newFreeVars = variablesOf(expression)
      freeVars ++= newFreeVars

      // We make sure all free variables are registered as variables
      freeVars.foreach { v =>
        variables.cachedB(Variable(v)) {
          templateGenerator.encoder.encodeId(v)
        }
      }

      constraints += expression

      val newClauses = unrollingBank.getClauses(expression, variables.aToB)

      for (cl <- newClauses) {
        solver.assertCnstr(cl)
      }
    } catch {
      case _: Unsupported =>
        addError()
    }
  }

  def getModel = {
    definitiveModel
  }

  def getUnsatCore = {
    definitiveCore
  }

  def fairCheck(assumptions: Set[Expr]): Option[Boolean] = {
    foundDefinitiveAnswer = false

    def entireFormula  = andJoin(assumptions.toSeq ++ constraints.toSeq)

    def foundAnswer(answer: Option[Boolean], model: HenkinModel = HenkinModel.empty, core: Set[Expr] = Set.empty) : Unit = {
      foundDefinitiveAnswer = true
      definitiveAnswer = answer
      definitiveModel  = model
      definitiveCore   = core
    }

    // these are the optional sequence of assumption literals
    val assumptionsAsZ3: Seq[Z3AST]    = assumptions.map(toZ3Formula(_)).toSeq
    val assumptionsAsZ3Set: Set[Z3AST] = assumptionsAsZ3.toSet

    def z3CoreToCore(core: Seq[Z3AST]): Set[Expr] = {
      core.filter(assumptionsAsZ3Set).map(ast => fromZ3Formula(null, ast, BooleanType) match {
          case n @ Not(Variable(_)) => n
          case v @ Variable(_) => v
          case x => scala.sys.error("Impossible element extracted from core: " + ast + " (as Leon tree : " + x + ")")
      }).toSet
    }

    def validatedModel(silenceErrors: Boolean) : (Boolean, HenkinModel) = {
      if (interrupted) {
        (false, HenkinModel.empty)
      } else {
        val lastModel = solver.getModel
        val clauses = templateGenerator.manager.checkClauses
        val optModel = if (clauses.isEmpty) Some(lastModel) else {
          solver.push()
          for (clause <- clauses) {
            solver.assertCnstr(clause)
          }

          reporter.debug(" - Enforcing model transitivity")
          val timer = context.timers.solvers.z3.check.start()
          solver.push() // FIXME: remove when z3 bug is fixed
          val res = solver.checkAssumptions((assumptionsAsZ3 ++ unrollingBank.satisfactionAssumptions) :_*)
          solver.pop()  // FIXME: remove when z3 bug is fixed
          timer.stop()

          val solverModel = res match {
            case Some(true) =>
              Some(solver.getModel)

            case Some(false) =>
              val msg = "- Transitivity independence not guaranteed for model"
              if (silenceErrors) {
                reporter.debug(msg)
              } else {
                reporter.warning(msg)
              }
              None

            case None =>
              val msg = "- Unknown for transitivity independence!?"
              if (silenceErrors) {
                reporter.debug(msg)
              } else {
                reporter.warning(msg)
              }
              None
          }

          solver.pop()
          solverModel
        }

        val model = optModel getOrElse lastModel

        val functionsModel: Map[Z3FuncDecl, (Seq[(Seq[Z3AST], Z3AST)], Z3AST)] = model.getModelFuncInterpretations.map(i => (i._1, (i._2, i._3))).toMap
        val functionsAsMap: Map[Identifier, Expr] = functionsModel.flatMap(p => {
          if (functions containsB p._1) {
            val tfd = functions.toA(p._1)
            if (!tfd.hasImplementation) {
              val (cses, default) = p._2
              val ite = cses.foldLeft(fromZ3Formula(model, default, tfd.returnType))((expr, q) => IfExpr(
                andJoin(
                  q._1.zip(tfd.params).map(a12 => Equals(fromZ3Formula(model, a12._1, a12._2.getType), Variable(a12._2.id)))
                ),
                fromZ3Formula(model, q._2, tfd.returnType),
                expr))
              Seq((tfd.id, ite))
            } else Seq()
          } else Seq()
        })

        val constantFunctionsAsMap: Map[Identifier, Expr] = model.getModelConstantInterpretations.flatMap(p => {
          if(functions containsB p._1) {
            val tfd = functions.toA(p._1)
            if(!tfd.hasImplementation) {
              Seq((tfd.id, fromZ3Formula(model, p._2, tfd.returnType)))
            } else Seq()
          } else Seq()
        }).toMap

        val leonModel = extractModel(model, freeVars.toSet)
        val fullModel = leonModel ++ (functionsAsMap ++ constantFunctionsAsMap)

        if (!optModel.isDefined) {
          (false, leonModel)
        } else {
          (evaluator.check(entireFormula, fullModel) match {
            case EvaluationResults.CheckSuccess =>
              reporter.debug("- Model validated.")
              true

            case EvaluationResults.CheckValidityFailure =>
              reporter.debug("- Invalid model.")
              false

            case EvaluationResults.CheckRuntimeFailure(msg) =>
              if (silenceErrors) {
                reporter.debug("- Model leads to evaluation error: " + msg)
              } else {
                reporter.warning("- Model leads to evaluation error: " + msg)
              }
              false

            case EvaluationResults.CheckQuantificationFailure(msg) =>
              if (silenceErrors) {
                reporter.debug("- Model leads to quantification error: " + msg)
              } else {
                reporter.warning("- Model leads to quantification error: " + msg)
              }
              false
          }, leonModel)
        }
      }
    }

    while(!foundDefinitiveAnswer && !interrupted) {

      //val blockingSetAsZ3 : Seq[Z3AST] = blockingSet.toSeq.map(toZ3Formula(_).get)
      // println("Blocking set : " + blockingSet)

      reporter.debug(" - Running Z3 search...")

      //reporter.debug("Searching in:\n"+solver.getAssertions.toSeq.mkString("\nAND\n"))
      //reporter.debug("Unroll.  Assumptions:\n"+unrollingBank.z3CurrentZ3Blockers.mkString("  &&  "))
      //reporter.debug("Userland Assumptions:\n"+assumptionsAsZ3.mkString("  &&  "))

      val timer = context.timers.solvers.z3.check.start()
      solver.push() // FIXME: remove when z3 bug is fixed
      val res = solver.checkAssumptions((assumptionsAsZ3 ++ unrollingBank.satisfactionAssumptions) :_*)
      solver.pop()  // FIXME: remove when z3 bug is fixed
      timer.stop()

      reporter.debug(" - Finished search with blocked literals")

      lazy val allVars: Set[Identifier] = freeVars.toSet

      res match {
        case None =>
          reporter.ifDebug { debug => 
            if (solver.getReasonUnknown != "canceled") {
              debug("Z3 returned unknown: " + solver.getReasonUnknown)
            }
          }
          foundAnswer(None)

        case Some(true) => // SAT
          val (valid, model) = if (!this.disableChecks && (this.checkModels || requireQuantification)) {
            validatedModel(false)
          } else {
            true -> extractModel(solver.getModel, allVars)
          }

          if (valid) {
            foundAnswer(Some(true), model)
          } else {
            reporter.error("Something went wrong. The model should have been valid, yet we got this : ")
            reporter.error(model.asString(context))
            foundAnswer(None, model)
          }

        case Some(false) if !unrollingBank.canUnroll =>

          val core = z3CoreToCore(solver.getUnsatCore())

          foundAnswer(Some(false), core = core)

        // This branch is both for with and without unsat cores. The
        // distinction is made inside.
        case Some(false) =>

          def coreElemToBlocker(c: Z3AST): (Z3AST, Boolean) = {
            z3.getASTKind(c) match {
              case Z3AppAST(decl, args) =>
                z3.getDeclKind(decl) match {
                  case Z3DeclKind.OpNot =>
                    (args.head, true)
                  case Z3DeclKind.OpUninterpreted =>
                    (c, false)
                }

              case ast =>
                (c, false)
            }
          }

          if (unrollUnsatCores) {
            unrollingBank.decreaseAllGenerations()

            for (c <- solver.getUnsatCore()) {
              val (z3ast, pol) = coreElemToBlocker(c)
              assert(pol)

              unrollingBank.promoteBlocker(z3ast)
            }

          }

          //debug("UNSAT BECAUSE: "+solver.getUnsatCore.mkString("\n  AND  \n"))
          //debug("UNSAT BECAUSE: "+core.mkString("  AND  "))

          if (!interrupted) {
            if (this.feelingLucky) {
              // we need the model to perform the additional test
              reporter.debug(" - Running search without blocked literals (w/ lucky test)")
            } else {
              reporter.debug(" - Running search without blocked literals (w/o lucky test)")
            }

            val timer = context.timers.solvers.z3.check.start()
            solver.push() // FIXME: remove when z3 bug is fixed
            val res2 = solver.checkAssumptions((assumptionsAsZ3 ++ unrollingBank.refutationAssumptions) : _*)
            solver.pop()  // FIXME: remove when z3 bug is fixed
            timer.stop()

            reporter.debug(" - Finished search without blocked literals")

            res2 match {
              case Some(false) =>
                //reporter.debug("UNSAT WITHOUT Blockers")
                foundAnswer(Some(false), core = z3CoreToCore(solver.getUnsatCore))
              case Some(true) =>
                //reporter.debug("SAT WITHOUT Blockers")
                if (this.feelingLucky && !interrupted) {
                  // we might have been lucky :D
                  val (wereWeLucky, cleanModel) = validatedModel(true)

                  if(wereWeLucky) {
                    foundAnswer(Some(true), cleanModel)
                  }
                }

              case None =>
                foundAnswer(None)
            }
          }

          if(interrupted) {
            foundAnswer(None)
          }

          if(!foundDefinitiveAnswer) { 
            reporter.debug("- We need to keep going.")

            val toRelease = unrollingBank.getBlockersToUnlock

            reporter.debug(" - more unrollings")

            val newClauses = unrollingBank.unrollBehind(toRelease)

            for(ncl <- newClauses) {
              solver.assertCnstr(ncl)
            }

            //readLine()

            reporter.debug(" - finished unrolling")
          }
      }
    }

    if(interrupted) {
      None
    } else {
      definitiveAnswer
    }
  }
}
