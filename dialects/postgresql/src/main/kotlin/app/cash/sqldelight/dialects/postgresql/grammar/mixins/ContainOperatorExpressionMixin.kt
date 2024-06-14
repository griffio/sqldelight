package app.cash.sqldelight.dialects.postgresql.grammar.mixins

import app.cash.sqldelight.dialects.postgresql.grammar.psi.PostgreSqlContainOperatorExpression
import com.alecstrong.sql.psi.core.SqlAnnotationHolder
import com.alecstrong.sql.psi.core.psi.SqlBinaryExpr
import com.alecstrong.sql.psi.core.psi.SqlColumnDef
import com.alecstrong.sql.psi.core.psi.SqlColumnName
import com.alecstrong.sql.psi.core.psi.SqlCompositeElementImpl
import com.alecstrong.sql.psi.core.psi.SqlExpr
import com.intellij.lang.ASTNode

/**
 * The "@> <@" contain operators is used by TsVector, Jsonb and TsRanges
 * The type annotation is performed here for both types
 * For other json operators see JsonExpressionMixin
 */
internal abstract class ContainOperatorExpressionMixin(node: ASTNode) :
  SqlCompositeElementImpl(node),
  SqlBinaryExpr,
  PostgreSqlContainOperatorExpression {

  override fun annotate(annotationHolder: SqlAnnotationHolder) {
    val columnType = ((firstChild.firstChild.reference?.resolve() as? SqlColumnName)?.parent as? SqlColumnDef)?.columnType?.typeName?.text
    when {
      columnType == null -> super.annotate(annotationHolder)
      columnType == "JSONB" -> super.annotate(annotationHolder)
      columnType == "JSON" -> annotationHolder.createErrorAnnotation(firstChild.firstChild, "Left side of jsonb expression must be a jsonb column.")
      columnType == "TSVECTOR" -> annotationHolder.createErrorAnnotation(firstChild.firstChild, "Left side of match expression must be a tsvector column.")
      columnType == "TSRANGE" -> annotationHolder.createErrorAnnotation(firstChild.firstChild, "Left side of match expression must be a tsrange column.")
      columnType != "TSTZRANGE" -> annotationHolder.createErrorAnnotation(firstChild.firstChild, "Left side of match expression must be a tstzrange column.")
    }
    super.annotate(annotationHolder)
  }
  override fun getExprList(): List<SqlExpr> {
    return children.filterIsInstance<SqlExpr>()
  }
}
