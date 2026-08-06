package app.cash.sqldelight.driver.r2dbc

import app.cash.sqldelight.Query
import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext

/**
 * A [SqlDriver] which acquires [Connection]s from a [ConnectionFactory]. For example a
 * `r2dbc-pool` connection pool , instead of wrapping a single [Connection] like [R2dbcDriver].
 *
 * Transactions are scoped to the coroutine context rather than to a thread:
 * - Each outermost transaction acquires a connection from the factory, runs `BEGIN`, all
 *   statements in the transaction body, and `COMMIT`/`ROLLBACK` on that connection, then releases
 *   it , even if the suspending transaction body resumes on different threads along the way.
 * - Statements executed outside a transaction acquire a connection for the duration of that
 *   single statement.
 * - Multiple transactions can be in flight concurrently, each on its own connection.
 *
 * Statements are associated with their transaction through the coroutine context, so they must be
 * executed from (a child of) the coroutine that started the transaction. Launching work in an
 * unrelated scope from within a transaction body will execute outside the transaction.
 *
 * Closing this driver does not close the [ConnectionFactory]; the factory's lifecycle (for
 * example `ConnectionPool.dispose()`) remains the caller's responsibility.
 */
class R2dbcPooledDriver(
  private val connectionFactory: ConnectionFactory,
) : SqlDriver,
  SuspendingTransacter.TransactionDispatcher {
  private val currentSession = ThreadLocal<TransactionSession?>()

  override fun <R> executeQuery(
    identifier: Int?,
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<R> = QueryResult.AsyncValue {
    withConnection { connection ->
      val prepared = connection.createStatement(sql).also { statement ->
        R2dbcPreparedStatement(statement).apply { if (binders != null) this.binders() }
      }
      prepared.executeQueryResult(mapper)
    }
  }

  override fun execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<Long> = QueryResult.AsyncValue {
    withConnection { connection ->
      val prepared = connection.createStatement(sql).also { statement ->
        R2dbcPreparedStatement(statement).apply { if (binders != null) this.binders() }
      }
      prepared.executeUpdateResult()
    }
  }

  /**
   * Runs [transaction] with a connection-carrying session installed in the coroutine context.
   * Called by [SuspendingTransacter] around every call to `transaction` and
   * `transactionWithResult`.
   */
  override suspend fun <R> dispatch(transaction: suspend () -> R): R {
    // A nested transaction call: reuse the enclosing transaction's session and connection.
    if (currentCoroutineContext()[SessionElement] != null) {
      return transaction()
    }

    val connection = acquireConnection()
    try {
      currentCoroutineContext().ensureActive()
      return withContext(SessionElement(TransactionSession(connection), currentSession)) {
        transaction()
      }
    } finally {
      withContext(NonCancellable) {
        connection.close().awaitFirstOrNull()
      }
    }
  }

  override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue {
    val session = checkNotNull(currentCoroutineContext()[SessionElement]?.session) {
      "R2dbcPooledDriver transactions must be started through " +
        "SuspendingTransacter.transaction or SuspendingTransacter.transactionWithResult."
    }
    val enclosing = session.transaction

    if (enclosing == null) {
      session.connection.beginTransaction().awaitFirstOrNull()
    }

    val transaction = Transaction(enclosing, session)
    session.transaction = transaction
    return@AsyncValue transaction
  }

  override fun currentTransaction(): Transacter.Transaction? = currentSession.get()?.transaction

  override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit
  override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit
  override fun notifyListeners(vararg queryKeys: String) = Unit

  override fun close() = Unit

  private suspend fun <R> withConnection(block: suspend (Connection) -> R): R {
    val session = currentCoroutineContext()[SessionElement]?.session
    if (session != null) return block(session.connection)

    val connection = acquireConnection()
    return try {
      currentCoroutineContext().ensureActive()
      block(connection)
    } finally {
      withContext(NonCancellable) {
        connection.close().awaitFirstOrNull()
      }
    }
  }

  /**
   * Takes a connection from the factory, and guarantees the caller ends up owning it.
   *
   * The handover is deliberately not cancellable. A [ConnectionFactory] backed by a pool parks
   * callers when every connection is busy, and cancelling a parked caller leaves a window in which
   * the pool has already handed a connection over but the awaiting coroutine never resumes to
   * receive it. Nothing would then hold a reference to close it, so it would be lost to the pool
   * permanently. Completing the acquisition means the `try`/`finally` around every call site always
   * owns the connection and always gives it back.
   *
   * The cost is that a caller cancelled while parked still waits for its turn before releasing the
   * connection it no longer wants. Pools bound that wait with an acquire timeout, see
   * `ConnectionPoolConfiguration.Builder.maxAcquireTime`, and callers observe the cancellation
   * immediately afterwards, at the [ensureActive] check that follows every acquisition.
   */
  private suspend fun acquireConnection(): Connection = withContext(NonCancellable) {
    connectionFactory.create().awaitSingle()
  }

  private class TransactionSession(val connection: Connection) {
    var transaction: Transaction? = null
  }

  /**
   * Carries the transaction session in the coroutine context, and mirrors it into a [ThreadLocal]
   * on whichever thread is currently executing the transaction so [currentTransaction] can be
   * answered synchronously.
   */
  private class SessionElement(
    val session: TransactionSession,
    private val threadLocal: ThreadLocal<TransactionSession?>,
  ) : ThreadContextElement<TransactionSession?> {
    companion object Key : CoroutineContext.Key<SessionElement>

    override val key: CoroutineContext.Key<SessionElement> get() = Key

    override fun updateThreadContext(context: CoroutineContext): TransactionSession? {
      val previous = threadLocal.get()
      threadLocal.set(session)
      return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: TransactionSession?) {
      threadLocal.set(oldState)
    }
  }

  private class Transaction(
    override val enclosingTransaction: Transaction?,
    private val session: TransactionSession,
  ) : Transacter.Transaction() {
    // The suspending transaction body may resume on a different thread; the session travels with
    // the coroutine context instead.
    override val isThreadConfined: Boolean get() = false

    override fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.AsyncValue {
      withContext(NonCancellable) {
        if (enclosingTransaction == null) {
          if (successful) {
            session.connection.commitTransaction().awaitFirstOrNull()
          } else {
            session.connection.rollbackTransaction().awaitFirstOrNull()
          }
        }
        session.transaction = enclosingTransaction
      }
    }
  }
}
