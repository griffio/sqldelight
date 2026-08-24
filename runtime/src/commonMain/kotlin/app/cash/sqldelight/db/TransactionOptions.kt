package app.cash.sqldelight.db

import app.cash.sqldelight.Transacter

/**
 * The standard SQL transaction isolation levels, as defined by ANSI SQL-92.
 *
 * Not every dialect supports every level, and several dialects silently promote a requested level
 * to a stronger one (for example PostgreSQL runs [READ_UNCOMMITTED] as [READ_COMMITTED]). Drivers
 * which cannot honour a level at all are expected to throw rather than silently downgrade it.
 */
enum class TransactionIsolationLevel {
  READ_UNCOMMITTED,
  READ_COMMITTED,
  REPEATABLE_READ,
  SERIALIZABLE,
}

/**
 * Per-transaction settings which are applied when a transaction is opened via
 * [SqlDriver.newTransaction] and reverted once it commits or rolls back.
 *
 * Options may only be supplied for the outermost transaction - SQL isolation is a property of the
 * whole transaction, so a nested [Transacter.transaction] cannot change it.
 */
data class TransactionOptions(
  val isolationLevel: TransactionIsolationLevel? = null,
  val readOnly: Boolean = false,
) {
  companion object {
    /** Whatever the underlying connection is already configured to do. */
    val Default = TransactionOptions()
  }
}
