package valium.plugin
package transform
package coerce

import scala.tools.nsc.transform.TypingTransformers
import scala.tools.nsc.typechecker.Analyzer
import scala.tools.nsc.Phase
import scala.reflect.internal.Mode
import scala.util.DynamicVariable

trait ValiumCoerceTreeTransformer extends TypingTransformers {
  this: ValiumCoercePhase =>

  import global._
  import definitions._
  import helper._

  class CoercePhase(prev: Phase) extends StdPhase(prev) {
    override def name = ValiumCoerceTreeTransformer.this.phaseName
    override def checkable = false
    def apply(unit: CompilationUnit): Unit = {
      val tree = afterCoerce(new TreeAdapters().adapt(unit))
      tree.foreach(node => assert(node.tpe != null, node))
    }
  }

  class TreeAdapters extends Analyzer {
    var indent = 0
    lazy val global: ValiumCoerceTreeTransformer.this.global.type = ValiumCoerceTreeTransformer.this.global

    def adapt(unit: CompilationUnit): Tree = {
      val context = rootContext(unit)
      val checker = new TreeAdapter(context)
      unit.body = checker.typed(unit.body)
      unit.body
    }

    var normalTyper = false

    def withNormalTyper[T](context: Context)(f: Typer => T): T = {
      val normalTyper0 = normalTyper
      normalTyper = true
      val res = f(newTyper(context))
      normalTyper = normalTyper0
      res
    }

    override def newTyper(context: Context): Typer =
      if (normalTyper) {
        super.newTyper(context)
      } else {
        new TreeAdapter(context)
      }

    def adaptdbg(ind: Int, msg: => String): Unit = {
      valiumlog("  " * ind + msg)
    }

    class TreeAdapter(context0: Context) extends Typer(context0) {
      override protected def finishMethodSynthesis(templ: Template, clazz: Symbol, context: Context): Template =
        templ

      def supertyped(tree: Tree, mode: Mode, pt: Type): Tree =
        super.typed(tree, mode, pt)

      override protected def adapt(tree: Tree, mode: Mode, pt: Type, original: Tree = EmptyTree): Tree = {
        val oldTpe = tree.tpe
        val newTpe = pt
        def typeMismatch = oldTpe.isUnboxedValiumRef ^ newTpe.isUnboxedValiumRef
        def dontAdapt = tree.isType || pt.isWildcard
        if (typeMismatch && !dontAdapt) {
          val conversion = if (oldTpe.isUnboxedValiumRef) unbox2box else box2unbox
          val tree1 = atPos(tree.pos)(Apply(gen.mkAttributedRef(conversion), List(tree)))
          val tree2 = super.typed(tree1, mode, pt)
          assert(tree2.tpe != ErrorType, tree2)
          tree2
        } else {
          super.adapt(tree, mode, pt, original)
        }
      }

      override def typed(tree: Tree, mode: Mode, pt: Type): Tree = {
        val ind = indent
        indent += 1
        adaptdbg(ind, " <== " + tree + ": " + showRaw(pt, true, true, false, false))
        val res = tree match {
          case EmptyTree | TypeTree() =>
            super.typed(tree, mode, pt)
          // IMPORTANT NOTE: ErrorType should be allowed to bubble up, since there will certainly be
          // a silent(_.typed(...)) ready to catch the error and perform the correct rewriting upstream.
          case _ if tree.tpe == null || tree.tpe == ErrorType =>
            super.typed(tree, mode, pt)
          case Select(qual, mth) if qual.isUnboxedValiumRef =>
            val boxed = atPos(tree.pos)(Apply(gen.mkAttributedRef(unbox2box), List(qual)))
            super.typed(Select(boxed, mth) setSymbol tree.symbol, mode, pt)
          case _ =>
            val oldTree = tree.duplicate
            val oldTpe = tree.tpe
            tree.clearType()
            super.typed(tree, mode, pt)
        }
        adaptdbg(ind, " ==> " + res + ": " + res.tpe)
        if (res.tpe == ErrorType)
          adaptdbg(ind, "ERRORS: " + context.errors)
        indent -= 1
        res
      }
    }
  }
}