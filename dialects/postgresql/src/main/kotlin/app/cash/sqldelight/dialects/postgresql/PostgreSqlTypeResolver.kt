package app.cash.sqldelight.dialects.postgresql

import app.cash.sqldelight.dialect.api.DialectType
import app.cash.sqldelight.dialect.api.IntermediateType
import app.cash.sqldelight.dialect.api.PrimitiveType
import app.cash.sqldelight.dialect.api.PrimitiveType.BLOB
import app.cash.sqldelight.dialect.api.PrimitiveType.INTEGER
import app.cash.sqldelight.dialect.api.PrimitiveType.REAL
import app.cash.sqldelight.dialect.api.PrimitiveType.TEXT
import app.cash.sqldelight.dialect.api.QueryWithResults
import app.cash.sqldelight.dialect.api.ReturningQueryable
import app.cash.sqldelight.dialect.api.TypeResolver
import app.cash.sqldelight.dialect.api.encapsulatingType
import app.cash.sqldelight.dialects.postgresql.PostgreSqlType.BIG_INT
import app.cash.sqldelight.dialects.postgresql.PostgreSqlType.SMALL_INT
import app.cash.sqldelight.dialects.postgresql.PostgreSqlType.TIMESTAMP
import app.cash.sqldelight.dialects.postgresql.PostgreSqlType.TIMESTAMP_TIMEZONE
import app.cash.sqldelight.dialects.postgresql.grammar.mixins.WindowFunctionMixin
import app.cash.sqldelight.dialects.postgresql.grammar.psi.PostgreSqlDeleteStmtLimited
import app.cash.sqldelight.dialects.postgresql.grammar.psi.PostgreSqlExtensionExpr
import app.cash.sqldelight.dialects.postgresql.grammar.psi.PostgreSqlInsertStmt
import app.cash.sqldelight.dialects.postgresql.grammar.psi.PostgreSqlTypeName
import app.cash.sqldelight.dialects.postgresql.grammar.psi.PostgreSqlUpdateStmtLimited
import com.alecstrong.sql.psi.core.psi.SqlColumnExpr
import com.alecstrong.sql.psi.core.psi.SqlCreateTableStmt
import com.alecstrong.sql.psi.core.psi.SqlExpr
import com.alecstrong.sql.psi.core.psi.SqlFunctionExpr
import com.alecstrong.sql.psi.core.psi.SqlLiteralExpr
import com.alecstrong.sql.psi.core.psi.SqlStmt
import com.alecstrong.sql.psi.core.psi.SqlTypeName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asTypeName

class PostgreSqlTypeResolver(private val parentResolver: TypeResolver) : TypeResolver by parentResolver {
  override fun definitionType(typeName: SqlTypeName): IntermediateType = with(typeName) {
    check(this is PostgreSqlTypeName)
    val type = IntermediateType(
      when {
        smallIntDataType != null -> PostgreSqlType.SMALL_INT
        intDataType != null -> PostgreSqlType.INTEGER
        bigIntDataType != null -> PostgreSqlType.BIG_INT
        numericDataType != null -> PostgreSqlType.NUMERIC
        approximateNumericDataType != null -> REAL
        stringDataType != null -> TEXT
        uuidDataType != null -> PostgreSqlType.UUID
        smallSerialDataType != null -> PostgreSqlType.SMALL_INT
        serialDataType != null -> PostgreSqlType.INTEGER
        bigSerialDataType != null -> PostgreSqlType.BIG_INT
        dateDataType != null -> {
          when (dateDataType!!.firstChild.text) {
            "DATE" -> PostgreSqlType.DATE
            "TIME" -> PostgreSqlType.TIME
            "TIMESTAMP" -> if (dateDataType!!.node.getChildren(null).any { it.text == "WITH" }) TIMESTAMP_TIMEZONE else TIMESTAMP
            "TIMESTAMPTZ" -> TIMESTAMP_TIMEZONE
            "INTERVAL" -> PostgreSqlType.INTERVAL
            else -> throw IllegalArgumentException("Unknown date type ${dateDataType!!.text}")
          }
        }
        jsonDataType != null -> TEXT
        booleanDataType != null -> PrimitiveType.BOOLEAN
        blobDataType != null -> BLOB
        customDataType != null -> PostgreSqlType.OTHER
        else -> throw IllegalArgumentException("Unknown kotlin type for sql type ${this.text}")
      },
    )
    if (node.getChildren(null).map { it.text }.takeLast(2) == listOf("[", "]")) {
      return IntermediateType(
        object : DialectType {
          override val javaType = Array::class.asTypeName().parameterizedBy(type.javaType)

          override fun prepareStatementBinder(columnIndex: String, value: CodeBlock) =
            CodeBlock.of("bindObject($columnIndex, %L)\n", value)

          override fun cursorGetter(columnIndex: Int, cursorName: String) =
            CodeBlock.of("$cursorName.getArray<%T>($columnIndex)", type.javaType)
        },
      )
    }
    return type
  }

  override fun functionType(functionExpr: SqlFunctionExpr): IntermediateType? {
    return functionExpr.postgreSqlFunctionType() ?: parentResolver.functionType(functionExpr)
  }

  private fun SqlFunctionExpr.postgreSqlFunctionType() = when (functionName.text.lowercase()) {
    "greatest" -> encapsulatingType(exprList, INTEGER, REAL, TEXT, BLOB)
    "concat" -> encapsulatingType(exprList, TEXT)
    "substring" -> IntermediateType(TEXT).nullableIf(resolvedType(exprList[0]).javaType.isNullable)
    "coalesce", "ifnull" -> encapsulatingType(exprList, SMALL_INT, PostgreSqlType.INTEGER, INTEGER, BIG_INT, REAL, TEXT, BLOB)
    "max" -> encapsulatingType(exprList, SMALL_INT, PostgreSqlType.INTEGER, INTEGER, BIG_INT, REAL, TEXT, BLOB, TIMESTAMP_TIMEZONE, TIMESTAMP).asNullable()
    "min" -> encapsulatingType(exprList, BLOB, TEXT, SMALL_INT, INTEGER, PostgreSqlType.INTEGER, BIG_INT, REAL, TIMESTAMP_TIMEZONE, TIMESTAMP).asNullable()
    "date_trunc" -> encapsulatingType(exprList, TIMESTAMP_TIMEZONE, TIMESTAMP)
    "date_part" -> IntermediateType(REAL)
    "percentile_disc" -> IntermediateType(REAL).asNullable()
    "now" -> IntermediateType(TIMESTAMP_TIMEZONE)
    "corr", "covar_pop", "covar_samp", "regr_avgx", "regr_avgy", "regr_intercept",
    "regr_r2", "regr_slope", "regr_sxx", "regr_sxy", "regr_syy",
    -> IntermediateType(REAL).asNullable()
    "stddev", "stddev_pop", "stddev_samp", "variance",
    "var_pop", "var_samp",
    -> if (resolvedType(exprList[0]).dialectType == REAL) {
      IntermediateType(REAL).asNullable()
    } else IntermediateType(
      PostgreSqlType.NUMERIC,
    ).asNullable()
    "regr_count" -> IntermediateType(BIG_INT).asNullable()
    "gen_random_uuid" -> IntermediateType(PostgreSqlType.UUID)
    "length", "character_length", "char_length" -> IntermediateType(PostgreSqlType.INTEGER).nullableIf(resolvedType(exprList[0]).javaType.isNullable)
    else -> null
  }

  override fun queryWithResults(sqlStmt: SqlStmt): QueryWithResults? {
    sqlStmt.insertStmt?.let { insert ->
      check(insert is PostgreSqlInsertStmt)
      insert.returningClause?.let { return ReturningQueryable(insert, it, insert.tableName) }
    }
    sqlStmt.updateStmtLimited?.let { update ->
      check(update is PostgreSqlUpdateStmtLimited)
      update.returningClause?.let { return ReturningQueryable(update, it, update.qualifiedTableName.tableName) }
    }
    sqlStmt.deleteStmtLimited?.let { delete ->
      check(delete is PostgreSqlDeleteStmtLimited)
      delete.returningClause?.let { return ReturningQueryable(delete, it, delete.qualifiedTableName?.tableName) }
    }
    return parentResolver.queryWithResults(sqlStmt)
  }

  override fun simplifyType(intermediateType: IntermediateType): IntermediateType {
    // Primary key columns are non null always.
    val columnDef = intermediateType.column ?: return intermediateType
    val tableDef = columnDef.parent as? SqlCreateTableStmt ?: return intermediateType
    tableDef.tableConstraintList.forEach {
      if (columnDef.columnName.name in it.indexedColumnList.mapNotNull {
          val expr = it.expr
          if (expr is SqlColumnExpr) expr.columnName.name else null
        }
      ) {
        return intermediateType.asNonNullable()
      }
    }

    return parentResolver.simplifyType(intermediateType)
  }

  override fun resolvedType(expr: SqlExpr): IntermediateType {
    return expr.postgreSqlType()
  }

  private fun SqlExpr.postgreSqlType(): IntermediateType = when (this) {
    is SqlLiteralExpr -> when {
      literalValue.text == "CURRENT_DATE" -> IntermediateType(PostgreSqlType.DATE)
      literalValue.text == "CURRENT_TIME" -> IntermediateType(PostgreSqlType.TIME)
      literalValue.text == "CURRENT_TIMESTAMP" -> IntermediateType(PostgreSqlType.TIMESTAMP)
      literalValue.text.startsWith("INTERVAL") -> IntermediateType(PostgreSqlType.INTERVAL)
      else -> parentResolver.resolvedType(this)
    }
    is PostgreSqlExtensionExpr -> when {
      windowFunctionExpr != null -> {
        val windowFunctionExpr = windowFunctionExpr as WindowFunctionMixin
        functionType(windowFunctionExpr.functionExpr)!!
      }
      else -> parentResolver.resolvedType(this)
    }

    else -> parentResolver.resolvedType(this)
  }
}
