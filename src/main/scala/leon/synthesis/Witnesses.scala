/* Copyright 2009-2015 EPFL, Lausanne */

package leon.synthesis

import leon.purescala._
import Types._
import Definitions.TypedFunDef
import Extractors._
import Expressions.Expr
import PrinterHelpers._

object Witnesses {
  
  abstract class Witness extends Expr with Extractable with PrettyPrintable {
    val getType = BooleanType
  }
  
  case class Guide(e : Expr) extends Witness {
    def extract: Option[(Seq[Expr], Seq[Expr] => Expr)] = Some((Seq(e), (es: Seq[Expr]) => Guide(es.head)))

    override def printWith(implicit pctx: PrinterContext): Unit = {
      p"⊙ {$e}"
    }
  }
  
  case class Terminating(tfd: TypedFunDef, args: Seq[Expr]) extends Witness {
    def extract: Option[(Seq[Expr], Seq[Expr] => Expr)] = Some((args, Terminating(tfd, _)))

    override def printWith(implicit pctx: PrinterContext): Unit = {
      p"↓ ${tfd.id}($args)"
    }
  }
  
}
