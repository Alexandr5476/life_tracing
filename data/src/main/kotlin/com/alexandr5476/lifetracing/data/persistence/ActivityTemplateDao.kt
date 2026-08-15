package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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
    abstract fun updateSettings(settings: ActivityTemplateSettingsEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract fun updateUserState(userState: ActivityTemplateUserStateEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract fun updateFields(fields: List<ActivityTemplateFieldEntity>)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract fun updateOptions(options: List<ActivityTemplateCategoryOptionEntity>)

    @Query("UPDATE activity_templates SET deleted_at_ms = :deletedAtMs WHERE id = :id")
    abstract fun archive(
        id: String,
        deletedAtMs: Long,
    ): Int

    @Query("UPDATE activity_templates SET deleted_at_ms = NULL WHERE id = :id")
    abstract fun restore(id: String): Int

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
        updateTemplate(update.template)
        updateSettings(update.settings)
        if (update.fields.isNotEmpty()) updateFields(update.fields)
        if (update.options.isNotEmpty()) updateOptions(update.options)
    }
}
