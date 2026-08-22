package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

internal data class LibrarySummaryRow(
    val id: String,
    val name: String,
    @ColumnInfo(name = "short_comment") val shortComment: String?,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    @ColumnInfo(name = "pinned_rank") val pinnedRank: Int?,
    @ColumnInfo(name = "last_used_at_ms") val lastUsedAtMs: Long?,
    @ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long?,
)

internal data class LibraryTagLinkRow(
    @ColumnInfo(name = "template_id") val templateId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
)

@Dao
@Suppress("TooManyFunctions")
internal interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(folder: FolderEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    fun update(folder: FolderEntity)

    @Query("SELECT * FROM folders WHERE id = :id")
    fun getById(id: String): FolderEntity?

    @Query("SELECT * FROM folders")
    fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE parent_folder_id IS NULL ORDER BY name COLLATE NOCASE, id")
    fun getRoots(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE parent_folder_id = :parentId ORDER BY name COLLATE NOCASE, id")
    fun getChildren(parentId: String): List<FolderEntity>

    @Query("UPDATE folders SET name = :name, updated_at_ms = :updatedAtMs WHERE id = :id")
    fun rename(
        id: String,
        name: String,
        updatedAtMs: Long,
    ): Int

    @Query("UPDATE folders SET parent_folder_id = :parentId, updated_at_ms = :updatedAtMs WHERE id = :id")
    fun move(
        id: String,
        parentId: String?,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE folders SET parent_folder_id = :destinationId, updated_at_ms = :updatedAtMs " +
            "WHERE parent_folder_id = :sourceId",
    )
    fun moveChildren(
        sourceId: String,
        destinationId: String?,
        updatedAtMs: Long,
    ): Int

    @Query(
        "WITH RECURSIVE subtree(id) AS (" +
            "SELECT id FROM folders WHERE id = :id UNION " +
            "SELECT folders.id FROM folders INNER JOIN subtree ON folders.parent_folder_id = subtree.id" +
            ") SELECT folders.* FROM folders INNER JOIN subtree ON folders.id = subtree.id",
    )
    fun getSubtree(id: String): List<FolderEntity>

    @Query("DELETE FROM folders WHERE id = :id")
    fun deleteById(id: String): Int
}

@Dao
internal interface TagDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(tag: TagEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    fun update(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE id = :id")
    fun getById(id: String): TagEntity?

    @Query("SELECT * FROM tags")
    fun getAll(): List<TagEntity>

    @Query("UPDATE tags SET name = :name, updated_at_ms = :updatedAtMs WHERE id = :id")
    fun rename(
        id: String,
        name: String,
        updatedAtMs: Long,
    ): Int

    @Query("DELETE FROM tags WHERE id = :id")
    fun deleteById(id: String): Int
}

@Dao
internal interface StatisticsSeriesDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(series: StatisticsSeriesEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    fun update(series: StatisticsSeriesEntity)

    @Query("SELECT * FROM statistics_series WHERE id = :id")
    fun getById(id: String): StatisticsSeriesEntity?

    @Query("SELECT * FROM statistics_series")
    fun getAll(): List<StatisticsSeriesEntity>
}

@Dao
@Suppress("TooManyFunctions") // One bounded DAO owns lightweight catalog SQL and metadata-only writes.
internal interface LibraryDao {
    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM activity_templates AS templates LEFT JOIN activity_template_user_state AS state " +
            "ON state.activity_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "ORDER BY templates.name COLLATE NOCASE, templates.id",
    )
    fun getActiveActivities(): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM sequence_templates AS templates LEFT JOIN sequence_template_user_state AS state " +
            "ON state.sequence_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "ORDER BY templates.name COLLATE NOCASE, templates.id",
    )
    fun getActiveSequences(): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM activity_templates AS templates LEFT JOIN activity_template_user_state AS state " +
            "ON state.activity_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "AND ((:folderId IS NULL AND templates.folder_id IS NULL) OR templates.folder_id = :folderId) " +
            "ORDER BY templates.name COLLATE NOCASE, templates.id",
    )
    fun getActivitiesInFolder(folderId: String?): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM sequence_templates AS templates LEFT JOIN sequence_template_user_state AS state " +
            "ON state.sequence_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "AND ((:folderId IS NULL AND templates.folder_id IS NULL) OR templates.folder_id = :folderId) " +
            "ORDER BY templates.name COLLATE NOCASE, templates.id",
    )
    fun getSequencesInFolder(folderId: String?): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM activity_templates AS templates LEFT JOIN activity_template_user_state AS state " +
            "ON state.activity_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "AND templates.name LIKE :pattern ESCAPE '\\' COLLATE NOCASE " +
            "ORDER BY templates.name COLLATE NOCASE, templates.id",
    )
    fun searchActivities(pattern: String): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM sequence_templates AS templates LEFT JOIN sequence_template_user_state AS state " +
            "ON state.sequence_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "AND templates.name LIKE :pattern ESCAPE '\\' COLLATE NOCASE " +
            "ORDER BY templates.name COLLATE NOCASE, templates.id",
    )
    fun searchSequences(pattern: String): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM activity_templates AS templates INNER JOIN activity_template_tags AS tags " +
            "ON tags.activity_template_id = templates.id LEFT JOIN activity_template_user_state AS state " +
            "ON state.activity_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "AND tags.tag_id = :tagId " +
            "ORDER BY templates.name COLLATE NOCASE, templates.id",
    )
    fun getActivitiesByTag(tagId: String): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM sequence_templates AS templates INNER JOIN sequence_template_tags AS tags " +
            "ON tags.sequence_template_id = templates.id LEFT JOIN sequence_template_user_state AS state " +
            "ON state.sequence_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "AND tags.tag_id = :tagId " +
            "ORDER BY templates.name COLLATE NOCASE, templates.id",
    )
    fun getSequencesByTag(tagId: String): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM activity_templates AS templates LEFT JOIN activity_template_user_state AS state " +
            "ON state.activity_template_id = templates.id WHERE templates.deleted_at_ms IS NOT NULL " +
            "ORDER BY templates.name COLLATE NOCASE, templates.id",
    )
    fun getArchivedActivities(): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM sequence_templates AS templates LEFT JOIN sequence_template_user_state AS state " +
            "ON state.sequence_template_id = templates.id WHERE templates.deleted_at_ms IS NOT NULL " +
            "ORDER BY templates.name COLLATE NOCASE, templates.id",
    )
    fun getArchivedSequences(): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM activity_templates AS templates INNER JOIN activity_template_user_state AS state " +
            "ON state.activity_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "AND state.pinned_rank IS NOT NULL ORDER BY state.pinned_rank, templates.name COLLATE NOCASE, templates.id",
    )
    fun getPinnedActivities(): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM sequence_templates AS templates INNER JOIN sequence_template_user_state AS state " +
            "ON state.sequence_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "AND state.pinned_rank IS NOT NULL ORDER BY state.pinned_rank, templates.name COLLATE NOCASE, templates.id",
    )
    fun getPinnedSequences(): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM activity_templates AS templates INNER JOIN activity_template_user_state AS state " +
            "ON state.activity_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "AND state.last_used_at_ms IS NOT NULL ORDER BY state.last_used_at_ms DESC, " +
            "templates.name COLLATE NOCASE, templates.id LIMIT :limit",
    )
    fun getRecentActivities(limit: Int): List<LibrarySummaryRow>

    @Query(
        "SELECT templates.id, templates.name, templates.short_comment, templates.folder_id, " +
            "state.pinned_rank, state.last_used_at_ms, templates.deleted_at_ms " +
            "FROM sequence_templates AS templates INNER JOIN sequence_template_user_state AS state " +
            "ON state.sequence_template_id = templates.id WHERE templates.deleted_at_ms IS NULL " +
            "AND state.last_used_at_ms IS NOT NULL ORDER BY state.last_used_at_ms DESC, " +
            "templates.name COLLATE NOCASE, templates.id LIMIT :limit",
    )
    fun getRecentSequences(limit: Int): List<LibrarySummaryRow>

    @Query(
        "SELECT activity_template_id AS template_id, tag_id FROM activity_template_tags " +
            "WHERE activity_template_id IN (:templateIds)",
    )
    fun getActivityTagLinks(templateIds: List<String>): List<LibraryTagLinkRow>

    @Query(
        "SELECT sequence_template_id AS template_id, tag_id FROM sequence_template_tags " +
            "WHERE sequence_template_id IN (:templateIds)",
    )
    fun getSequenceTagLinks(templateIds: List<String>): List<LibraryTagLinkRow>

    @Query("SELECT id FROM activity_templates WHERE id IN (:ids)")
    fun getExistingActivityIds(ids: List<String>): List<String>

    @Query("SELECT id FROM sequence_templates WHERE id IN (:ids)")
    fun getExistingSequenceIds(ids: List<String>): List<String>

    @Query("UPDATE activity_templates SET folder_id = :folderId WHERE id IN (:ids)")
    fun moveActivities(
        ids: List<String>,
        folderId: String?,
    ): Int

    @Query("UPDATE sequence_templates SET folder_id = :folderId WHERE id IN (:ids)")
    fun moveSequences(
        ids: List<String>,
        folderId: String?,
    ): Int

    @Query("UPDATE activity_templates SET folder_id = :destinationId WHERE folder_id = :sourceId")
    fun moveDirectActivities(
        sourceId: String,
        destinationId: String?,
    ): Int

    @Query("UPDATE sequence_templates SET folder_id = :destinationId WHERE folder_id = :sourceId")
    fun moveDirectSequences(
        sourceId: String,
        destinationId: String?,
    ): Int

    @Query(
        "UPDATE activity_templates SET deleted_at_ms = COALESCE(deleted_at_ms, :deletedAtMs), folder_id = NULL " +
            "WHERE folder_id IN (:folderIds)",
    )
    fun archiveActivitiesInFolders(
        folderIds: List<String>,
        deletedAtMs: Long,
    ): Int

    @Query(
        "UPDATE sequence_templates SET deleted_at_ms = COALESCE(deleted_at_ms, :deletedAtMs), folder_id = NULL " +
            "WHERE folder_id IN (:folderIds)",
    )
    fun archiveSequencesInFolders(
        folderIds: List<String>,
        deletedAtMs: Long,
    ): Int

    @Query("UPDATE activity_template_user_state SET pinned_rank = :rank WHERE activity_template_id = :id")
    fun setActivityPinnedRank(
        id: String,
        rank: Int?,
    ): Int

    @Query("UPDATE sequence_template_user_state SET pinned_rank = :rank WHERE sequence_template_id = :id")
    fun setSequencePinnedRank(
        id: String,
        rank: Int?,
    ): Int

    @Query("UPDATE activity_template_user_state SET last_used_at_ms = :atMs WHERE activity_template_id = :id")
    fun touchActivity(
        id: String,
        atMs: Long,
    ): Int

    @Query("UPDATE sequence_template_user_state SET last_used_at_ms = :atMs WHERE sequence_template_id = :id")
    fun touchSequence(
        id: String,
        atMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addActivityTag(link: ActivityTemplateTagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addSequenceTag(link: SequenceTemplateTagEntity): Long

    @Query("DELETE FROM activity_template_tags WHERE activity_template_id = :templateId AND tag_id = :tagId")
    fun removeActivityTag(
        templateId: String,
        tagId: String,
    ): Int

    @Query("DELETE FROM sequence_template_tags WHERE sequence_template_id = :templateId AND tag_id = :tagId")
    fun removeSequenceTag(
        templateId: String,
        tagId: String,
    ): Int
}
