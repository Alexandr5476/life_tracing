package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alexandr5476.lifetracing.domain.ActiveSession

@Dao
internal abstract class ActiveSessionDao {
    @Query("SELECT * FROM active_session WHERE singleton_id = 1")
    protected abstract fun getSingletonRow(): ActiveSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertSingletonRow(session: ActiveSessionEntity)

    @Query(
        "UPDATE active_session SET state = :state, updated_at_ms = :updatedAtMs " +
            "WHERE singleton_id = 1",
    )
    abstract fun updateState(
        state: String,
        updatedAtMs: Long,
    ): Int

    @Query("DELETE FROM active_session WHERE singleton_id = 1")
    abstract fun clear(): Int

    fun get(): ActiveSession? = getSingletonRow()?.toDomain()

    fun insert(session: ActiveSession) {
        insertSingletonRow(session.toEntity())
    }
}
