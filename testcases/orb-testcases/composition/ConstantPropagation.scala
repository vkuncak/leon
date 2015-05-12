import leon.lang._
import leon.annotation._
import leon.collection._
import leon._
import leon.invariant._
import leon.instrumentation._

object IntLattice {
  abstract class Element
  case class Bot() extends Element
  case class Top() extends Element
  case class BigIntVal(x: BigInt) extends Element

  def height: BigInt = {
    /**
     * A number that depends on the lattice definition.
     * In simplest case it has height 3 (_|_ (bot) <= BigInts <= T (top))
     */
    3
  } ensuring(res => time <= ?)

  def join(oldVal: Element, newVal: Element) = (oldVal, newVal) match {
    case (Bot(), any) => any // bot is the identity for join
    case (any, Bot()) => any
    case (Top(), _) => Top() // top joined with anything is top
    case (_, Top()) => Top()
    case (BigIntVal(x), BigIntVal(y)) if (x == y) => BigIntVal(y)
    case _ =>
      //here old and new vals are different BigIntegers
      Top()
  }
}

object LatticeOps {
  import IntLattice._

  def add(a: Element, b: Element): Element = {
    (a, b) match {
      case (Bot(), _) => Bot()
      case (_, Bot()) => Bot()
      case (Top(), _) => Top()
      case (_, Top()) => Top()
      case (BigIntVal(x), BigIntVal(y)) => BigIntVal(x + y)
    }
  }

  def multiply(a: Element, b: Element): Element = {
    (a, b) match {
      case (_, BigIntVal(x)) if x == 0 => BigIntVal(0)
      case (BigIntVal(x), _) if x == 0 => BigIntVal(0)
      case (Bot(), _) => Bot()
      case (_, Bot()) => Bot()
      case (Top(), _) => Top()
      case (_, Top()) => Top()
      case (BigIntVal(x), BigIntVal(y)) => BigIntVal(x * y)
    }
  }
}

object ConstantPropagation {
  import IntLattice._
  import LatticeOps._

  abstract class Expr
  case class Times(lhs: Expr, rhs: Expr) extends Expr
  case class Plus(lhs: Expr, rhs: Expr) extends Expr
  case class BigIntLiteral(v: BigInt) extends Expr
  case class FunctionCall(calleeName: Identifier, args: List[Expr]) extends Expr
  case class IfThenElse(cond: Expr, thenExpr: Expr, elseExpr: Expr) extends Expr
  case class Identifier(id: BigInt) extends Expr

  /**
   * Definition of a function AST
   */
  case class Function(name: Identifier, params: List[Expr], body: Expr)

  /**
   * Assuming that the functions are ordered from callee to
   * caller and there is no mutual recursion
   */
  case class Program(funcs: List[Function])

  def size(l: List[Function]): BigInt = {
    l match {
      case Cons(_, t) => 1 + size(t)
      case Nil() => 0
    }
  } ensuring(_ >= 0)

  def sizeExprList(exprs: List[Expr]): BigInt = {
    exprs match {
      case Nil() => 0
      case Cons(currExpr, otherExprs) => sizeExpr(currExpr) + sizeExprList(otherExprs)
    }
  } ensuring(_ >= 0)

  def sizeExpr(e: Expr): BigInt = {
    e match {
      case Plus(l, r) => 1 + sizeExpr(l) + sizeExpr(r)
      case Times(l, r) => 1 + sizeExpr(l) + sizeExpr(r)
      case FunctionCall(c, args) => {
        1 + sizeExprList(args)
      }
      case IfThenElse(c, th, el) =>
        1 + sizeExpr(c) + sizeExpr(th) + sizeExpr(el)
      case _ => 1
    }
  } ensuring(_ >= 0)

  def sizeFuncList(funcs: List[Function]): BigInt = {
    funcs match {
      case Nil() => 0
      case Cons(currFunc, otherFuncs) =>
        1 + sizeExpr(currFunc.body) + sizeFuncList(otherFuncs)
    }
  } ensuring(_ >= 0)

  def initToBot(l: List[Function]): List[(BigInt /*function id*/ , Element)] = {
    l match {
      case Nil() => Nil[(BigInt /*function id*/ , Element)]()
      case Cons(fun, tail) => Cons((fun.name.id, Bot()), initToBot(tail))
    }
  } ensuring (res => tmpl ((a, b) => time <= a * size(l) + b))

  def foldConstants(p: Program): Program = {
    val initVals = initToBot(p.funcs)
    val fvals = computeSummaries(p, initVals, height)
    val newfuns = transformFuns(p.funcs, fvals)
    Program(newfuns)
  } ensuring(res => tmpl((a, b, c, d, e) => time <= a*(height*sizeFuncList(p.funcs)) + b*height +  c*sizeFuncList(p.funcs) + d*size(p.funcs) + e))

  /**
   * The initVals is the initial values for the
   * values of the functions
   */
  @compose
  def computeSummaries(p: Program, initVals: List[(BigInt /*function id*/ , Element)], noIters: BigInt): List[(BigInt /*function id*/ , Element)] = {
    require(noIters >= 0)
    if (noIters <= 0) {
      initVals
    } else
      computeSummaries(p, analyzeFuns(p.funcs, initVals, initVals), noIters - 1)
  } ensuring(res =>
      time <= ? * (noIters * sizeFuncList(p.funcs)) + ? * sizeFuncList(p.funcs) + ? * noIters + ? &&
      rec <= noIters + ? &&
      tpr <= ? * sizeFuncList(p.funcs) + ? &&
      true)

  /**
   * Initial fvals and oldVals are the same
   * but as the function progresses, fvals will only have the olds values
   * of the functions that are yet to be processed, whereas oldVals will remain the same.
   */
  def analyzeFuns(funcs: List[Function], fvals: List[(BigInt, Element)], oldVals: List[(BigInt, Element)]): List[(BigInt, Element)] = {
    (funcs, fvals) match {
      case (Cons(f, otherFuns), Cons((fid, fval), otherVals)) =>
        val newval = analyzeFunction(f, oldVals)
        val approxVal = join(fval, newval) //creates an approximation of newVal to ensure convergence
        Cons((fid, approxVal), analyzeFuns (otherFuns, otherVals, oldVals))
      case _ =>
        Nil[(BigInt, Element)]() //this also handles precondition violations e.g. lists aren't of same size etc.
    }
  } ensuring (res => tmpl ((a, b) => time <= a * sizeFuncList(funcs) + b))

  @library
  def getFunctionVal(funcId: BigInt, funcVals: List[(BigInt, Element)]): Element = {
    funcVals match {
      case Nil() => Bot()
      case Cons((currFuncId, currFuncVal), otherFuncVals) if (currFuncId == funcId) => currFuncVal
      case Cons(_, otherFuncVals) =>
        getFunctionVal(funcId, otherFuncVals)
    }
  } ensuring (res => time <= 1)

  def analyzeExprList(l: List[Expr], funcVals: List[(BigInt, Element)]): List[Element] = {
    l match {
      case Nil() => Nil[Element]()
      case Cons(expr, otherExprs) => Cons(analyzeExpr(expr, funcVals), analyzeExprList(otherExprs, funcVals))
    }
  } ensuring (res => tmpl ((a, b) => time <= a * sizeExprList(l) + b))

  /**
   * Returns the value of the expression when "abstractly BigInterpreted"
   * using the lattice.
   */
  def analyzeExpr(e: Expr, funcVals: List[(BigInt, Element)]): Element = {
    e match {
      case Times(lhs: Expr, rhs: Expr) => {
        val lval = analyzeExpr(lhs, funcVals)
        val rval = analyzeExpr(rhs, funcVals)
        multiply(lval, rval)
      }
      case Plus(lhs: Expr, rhs: Expr) => {
        val lval = analyzeExpr(lhs, funcVals)
        val rval = analyzeExpr(rhs, funcVals)
        add(lval, rval)
      }
      case FunctionCall(calleeName, args: List[Expr]) => {
        getFunctionVal(calleeName.id, funcVals)
      }
      case IfThenElse(c, th, el) => {
        //analyze then and else branches and join their values
        //TODO: this can be made more precise e.g. if 'c' is
        //a non-zero value it can only execute the then branch.
        val v1 = analyzeExpr(th, funcVals)
        val v2 = analyzeExpr(el, funcVals)
        join(v1, v2)
      }
      case lit @ BigIntLiteral(v) =>
        BigIntVal(v)

      case Identifier(_) => Bot()
    }
  } ensuring (res => tmpl ((a, b) => time <= a * sizeExpr(e) + b))

  def analyzeFunction(f: Function, oldVals: List[(BigInt, Element)]): Element = {
    // traverse the body of the function and simplify constants
    // for function calls assume the value given by oldVals
    // also for if-then-else statments, take a join of the values along if and else branches
    // assume that bot op any = bot and top op any = top (but this can be made more precise).
    analyzeExpr(f.body, oldVals)
  } ensuring (res => tmpl ((a, b) => time <= a * sizeExpr(f.body) + b))

  def transformExprList(l: List[Expr], funcVals: List[(BigInt, Element)]): List[Expr] = {
    l match {
      case Nil() => Nil[Expr]()
      case Cons(expr, otherExprs) => Cons(transformExpr(expr, funcVals),
        transformExprList(otherExprs, funcVals))
    }
  } ensuring (res => tmpl ((a, b) => time <= a * sizeExprList(l) + b))

  /**
   * Returns the folded expression
   */
  def transformExpr(e: Expr, funcVals: List[(BigInt, Element)]): Expr = {
    e match {
      case Times(lhs: Expr, rhs: Expr) => {
        val foldedLHS = transformExpr(lhs, funcVals)
        val foldedRHS = transformExpr(rhs, funcVals)
        (foldedLHS, foldedRHS) match {
          case (BigIntLiteral(x), BigIntLiteral(y)) =>
            BigIntLiteral(x * y)
          case _ =>
            Times(foldedLHS, foldedRHS)
        }
      }
      case Plus(lhs: Expr, rhs: Expr) => {
        val foldedLHS = transformExpr(lhs, funcVals)
        val foldedRHS = transformExpr(rhs, funcVals)
        (foldedLHS, foldedRHS) match {
          case (BigIntLiteral(x), BigIntLiteral(y)) =>
            BigIntLiteral(x + y)
          case _ =>
            Plus(foldedLHS, foldedRHS)
        }
      }
      case FunctionCall(calleeName, args: List[Expr]) => {
        getFunctionVal(calleeName.id, funcVals) match {
          case BigIntVal(x) =>
            BigIntLiteral(x)
          case _ =>
            val foldedArgs = transformExprList(args, funcVals)
            FunctionCall(calleeName, foldedArgs)
        }
      }
      case IfThenElse(c, th, el) => {
        val foldedCond = transformExpr(c, funcVals)
        val foldedTh = transformExpr(th, funcVals)
        val foldedEl = transformExpr(el, funcVals)
        foldedCond match {
          case BigIntLiteral(x) => {
            if (x != 0) foldedTh
            else foldedEl
          }
          case _ => IfThenElse(foldedCond, foldedTh, foldedEl)
        }
      }
      case _ => e
    }
  } ensuring (res => tmpl ((a, b) => time <= a * sizeExpr(e) + b))

  def transformFuns(funcs: List[Function], fvals: List[(BigInt, Element)]): List[Function] = {
    funcs match {
      case Cons(f, otherFuns) =>
        val newfun = Function(f.name, f.params, transformExpr(f.body, fvals))
        Cons(newfun, transformFuns(otherFuns, fvals))
      case _ =>
        Nil[Function]()
    }
  } ensuring (res => tmpl ((a, b) => time <= a * sizeFuncList(funcs) + b))
}
