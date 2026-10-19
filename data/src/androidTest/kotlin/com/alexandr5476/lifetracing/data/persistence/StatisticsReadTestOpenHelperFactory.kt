package com.alexandr5476.lifetracing.data.persistence

import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** Synchronous test instrumentation; all operations still use the real framework database. */
internal class StatisticsReadTestOpenHelperFactory(
    private val beforeQuery: (SupportSQLiteDatabase, String) -> Unit = { _, _ -> },
    private val beforeTransaction: () -> Unit = {},
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase: SupportSQLiteDatabase
                get() = ObservedDatabase(helper.writableDatabase)

            override val readableDatabase: SupportSQLiteDatabase
                get() = ObservedDatabase(helper.readableDatabase)
        }
    }

    private inner class ObservedDatabase(
        private val actual: SupportSQLiteDatabase,
    ) : SupportSQLiteDatabase by actual {
        override fun query(query: String): Cursor {
            beforeQuery(actual, query)
            return actual.query(query)
        }

        override fun query(
            query: String,
            bindArgs: Array<out Any?>,
        ): Cursor {
            beforeQuery(actual, query)
            return actual.query(query, bindArgs)
        }

        override fun query(query: SupportSQLiteQuery): Cursor {
            beforeQuery(actual, query.sql)
            return actual.query(query)
        }

        override fun query(
            query: SupportSQLiteQuery,
            cancellationSignal: CancellationSignal?,
        ): Cursor {
            beforeQuery(actual, query.sql)
            return actual.query(query, cancellationSignal)
        }

        override fun beginTransaction() {
            beforeTransaction()
            actual.beginTransaction()
        }

        override fun beginTransactionNonExclusive() {
            beforeTransaction()
            actual.beginTransactionNonExclusive()
        }

        override fun beginTransactionReadOnly() {
            beforeTransaction()
            actual.beginTransactionReadOnly()
        }

        override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) {
            beforeTransaction()
            actual.beginTransactionWithListener(transactionListener)
        }

        override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) {
            beforeTransaction()
            actual.beginTransactionWithListenerNonExclusive(transactionListener)
        }

        override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) {
            beforeTransaction()
            actual.beginTransactionWithListenerReadOnly(transactionListener)
        }
    }
}
