package com.alexandr5476.lifetracing.library

import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryRoot
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant

class LibraryControllerTest {
    @Test
    fun rootUsesTheCanonicalCatalogAndPreservesPersistedPinnedOrder() =
        runBlocking {
            val folder = folder("root", "Root")
            val activeActivity = activity("activity", "Activity")
            val activeSequence = sequence("sequence", "Sequence")
            val archived = activity("archived", "Archived", archived = true)
            val controller =
                controller(
                    this,
                    root =
                        LibraryRoot(
                            LibraryContents(listOf(folder), listOf(activeActivity, archived), listOf(activeSequence)),
                            listOf(activeSequence, activeActivity),
                        ),
                )

            val browse = controller.awaitBrowse()

            assertEquals(listOf("sequence", "activity"), browse.pinned.map { it.id.value })
            assertEquals(listOf("root"), browse.contents.folders.map { it.id.value })
            assertEquals(listOf("activity"), browse.contents.activities.map { it.id.value })
            assertEquals(listOf("sequence"), browse.contents.sequences.map { it.id.value })
            controller.close()
        }

    @Test
    fun nestedFolderUsesCanonicalPathAndOnlyDirectMembership() =
        runBlocking {
            val root = folder("root", "Root")
            val nested = folder("nested", "Nested", root.id)
            val direct = sequence("direct", "Direct")
            val controller =
                controller(
                    this,
                    root = LibraryRoot(LibraryContents(listOf(root), emptyList(), emptyList()), emptyList()),
                    folders =
                        mapOf(
                            root.id to LibraryContents(listOf(nested), emptyList(), emptyList()),
                            nested.id to LibraryContents(emptyList(), emptyList(), listOf(direct)),
                        ),
                    paths = mapOf(root.id to listOf(root), nested.id to listOf(root, nested)),
                )
            controller.awaitBrowse()

            controller.dispatch(LibraryAction.OpenFolder(nested.id))
            val browse = controller.awaitBrowse { it.folderId == nested.id }

            assertEquals(listOf(root.id, nested.id), browse.path.map(Folder::id))
            assertEquals(listOf("direct"), browse.contents.sequences.map { it.id.value })
            assertFalse(browse.contents.activities.isNotEmpty())
            controller.close()
        }

    @Test
    fun searchAndFilterAreReadOnlyGlobalCatalogQueriesThatExcludeArchivedTemplates() =
        runBlocking {
            val activeActivity = activity("activity", "Morning walk")
            val activeSequence = sequence("sequence", "Morning routine")
            val archived = sequence("archived", "Morning archive", archived = true)
            val calls = mutableListOf<Pair<String, LibraryKindFilter>>()
            val controller =
                controller(
                    this,
                    root = LibraryRoot(LibraryContents(emptyList(), emptyList(), emptyList()), emptyList()),
                    search = { query, filter ->
                        calls += query to filter
                        listOf(activeActivity, activeSequence, archived).filter {
                            filter == LibraryKindFilter.ALL ||
                                (filter == LibraryKindFilter.ACTIVITIES && it.id is LibraryTemplateId.Activity) ||
                                (filter == LibraryKindFilter.SEQUENCES && it.id is LibraryTemplateId.Sequence)
                        }
                    },
                )
            controller.awaitBrowse()

            controller.dispatch(LibraryAction.Search("Morning"))
            assertEquals(listOf("activity", "sequence"), controller.awaitSearch().map { it.id.value })
            controller.dispatch(LibraryAction.SetFilter(LibraryKindFilter.SEQUENCES))
            assertEquals(listOf("sequence"), controller.awaitSearch().map { it.id.value })

            assertEquals(
                listOf("Morning" to LibraryKindFilter.ALL, "Morning" to LibraryKindFilter.SEQUENCES),
                calls,
            )
            controller.close()
        }

    @Test
    fun retryReacquiresTheCatalogInsteadOfRetainingAUiLocalCopy() =
        runBlocking {
            var read = 0
            val controller =
                controller(
                    this,
                    rootProvider = {
                        read++
                        LibraryRoot(
                            LibraryContents(emptyList(), listOf(activity("activity", "Version $read")), emptyList()),
                            emptyList(),
                        )
                    },
                )
            assertEquals(
                "Version 1",
                controller
                    .awaitBrowse()
                    .contents.activities
                    .single()
                    .name,
            )

            controller.dispatch(LibraryAction.Retry)

            assertEquals(
                "Version 2",
                controller
                    .awaitBrowse {
                        it.contents.activities
                            .single()
                            .name == "Version 2"
                    }.contents.activities
                    .single()
                    .name,
            )
            assertEquals(2, read)
            controller.close()
        }

    @Suppress("LongParameterList")
    private fun controller(
        scope: CoroutineScope,
        root: LibraryRoot = LibraryRoot(LibraryContents(emptyList(), emptyList(), emptyList()), emptyList()),
        rootProvider: suspend () -> LibraryRoot = { root },
        folders: Map<FolderId, LibraryContents> = emptyMap(),
        paths: Map<FolderId, List<Folder>> = emptyMap(),
        search: suspend (String, LibraryKindFilter) -> List<LibraryTrackable> = { _, _ -> emptyList() },
    ) = LibraryController(
        scope,
        rootProvider,
        { requireNotNull(folders[it]) },
        { requireNotNull(paths[it]) },
        search,
    )

    private suspend fun LibraryController.awaitBrowse(predicate: (LibraryBrowse) -> Boolean = { true }): LibraryBrowse =
        withTimeout(2_000) {
            state.first { (it.browse as? LibraryLoad.Content)?.value?.let(predicate) == true }
        }.let { (it.browse as LibraryLoad.Content).value }

    private suspend fun LibraryController.awaitSearch(): List<LibraryTrackable> =
        withTimeout(2_000) { state.first { it.search is LibraryLoad.Content } }.let {
            (it.search as LibraryLoad.Content).value
        }

    private fun folder(
        id: String,
        name: String,
        parent: FolderId? = null,
    ) = Folder(FolderId(id), name, parent, Instant.EPOCH, Instant.EPOCH)

    private fun activity(
        id: String,
        name: String,
        archived: Boolean = false,
    ) = LibraryTrackable(
        LibraryTemplateId.Activity(ActivityTemplateId(id)),
        name,
        null,
        null,
        emptySet(),
        null,
        null,
        Instant.EPOCH.takeIf { archived },
    )

    private fun sequence(
        id: String,
        name: String,
        archived: Boolean = false,
    ) = LibraryTrackable(
        LibraryTemplateId.Sequence(SequenceTemplateId(id)),
        name,
        null,
        null,
        emptySet(),
        null,
        null,
        Instant.EPOCH.takeIf { archived },
    )
}
