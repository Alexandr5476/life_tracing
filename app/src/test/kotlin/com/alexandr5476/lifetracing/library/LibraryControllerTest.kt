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
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun refreshReReadsBrowseAndActiveSearchWithItsCurrentFilter() =
        runBlocking {
            var browseReads = 0
            var catalog = listOf(activity("old", "Match old"), sequence("sequence", "Match sequence"))
            val searchCalls = mutableListOf<Pair<String, LibraryKindFilter>>()
            val controller =
                controller(
                    this,
                    rootProvider = {
                        browseReads++
                        LibraryRoot(LibraryContents(emptyList(), catalog.filterActivities(), emptyList()), emptyList())
                    },
                    search = { query, filter ->
                        searchCalls += query to filter
                        catalog.filter { item ->
                            item.name.contains(query, ignoreCase = true) &&
                                (filter != LibraryKindFilter.ACTIVITIES || item.id is LibraryTemplateId.Activity)
                        }
                    },
                )
            controller.awaitBrowse()
            controller.dispatch(LibraryAction.Search("Match"))
            controller.awaitSearch { it.any { item -> item.id.value == "old" } }
            controller.dispatch(LibraryAction.SetFilter(LibraryKindFilter.ACTIVITIES))
            controller.awaitSearch { it.size == 1 && it.single().id.value == "old" }

            catalog = listOf(activity("old", "Renamed"), activity("new", "Match new"))
            controller.dispatch(LibraryAction.Refresh)

            assertEquals(
                listOf("new"),
                controller.awaitSearch { it.singleOrNull()?.id?.value == "new" }.map { it.id.value },
            )
            assertEquals(2, browseReads)
            assertEquals(LibraryKindFilter.ACTIVITIES, controller.state.value.filter)
            assertEquals("Match" to LibraryKindFilter.ACTIVITIES, searchCalls.last())
            controller.close()
        }

    @Test
    fun refreshKeepsTheCurrentFolderBrowseLocation() =
        runBlocking {
            val folder = folder("folder", "Folder")
            var folderReads = 0
            val controller =
                LibraryController(
                    this,
                    { LibraryRoot(LibraryContents(listOf(folder), emptyList(), emptyList()), emptyList()) },
                    {
                        folderReads++
                        LibraryContents(emptyList(), listOf(activity("activity", "Version $folderReads")), emptyList())
                    },
                    { listOf(folder) },
                    { _, _ -> emptyList() },
                )
            controller.awaitBrowse()
            controller.dispatch(LibraryAction.OpenFolder(folder.id))
            controller.awaitBrowse {
                it.contents.activities
                    .singleOrNull()
                    ?.name == "Version 1"
            }

            controller.dispatch(LibraryAction.Refresh)

            val refreshed =
                controller.awaitBrowse {
                    it.contents.activities
                        .singleOrNull()
                        ?.name == "Version 2"
                }
            assertEquals(folder.id, refreshed.folderId)
            assertEquals(folder.id, controller.state.value.folderId)
            controller.close()
        }

    @Test
    fun staleRefreshSearchCannotReplaceANewerQuery() =
        runBlocking {
            val bothOldSearchesStarted = CompletableDeferred<Unit>()
            val releaseOldSearches = CompletableDeferred<Unit>()
            var oldSearches = 0
            val controller =
                controller(
                    this,
                    search = { query, _ ->
                        if (query == "old") {
                            oldSearches++
                            if (oldSearches == 2) bothOldSearchesStarted.complete(Unit)
                            releaseOldSearches.await()
                        }
                        listOf(activity(query, query))
                    },
                )
            controller.awaitBrowse()
            controller.dispatch(LibraryAction.Search("old"))
            controller.dispatch(LibraryAction.Refresh)
            bothOldSearchesStarted.await()

            controller.dispatch(LibraryAction.Search("new"))
            controller.awaitSearch { it.singleOrNull()?.name == "new" }
            releaseOldSearches.complete(Unit)

            assertEquals("new", controller.state.value.query)
            assertEquals("new", controller.awaitSearch().single().name)
            controller.close()
        }

    @Test
    fun failedNestedFolderRetryReusesItsCanonicalFolderIdentity() =
        runBlocking {
            val root = folder("root", "Root")
            val nested = folder("nested", "Nested", root.id)
            var rootReads = 0
            val pathReads = mutableListOf<FolderId>()
            val contentReads = mutableListOf<FolderId>()
            val controller =
                LibraryController(
                    this,
                    {
                        rootReads++
                        LibraryRoot(LibraryContents(listOf(root), emptyList(), emptyList()), emptyList())
                    },
                    { id ->
                        contentReads += id
                        LibraryContents(emptyList(), emptyList(), emptyList())
                    },
                    { id ->
                        pathReads += id
                        if (pathReads.size == 1) error("nested read failed")
                        listOf(root, nested)
                    },
                    { _, _ -> emptyList() },
                )
            controller.awaitBrowse()

            controller.dispatch(LibraryAction.OpenFolder(nested.id))
            controller.awaitBrowseFailure()
            assertEquals(nested.id, controller.state.value.folderId)

            controller.dispatch(LibraryAction.Retry)
            val browse = controller.awaitBrowse { it.folderId == nested.id }

            assertEquals(1, rootReads)
            assertEquals(listOf(nested.id, nested.id), pathReads)
            assertEquals(listOf(nested.id), contentReads)
            assertEquals(listOf(root.id, nested.id), browse.path.map(Folder::id))
            controller.close()
        }

    @Test
    fun failedRootRetryReloadsRoot() =
        runBlocking {
            var rootReads = 0
            val controller =
                LibraryController(
                    this,
                    {
                        rootReads++
                        if (rootReads == 1) error("root read failed")
                        LibraryRoot(LibraryContents(emptyList(), emptyList(), emptyList()), emptyList())
                    },
                    { error("unused folder contents") },
                    { error("unused folder path") },
                    { _, _ -> emptyList() },
                )
            controller.awaitBrowseFailure()
            assertEquals(null, controller.state.value.folderId)

            controller.dispatch(LibraryAction.Retry)
            controller.awaitBrowse()

            assertEquals(2, rootReads)
            controller.close()
        }

    @Test
    fun staleNestedRetryCannotReplaceANewerFolder() =
        runBlocking {
            val root = folder("root", "Root")
            val nested = folder("nested", "Nested", root.id)
            val newer = folder("newer", "Newer", root.id)
            val retryPathStarted = CompletableDeferred<Unit>()
            val releaseRetryPath = CompletableDeferred<List<Folder>>()
            val staleContentsRead = CompletableDeferred<Unit>()
            var nestedPathReads = 0
            val controller =
                LibraryController(
                    this,
                    { LibraryRoot(LibraryContents(listOf(root), emptyList(), emptyList()), emptyList()) },
                    { id ->
                        if (id == nested.id) staleContentsRead.complete(Unit)
                        LibraryContents(emptyList(), emptyList(), emptyList())
                    },
                    { id ->
                        when (id) {
                            nested.id -> {
                                nestedPathReads++
                                if (nestedPathReads == 1) error("nested read failed")
                                retryPathStarted.complete(Unit)
                                releaseRetryPath.await()
                            }
                            newer.id -> listOf(root, newer)
                            else -> error("unexpected folder")
                        }
                    },
                    { _, _ -> emptyList() },
                )
            controller.awaitBrowse()
            controller.dispatch(LibraryAction.OpenFolder(nested.id))
            controller.awaitBrowseFailure()

            controller.dispatch(LibraryAction.Retry)
            retryPathStarted.await()
            controller.dispatch(LibraryAction.OpenFolder(newer.id))
            controller.awaitBrowse { it.folderId == newer.id }
            releaseRetryPath.complete(listOf(root, nested))
            staleContentsRead.await()

            assertEquals(newer.id, controller.state.value.folderId)
            assertEquals(newer.id, controller.currentBrowse().folderId)
            assertEquals(2, nestedPathReads)
            controller.close()
        }

    @Test
    fun successfulMetadataMutationReReadsCanonicalBrowseAndPreservesSearchFilter() =
        runBlocking {
            var catalog = listOf(activity("activity", "Before"))
            val mutations = mutableListOf<LibraryMutation>()
            val controller =
                LibraryController(
                    this,
                    { LibraryRoot(LibraryContents(emptyList(), catalog, emptyList()), emptyList()) },
                    { error("unused folder reader") },
                    { error("unused path reader") },
                    { query, filter ->
                        catalog.filter {
                            it.name.contains(query, ignoreCase = true) &&
                                (filter != LibraryKindFilter.SEQUENCES || it.id is LibraryTemplateId.Sequence)
                        }
                    },
                    { LibraryOrganization(emptyList(), emptyList()) },
                    { mutation ->
                        mutations += mutation
                        catalog = listOf(activity("activity", "After"))
                    },
                )
            controller.awaitBrowse()
            controller.dispatch(LibraryAction.Search("After"))
            controller.awaitSearch { it.isEmpty() }
            controller.dispatch(LibraryAction.SetFilter(LibraryKindFilter.ACTIVITIES))

            controller.dispatch(LibraryAction.MoveTemplate(activity("activity", "Before").id, null))

            assertEquals(
                "After",
                controller
                    .awaitBrowse {
                        it.contents.activities
                            .single()
                            .name == "After"
                    }.contents.activities
                    .single()
                    .name,
            )
            assertEquals("After", controller.awaitSearch { it.singleOrNull()?.name == "After" }.single().name)
            assertEquals(LibraryKindFilter.ACTIVITIES, controller.state.value.filter)
            assertEquals(1, mutations.size)
            controller.close()
        }

    @Test
    fun repeatedCreateDoesNotSubmitConcurrentDuplicateMutations() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var mutations = 0
            val controller =
                LibraryController(
                    this,
                    { LibraryRoot(LibraryContents(emptyList(), emptyList(), emptyList()), emptyList()) },
                    { error("unused folder reader") },
                    { error("unused path reader") },
                    { _, _ -> emptyList() },
                    { LibraryOrganization(emptyList(), emptyList()) },
                    {
                        mutations++
                        started.complete(Unit)
                        release.await()
                    },
                )
            controller.awaitBrowse()

            controller.dispatch(LibraryAction.CreateFolder("Folder"))
            started.await()
            controller.dispatch(LibraryAction.CreateFolder("Folder"))
            release.complete(Unit)
            withTimeout(2_000) { controller.state.first { !it.isMutating } }

            assertEquals(1, mutations)
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

    private suspend fun LibraryController.awaitBrowseFailure() =
        withTimeout(2_000) { state.first { it.browse is LibraryLoad.Failure } }

    private fun LibraryController.currentBrowse(): LibraryBrowse = (state.value.browse as LibraryLoad.Content).value

    private suspend fun LibraryController.awaitSearch(
        predicate: (List<LibraryTrackable>) -> Boolean = { true },
    ): List<LibraryTrackable> =
        withTimeout(2_000) {
            state.first { (it.search as? LibraryLoad.Content)?.value?.let(predicate) == true }
        }.let {
            (it.search as LibraryLoad.Content).value
        }

    private fun List<LibraryTrackable>.filterActivities() = filter { it.id is LibraryTemplateId.Activity }

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
