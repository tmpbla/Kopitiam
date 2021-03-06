/* (c) 2013 Hannes Mehnert */

package dk.itu.sdg.kopitiam.javap

import dk.itu.coqoon.ui.CreateErrorMarkerJob
import dk.itu.sdg.kopitiam._

object VisitingAST {
  import org.eclipse.jdt.core.IJavaElement
  import org.eclipse.jdt.core.dom.SimpleName
  def isField (y : SimpleName) : Boolean = {
    var res : Boolean = false
    val bind = y.resolveBinding
    if (bind != null) {
      val javaele = bind.getJavaElement
      if (javaele != null) {
        val typ = javaele.getElementType
        if (typ == IJavaElement.FIELD)
          res = true
      }
    }
    res
  }

  class ReportingVisitor(jes : JavaEditorState) extends Visitor {
    private var success = true

    def getSuccess = success

    import org.eclipse.jdt.core.dom.ASTNode
    def reportError(e : String, s : ASTNode) : Unit = {
      success = false
      jes.file.foreach(file => CreateErrorMarkerJob(file,
          (s.getStartPosition, s.getStartPosition + s.getLength), e).schedule)
    }
  }

  //beware of the boilerplate. nothing interesting to see below.
  import org.eclipse.jdt.core.dom.ASTVisitor

  class Visitor extends ASTVisitor {
    import org.eclipse.jdt.core.dom.{AbstractTypeDeclaration, AnnotationTypeDeclaration, AnnotationTypeMemberDeclaration, AnonymousClassDeclaration, ArrayAccess, ArrayCreation, ArrayInitializer, ArrayType, AssertStatement, Assignment, ASTNode, Block, BlockComment, BooleanLiteral, BreakStatement, CastExpression, CatchClause, CharacterLiteral, ClassInstanceCreation, CompilationUnit, ConditionalExpression, ConstructorInvocation, ContinueStatement, DoStatement, EmptyStatement, EnhancedForStatement, EnumConstantDeclaration, EnumDeclaration, ExpressionStatement, FieldAccess, FieldDeclaration, ForStatement, IfStatement, ImportDeclaration, InfixExpression, Initializer, InstanceofExpression, Javadoc, LabeledStatement, LineComment, MarkerAnnotation, MemberRef, MemberValuePair, MethodDeclaration, MethodInvocation, MethodRef, MethodRefParameter, Modifier, NormalAnnotation, NullLiteral, NumberLiteral, PackageDeclaration, ParameterizedType, ParenthesizedExpression, PostfixExpression, PrefixExpression, PrimitiveType, QualifiedName, QualifiedType, ReturnStatement, SimpleName, SimpleType, SingleMemberAnnotation, SingleVariableDeclaration, StringLiteral, SuperConstructorInvocation, SuperFieldAccess, SuperMethodInvocation, SwitchCase, SwitchStatement, SynchronizedStatement, TagElement, TextElement, ThisExpression, ThrowStatement, TryStatement, TypeDeclaration, TypeDeclarationStatement, TypeLiteral, TypeParameter, UnionType, VariableDeclarationExpression, VariableDeclarationFragment, VariableDeclarationStatement, WhileStatement, WildcardType}

    def visitNode (node : ASTNode) : Boolean = { true }
    def endVisitNode (node : ASTNode) : Unit = { }

    override def visit (node : AnnotationTypeDeclaration) : Boolean = { visitNode(node) }
    override def visit (node : AnnotationTypeMemberDeclaration) : Boolean = { visitNode(node) }
    override def visit (node : AnonymousClassDeclaration) : Boolean = { visitNode(node) }
    override def visit (node : ArrayAccess) : Boolean = { visitNode(node) }
    override def visit (node : ArrayCreation) : Boolean = { visitNode(node) }
    override def visit (node : ArrayInitializer) : Boolean = { visitNode(node) }
    override def visit (node : ArrayType) : Boolean = { visitNode(node) }
    override def visit (node : AssertStatement) : Boolean = { visitNode(node) }
    override def visit (node : Assignment) : Boolean = { visitNode(node) }
    override def visit (node : Block) : Boolean = { visitNode(node) }
    override def visit (node : BlockComment) : Boolean = { visitNode(node) }
    override def visit (node : BooleanLiteral) : Boolean = { visitNode(node) }
    override def visit (node : BreakStatement) : Boolean = { visitNode(node) }
    override def visit (node : CastExpression) : Boolean = { visitNode(node) }
    override def visit (node : CatchClause) : Boolean = { visitNode(node) }
    override def visit (node : CharacterLiteral) : Boolean = { visitNode(node) }
    override def visit (node : ClassInstanceCreation) : Boolean = { visitNode(node) }
    override def visit (node : CompilationUnit) : Boolean = { visitNode(node) }
    override def visit (node : ConditionalExpression) : Boolean = { visitNode(node) }
    override def visit (node : ConstructorInvocation) : Boolean = { visitNode(node) }
    override def visit (node : ContinueStatement) : Boolean = { visitNode(node) }
    override def visit (node : DoStatement) : Boolean = { visitNode(node) }
    override def visit (node : EmptyStatement) : Boolean = { visitNode(node) }
    override def visit (node : EnhancedForStatement) : Boolean = { visitNode(node) }
    override def visit (node : EnumConstantDeclaration) : Boolean = { visitNode(node) }
    override def visit (node : EnumDeclaration) : Boolean = { visitNode(node) }
    override def visit (node : ExpressionStatement) : Boolean = { visitNode(node) }
    override def visit (node : FieldAccess) : Boolean = { visitNode(node) }
    override def visit (node : FieldDeclaration) : Boolean = { visitNode(node) }
    override def visit (node : ForStatement) : Boolean = { visitNode(node) }
    override def visit (node : IfStatement) : Boolean = { visitNode(node) }
    override def visit (node : ImportDeclaration) : Boolean = { visitNode(node) }
    override def visit (node : InfixExpression) : Boolean = { visitNode(node) }
    override def visit (node : Initializer) : Boolean = { visitNode(node) }
    override def visit (node : InstanceofExpression) : Boolean = { visitNode(node) }
    override def visit (node : Javadoc) : Boolean = { visitNode(node) }
    override def visit (node : LabeledStatement) : Boolean = { visitNode(node) }
    override def visit (node : LineComment) : Boolean = { visitNode(node) }
    override def visit (node : MarkerAnnotation) : Boolean = { visitNode(node) }
    override def visit (node : MemberRef) : Boolean = { visitNode(node) }
    override def visit (node : MemberValuePair) : Boolean = { visitNode(node) }
    override def visit (node : MethodDeclaration) : Boolean = { visitNode(node) }
    override def visit (node : MethodInvocation) : Boolean = { visitNode(node) }
    override def visit (node : MethodRef) : Boolean = { visitNode(node) }
    override def visit (node : MethodRefParameter) : Boolean = { visitNode(node) }
    override def visit (node : Modifier) : Boolean = { visitNode(node) }
    override def visit (node : NormalAnnotation) : Boolean = { visitNode(node) }
    override def visit (node : NullLiteral) : Boolean = { visitNode(node) }
    override def visit (node : NumberLiteral) : Boolean = { visitNode(node) }
    override def visit (node : PackageDeclaration) : Boolean = { visitNode(node) }
    override def visit (node : ParameterizedType) : Boolean = { visitNode(node) }
    override def visit (node : ParenthesizedExpression) : Boolean = { visitNode(node) }
    override def visit (node : PostfixExpression) : Boolean = { visitNode(node) }
    override def visit (node : PrefixExpression) : Boolean = { visitNode(node) }
    override def visit (node : PrimitiveType) : Boolean = { visitNode(node) }
    override def visit (node : QualifiedName) : Boolean = { visitNode(node) }
    override def visit (node : QualifiedType) : Boolean = { visitNode(node) }
    override def visit (node : ReturnStatement) : Boolean = { visitNode(node) }
    override def visit (node : SimpleName) : Boolean = { visitNode(node) }
    override def visit (node : SimpleType) : Boolean = { visitNode(node) }
    override def visit (node : SingleMemberAnnotation) : Boolean = { visitNode(node) }
    override def visit (node : SingleVariableDeclaration) : Boolean = { visitNode(node) }
    override def visit (node : StringLiteral) : Boolean = { visitNode(node) }
    override def visit (node : SuperConstructorInvocation) : Boolean = { visitNode(node) }
    override def visit (node : SuperFieldAccess) : Boolean = { visitNode(node) }
    override def visit (node : SuperMethodInvocation) : Boolean = { visitNode(node) }
    override def visit (node : SwitchCase) : Boolean = { visitNode(node) }
    override def visit (node : SwitchStatement) : Boolean = { visitNode(node) }
    override def visit (node : SynchronizedStatement) : Boolean = { visitNode(node) }
    override def visit (node : TagElement) : Boolean = { visitNode(node) }
    override def visit (node : TextElement) : Boolean = { visitNode(node) }
    override def visit (node : ThisExpression) : Boolean = { visitNode(node) }
    override def visit (node : ThrowStatement) : Boolean = { visitNode(node) }
    override def visit (node : TryStatement) : Boolean = { visitNode(node) }
    override def visit (node : TypeDeclaration) : Boolean = { visitNode(node) }
    override def visit (node : TypeDeclarationStatement) : Boolean = { visitNode(node) }
    override def visit (node : TypeLiteral) : Boolean = { visitNode(node) }
    override def visit (node : TypeParameter) : Boolean = { visitNode(node) }
    override def visit (node : UnionType) : Boolean = { visitNode(node) }
    override def visit (node : VariableDeclarationExpression) : Boolean = { visitNode(node) }
    override def visit (node : VariableDeclarationFragment) : Boolean = { visitNode(node) }
    override def visit (node : VariableDeclarationStatement) : Boolean = { visitNode(node) }
    override def visit (node : WhileStatement) : Boolean = { visitNode(node) }
    override def visit (node : WildcardType) : Boolean = { visitNode(node) }


    override def endVisit (node : AnnotationTypeDeclaration) : Unit = { endVisitNode(node) }
    override def endVisit (node : AnnotationTypeMemberDeclaration) : Unit = { endVisitNode(node) }
    override def endVisit (node : AnonymousClassDeclaration) : Unit = { endVisitNode(node) }
    override def endVisit (node : ArrayAccess) : Unit = { endVisitNode(node) }
    override def endVisit (node : ArrayCreation) : Unit = { endVisitNode(node) }
    override def endVisit (node : ArrayInitializer) : Unit = { endVisitNode(node) }
    override def endVisit (node : ArrayType) : Unit = { endVisitNode(node) }
    override def endVisit (node : AssertStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : Assignment) : Unit = { endVisitNode(node) }
    override def endVisit (node : Block) : Unit = { endVisitNode(node) }
    override def endVisit (node : BlockComment) : Unit = { endVisitNode(node) }
    override def endVisit (node : BooleanLiteral) : Unit = { endVisitNode(node) }
    override def endVisit (node : BreakStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : CastExpression) : Unit = { endVisitNode(node) }
    override def endVisit (node : CatchClause) : Unit = { endVisitNode(node) }
    override def endVisit (node : CharacterLiteral) : Unit = { endVisitNode(node) }
    override def endVisit (node : ClassInstanceCreation) : Unit = { endVisitNode(node) }
    override def endVisit (node : CompilationUnit) : Unit = { endVisitNode(node) }
    override def endVisit (node : ConditionalExpression) : Unit = { endVisitNode(node) }
    override def endVisit (node : ConstructorInvocation) : Unit = { endVisitNode(node) }
    override def endVisit (node : ContinueStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : DoStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : EmptyStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : EnhancedForStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : EnumConstantDeclaration) : Unit = { endVisitNode(node) }
    override def endVisit (node : EnumDeclaration) : Unit = { endVisitNode(node) }
    override def endVisit (node : ExpressionStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : FieldAccess) : Unit = { endVisitNode(node) }
    override def endVisit (node : FieldDeclaration) : Unit = { endVisitNode(node) }
    override def endVisit (node : ForStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : IfStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : ImportDeclaration) : Unit = { endVisitNode(node) }
    override def endVisit (node : InfixExpression) : Unit = { endVisitNode(node) }
    override def endVisit (node : Initializer) : Unit = { endVisitNode(node) }
    override def endVisit (node : InstanceofExpression) : Unit = { endVisitNode(node) }
    override def endVisit (node : Javadoc) : Unit = { endVisitNode(node) }
    override def endVisit (node : LabeledStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : LineComment) : Unit = { endVisitNode(node) }
    override def endVisit (node : MarkerAnnotation) : Unit = { endVisitNode(node) }
    override def endVisit (node : MemberRef) : Unit = { endVisitNode(node) }
    override def endVisit (node : MemberValuePair) : Unit = { endVisitNode(node) }
    override def endVisit (node : MethodDeclaration) : Unit = { endVisitNode(node) }
    override def endVisit (node : MethodInvocation) : Unit = { endVisitNode(node) }
    override def endVisit (node : MethodRef) : Unit = { endVisitNode(node) }
    override def endVisit (node : MethodRefParameter) : Unit = { endVisitNode(node) }
    override def endVisit (node : Modifier) : Unit = { endVisitNode(node) }
    override def endVisit (node : NormalAnnotation) : Unit = { endVisitNode(node) }
    override def endVisit (node : NullLiteral) : Unit = { endVisitNode(node) }
    override def endVisit (node : NumberLiteral) : Unit = { endVisitNode(node) }
    override def endVisit (node : PackageDeclaration) : Unit = { endVisitNode(node) }
    override def endVisit (node : ParameterizedType) : Unit = { endVisitNode(node) }
    override def endVisit (node : ParenthesizedExpression) : Unit = { endVisitNode(node) }
    override def endVisit (node : PostfixExpression) : Unit = { endVisitNode(node) }
    override def endVisit (node : PrefixExpression) : Unit = { endVisitNode(node) }
    override def endVisit (node : PrimitiveType) : Unit = { endVisitNode(node) }
    override def endVisit (node : QualifiedName) : Unit = { endVisitNode(node) }
    override def endVisit (node : QualifiedType) : Unit = { endVisitNode(node) }
    override def endVisit (node : ReturnStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : SimpleName) : Unit = { endVisitNode(node) }
    override def endVisit (node : SimpleType) : Unit = { endVisitNode(node) }
    override def endVisit (node : SingleMemberAnnotation) : Unit = { endVisitNode(node) }
    override def endVisit (node : SingleVariableDeclaration) : Unit = { endVisitNode(node) }
    override def endVisit (node : StringLiteral) : Unit = { endVisitNode(node) }
    override def endVisit (node : SuperConstructorInvocation) : Unit = { endVisitNode(node) }
    override def endVisit (node : SuperFieldAccess) : Unit = { endVisitNode(node) }
    override def endVisit (node : SuperMethodInvocation) : Unit = { endVisitNode(node) }
    override def endVisit (node : SwitchCase) : Unit = { endVisitNode(node) }
    override def endVisit (node : SwitchStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : SynchronizedStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : TagElement) : Unit = { endVisitNode(node) }
    override def endVisit (node : TextElement) : Unit = { endVisitNode(node) }
    override def endVisit (node : ThisExpression) : Unit = { endVisitNode(node) }
    override def endVisit (node : ThrowStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : TryStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : TypeDeclaration) : Unit = { endVisitNode(node) }
    override def endVisit (node : TypeDeclarationStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : TypeLiteral) : Unit = { endVisitNode(node) }
    override def endVisit (node : TypeParameter) : Unit = { endVisitNode(node) }
    override def endVisit (node : UnionType) : Unit = { endVisitNode(node) }
    override def endVisit (node : VariableDeclarationExpression) : Unit = { endVisitNode(node) }
    override def endVisit (node : VariableDeclarationFragment) : Unit = { endVisitNode(node) }
    override def endVisit (node : VariableDeclarationStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : WhileStatement) : Unit = { endVisitNode(node) }
    override def endVisit (node : WildcardType) : Unit = { endVisitNode(node) }
  }
}
