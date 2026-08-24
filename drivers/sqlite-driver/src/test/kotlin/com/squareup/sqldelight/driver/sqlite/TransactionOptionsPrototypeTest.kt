package com.squareup.sqldelight.driver.sqlite

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.TransactionIsolationLevel
import app.cash.sqldelight.db.TransactionOptions
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransactionOptionsPrototypeTest {
  private val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
  private val isolationLevelsSeenInsideTransaction = mutableListOf<Int>()

  private val driver = object : JdbcDriver() {
    override fun getConnection() = connection
    override fun closeConnection(connection: Connection) = Unit
    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit
    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit
    override fun notifyListeners(vararg queryKeys: String) = Unit
  }

  private val transacter = object : TransacterImpl(driver) {}

  @Test fun `isolation level is applied for the transaction and restored afterwards`() {
    val before = connection.transactionIsolation

    transacter.transaction(TransactionOptions(TransactionIsolationLevel.READ_UNCOMMITTED)) {
      isolationLevelsSeenInsideTransaction += connection.transactionIsolation
    }

    assertEquals(listOf(Connection.TRANSACTION_READ_UNCOMMITTED), isolationLevelsSeenInsideTransaction)
    assertEquals(before, connection.transactionIsolation)
  }

  @Test fun `plain transactions are untouched`() {
    val before = connection.transactionIsolation
    transacter.transaction {
      isolationLevelsSeenInsideTransaction += connection.transactionIsolation
    }
    assertEquals(listOf(before), isolationLevelsSeenInsideTransaction)
    assertEquals(before, connection.transactionIsolation)
  }

  @Test fun `isolation level is restored after a rollback too`() {
    val before = connection.transactionIsolation
    transacter.transaction(TransactionOptions(TransactionIsolationLevel.READ_UNCOMMITTED)) {
      rollback()
    }
    assertEquals(before, connection.transactionIsolation)
  }

  @Test fun `options on a nested transaction fail`() {
    val failure = assertFailsWith<IllegalStateException> {
      transacter.transaction {
        transacter.transaction(TransactionOptions(TransactionIsolationLevel.SERIALIZABLE)) { }
      }
    }
    assertTrue(failure.message!!.contains("outermost transaction"))
  }

  @Test fun `drivers that cannot honour options throw`() {
    val naiveDriver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite::memory:")
    val naiveTransacter = object : TransacterImpl(naiveDriver) {}
    assertFailsWith<UnsupportedOperationException> {
      naiveTransacter.transaction(TransactionOptions(TransactionIsolationLevel.SERIALIZABLE)) { }
    }
    naiveDriver.close()
  }
}

/** Existing extension point: a subclass that overrides only [beginTransaction] must keep working. */
class LegacyBeginTransactionOverrideTest {
  private val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
  private var overrideCalled = 0

  private val driver = object : JdbcDriver() {
    override fun getConnection() = connection
    override fun closeConnection(connection: Connection) = Unit
    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit
    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit
    override fun notifyListeners(vararg queryKeys: String) = Unit
    override fun Connection.beginTransaction() {
      overrideCalled++
      transactionIsolation = Connection.TRANSACTION_READ_UNCOMMITTED
      autoCommit = false
    }
  }

  private val transacter = object : TransacterImpl(driver) {}

  @Test fun `subclass override still drives plain transactions`() {
    var seen = 0
    transacter.transaction { seen = connection.transactionIsolation }
    assertEquals(1, overrideCalled)
    assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED, seen)
  }
}
