package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldEvolution
import kotlinx.coroutines.flow.Flow

internal data class ActivityTemplateAggregateEntity(
    val template: ActivityTemplateEntity,
    val settings: ActivityTemplateSettingsEntity,
    val fields: List<ActivityTemplateFieldEntity> = emptyList(),
    val options: List<ActivityTemplateCategoryOptionEntity> = emptyList(),
    val tags: List<ActivityTemplateTagEntity> = emptyList(),
    val userState: ActivityTemplateUserStateEntity? = null,
)

internal data class ActivityTemplateSemanticUpdate(
    val template: ActivityTemplateEntity,
    val settings: ActivityTemplateSettingsEntity,
    val fields: List<ActivityTemplateFieldEntity> = emptyList(),
    val options: List<ActivityTemplateCategoryOptionEntity> = emptyList(),
    val expectedRevision: Long = template.revision - 1,
)

@Dao
@Suppress("TooManyFunctions") // A single feature DAO keeps aggregate transaction boundaries explicit.
internal abstract class ActivityTemplateDao {
    @Query("SELECT * FROM activity_templates WHERE id = :id")
    abstract fun getById(id: String): ActivityTemplateEntity?

    @Query("SELECT * FROM activity_templates WHERE deleted_at_ms IS NULL ORDER BY name, id")
    abstract fun observeActive(): Flow<List<ActivityTemplateEntity>>

    @Query("SELECT * FROM activity_templates WHERE deleted_at_ms IS NOT NULL ORDER BY name, id")
    abstract fun observeArchived(): Flow<List<ActivityTemplateEntity>>

    @Query("SELECT * FROM activity_template_settings WHERE activity_template_id = :templateId")
    abstract fun getSettings(templateId: String): ActivityTemplateSettingsEntity?

    @Query("SELECT * FROM activity_template_user_state WHERE activity_template_id = :templateId")
    abstract fun getUserState(templateId: String): ActivityTemplateUserStateEntity?

    @Query(
        "SELECT * FROM activity_template_fields " +
            "WHERE activity_template_id = :templateId AND deleted_at_ms IS NULL ORDER BY position, id",
    )
    abstract fun getActiveFields(templateId: String): List<ActivityTemplateFieldEntity>

    @Query("SELECT * FROM activity_template_fields WHERE activity_template_id = :templateId ORDER BY position, id")
    abstract fun getAllFields(templateId: String): List<ActivityTemplateFieldEntity>

    @Query(
        "SELECT * FROM activity_template_category_options " +
            "WHERE activity_template_field_id = :fieldId ORDER BY position, id",
    )
    abstract fun getCategoryOptions(fieldId: String): List<ActivityTemplateCategoryOptionEntity>

    @Query(
        "SELECT * FROM activity_template_category_options " +
            "WHERE activity_template_field_id IN (:fieldIds) " +
            "ORDER BY activity_template_field_id, position, id",
    )
    abstract fun getCategoryOptions(fieldIds: List<String>): List<ActivityTemplateCategoryOptionEntity>

    @Query("SELECT tag_id FROM activity_template_tags WHERE activity_template_id = :templateId ORDER BY tag_id")
    abstract fun getTagIds(templateId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertTemplate(template: ActivityTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertSettings(settings: ActivityTemplateSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertUserState(userState: ActivityTemplateUserStateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertFields(fields: List<ActivityTemplateFieldEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertOptions(options: List<ActivityTemplateCategoryOptionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertTagLinks(tags: List<ActivityTemplateTagEntity>)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract fun updateTemplate(template: ActivityTemplateEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract fun updateSettings(settings: ActivityTemplateSettingsEntity): Int

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract fun updateUserState(userState: ActivityTemplateUserStateEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract fun updateFields(fields: List<ActivityTemplateFieldEntity>): Int

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract fun updateOptions(options: List<ActivityTemplateCategoryOptionEntity>): Int

    @Query(
        "UPDATE activity_templates SET name = :name, short_comment = :shortComment, " +
            "time_tracking_mode = :timeTrackingMode, timer_target_ms = :timerTargetMs, " +
            "revision = :newRevision, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND revision = :expectedRevision AND deleted_at_ms IS NULL",
    )
    @Suppress("LongParameterList")
    protected abstract fun updateSemanticTemplateUnchecked(
        id: String,
        expectedRevision: Long,
        newRevision: Long,
        name: String,
        shortComment: String?,
        timeTrackingMode: String,
        timerTargetMs: Long?,
        updatedAtMs: Long,
    ): Int

    @Query("UPDATE activity_template_fields SET name = :name, updated_at_ms = :updatedAtMs WHERE id = :id")
    abstract fun updateFieldDisplayName(
        id: String,
        name: String,
        updatedAtMs: Long,
    ): Int

    @Query("UPDATE activity_template_category_options SET label = :label WHERE id = :id")
    abstract fun updateOptionDisplayLabel(
        id: String,
        label: String,
    ): Int

    @Query("UPDATE activity_templates SET deleted_at_ms = :deletedAtMs WHERE id = :id")
    abstract fun archive(
        id: String,
        deletedAtMs: Long,
    ): Int

    @Query("UPDATE activity_templates SET deleted_at_ms = NULL WHERE id = :id")
    abstract fun restore(id: String): Int

    @Query(
        "UPDATE activity_templates SET statistics_series_id = :newSeriesId, revision = revision + 1, " +
            "updated_at_ms = :updatedAtMs WHERE id = :id AND statistics_series_id = :oldSeriesId " +
            "AND revision = :expectedRevision AND deleted_at_ms IS NULL",
    )
    abstract fun startNewStatisticsSeries(
        id: String,
        oldSeriesId: String,
        expectedRevision: Long,
        newSeriesId: String,
        updatedAtMs: Long,
    ): Int

    @Query("UPDATE statistics_series SET display_name = :displayName WHERE id = :seriesId")
    protected abstract fun updateSeriesDisplayName(
        seriesId: String,
        displayName: String,
    ): Int

    @Query("UPDATE activity_template_fields SET deleted_at_ms = :deletedAtMs WHERE id = :id")
    abstract fun archiveField(
        id: String,
        deletedAtMs: Long,
    ): Int

    @Query("UPDATE activity_template_category_options SET is_archived = 1 WHERE id = :id")
    abstract fun archiveOption(id: String): Int

    @Query("DELETE FROM activity_template_tags WHERE activity_template_id = :templateId AND tag_id = :tagId")
    abstract fun deleteTagLink(
        templateId: String,
        tagId: String,
    ): Int

    @Transaction
    open fun getAggregate(id: String): ActivityTemplateAggregateEntity? {
        val template = getById(id) ?: return null
        val fields = getAllFields(id)
        return ActivityTemplateAggregateEntity(
            template,
            checkNotNull(getSettings(id)) { "ActivityTemplate $id is missing settings" },
            fields,
            if (fields.isEmpty()) emptyList() else getCategoryOptions(fields.map { it.id }),
            getTagIds(id).map { ActivityTemplateTagEntity(id, it) },
            getUserState(id),
        ).also { it.toDomain() }
    }

    @Transaction
    open fun insertAggregate(aggregate: ActivityTemplateAggregateEntity) {
        insertTemplate(aggregate.template)
        insertSettings(aggregate.settings)
        if (aggregate.fields.isNotEmpty()) insertFields(aggregate.fields)
        if (aggregate.options.isNotEmpty()) insertOptions(aggregate.options)
        if (aggregate.tags.isNotEmpty()) insertTagLinks(aggregate.tags)
        aggregate.userState?.let(::insertUserState)
    }

    @Transaction
    open fun updateSemanticAggregate(update: ActivityTemplateSemanticUpdate) {
        val current =
            requireNotNull(getAggregate(update.template.id)) { "Unknown ActivityTemplate: ${update.template.id}" }
        require(current.template.deletedAtMs == null) { "Archived ActivityTemplate cannot be edited" }
        require(current.template.revision == update.expectedRevision) {
            "ActivityTemplate revision changed concurrently"
        }
        require(update.template.revision == update.expectedRevision + 1) {
            "A semantic aggregate update must increment revision exactly once"
        }
        require(update.template.statisticsSeriesId == current.template.statisticsSeriesId) {
            "Semantic commit cannot change Statistics Series; use Start new statistics"
        }
        require(
            update.template.id == current.template.id &&
                update.template.createdAtMs == current.template.createdAtMs &&
                update.template.deletedAtMs == current.template.deletedAtMs &&
                update.template.folderId == current.template.folderId,
        ) { "Semantic commit cannot change Activity identity, ownership, lifecycle, or Library metadata" }
        require(current.fields.map { it.id }.all(update.fields.map { it.id }.toSet()::contains)) {
            "Existing Field identities must be retained and archived instead of removed"
        }
        require(current.options.map { it.id }.all(update.options.map { it.id }.toSet()::contains)) {
            "Existing Category option identities must be retained and archived instead of removed"
        }
        requireCompatibleEvolution(current, update)
        check(
            updateSemanticTemplateUnchecked(
                update.template.id,
                update.expectedRevision,
                update.template.revision,
                update.template.name,
                update.template.shortComment,
                update.template.timeTrackingMode,
                update.template.timerTargetMs,
                update.template.updatedAtMs,
            ) == 1,
        ) { "ActivityTemplate revision changed concurrently" }
        if (update.template.name != current.template.name) {
            check(updateSeriesDisplayName(current.template.statisticsSeriesId, update.template.name) == 1)
        }
        check(updateSettings(update.settings) == 1)
        val existingFields = current.fields.mapTo(hashSetOf()) { it.id }
        val existingOptions = current.options.mapTo(hashSetOf()) { it.id }
        update.fields.partition { it.id in existingFields }.let { (old, new) ->
            if (old.isNotEmpty()) check(updateFields(old) == old.size)
            if (new.isNotEmpty()) insertFields(new)
        }
        update.options.partition { it.id in existingOptions }.let { (old, new) ->
            if (old.isNotEmpty()) check(updateOptions(old) == old.size)
            if (new.isNotEmpty()) insertOptions(new)
        }
    }

    private fun requireCompatibleEvolution(
        current: ActivityTemplateAggregateEntity,
        update: ActivityTemplateSemanticUpdate,
    ) {
        val previous = current.toDomain().fields.associateBy { it.id }
        update
            .copy(template = update.template, settings = update.settings)
            .let {
                ActivityTemplateAggregateEntity(
                    it.template,
                    it.settings,
                    it.fields,
                    it.options,
                ).toDomain()
            }.fields
            .forEach { field ->
                previous[field.id]?.let {
                    ActivityTemplateFieldEvolution.requireSameIdentityCompatible(it, field)
                }
            }
        val owners = current.options.associate { it.id to it.activityTemplateFieldId }
        update.options.forEach { option ->
            owners[option.id]?.let { owner ->
                require(owner == option.activityTemplateFieldId) {
                    "Category option owner Field is immutable for the same option identity"
                }
            }
        }
    }
}
