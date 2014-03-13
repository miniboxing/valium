package valium.plugin
package transform
package verify

import scala.tools.nsc.transform.TypingTransformers
import scala.tools.nsc.Phase

trait ValiumVerifyTreeTransformer extends TypingTransformers {
  this: ValiumVerifyPhase =>

  import global._
  import definitions._
  import helper._

  class VerifyPhase(prev: Phase) extends StdPhase(prev) {
    override def name = ValiumVerifyTreeTransformer.this.phaseName
    def apply(unit: CompilationUnit): Unit = {
      object VerifyTraverser extends Traverser {
        override def traverse(tree: Tree): Unit = tree match {
          case ClassDef(_, _, _, Template(_, _, stats)) if tree.symbol.isValiumClass =>
            if (tree.symbol.isAbstract) unit.error(tree.pos, "`abstract' modifier cannot be used with valium classes")
            val constParamGetters = tree.symbol.constrParamAccessors.map(field => (field, field.getterIn(field.owner)))
            constParamGetters collect { case (field, getter) if getter == NoSymbol || !getter.isPublic => unit.error(field.pos, "there can only be public fields in valium classes") }
            // TODO: this is to avoid dealing with types dependent on valium classes
            // those can be supported in valium-convert by just replacing p.T's to their upper bounds
            // (that's valid, because valium classes are final, and because typechecker has already checked that path-dependent types are ok)
            // however that would be tedious in the sense that we need to make these replacements everywhere - in trees, in symbol signatures, etc
            stats collect { case tdef: TypeDef => unit.error(tdef.pos, "type members can't be defined in valium classes") }
            stats collect { case vdef: ValDef if !vdef.symbol.isParamAccessor => unit.error(vdef.pos, "value members can't be defined in valium classes") }
            super.traverse(tree)
          case _: MemberDef if tree.symbol.isValiumClass =>
            unit.error(tree.pos, "only classes (not traits) are allowed to be @valium")
            super.traverse(tree)
          case _ =>
            // TODO: need to ban p.type for valium classes
            super.traverse(tree)
        }
      }
      VerifyTraverser.traverse(unit.body)
    }
  }
}