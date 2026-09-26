package com.alexandr5476.lifetracing.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val HISTORICAL_PLAN_FULFILLMENT_SCHEMA_VERSION = 11

internal val MIGRATION_10_11 =
    object : Migration(HISTORY_DISCOVERY_INDEX_SCHEMA_VERSION, HISTORICAL_PLAN_FULFILLMENT_SCHEMA_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PlanEntrySchemaV9.allowHistoricalFulfillmentBeforePlanCreation(db)
        }
    }
