@file:Suppress("LargeClass")

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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class LibraryControllerTest {
    @Test
    fun retainedOwnerRefreshesTheCurrentLibraryProjectionAfterRecreationDuringAMutation() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var catalog = listOf(activity("old", "Old"))
            val owner = LibraryControllerOwner()
            val first =
                owner.get {
                    LibraryController(
                        this,
                        { LibraryRoot(LibraryContents(emptyList(), catalog, emptyList()), emptyList()) },
                        { error("unused folder reader") },
                        { error("unused path reader") },
                        { _, _ -> emptyList() },
                        { LibraryOrganization(emptyList(), emptyList()) },
                        {
                            started.complete(Unit)
                            release.await()
                        },
                    )
                }
            first.awaitBrowse {
                it.contents.activities
                    .singleOrNull()
                    ?.name == "Old"
            }
            first.dispatch(LibraryAction.CreateFolder("Folder"))
            started.await()

            val recreated = owner.get { error("Host recreation must retain the current catalog owner") }
            assertSame(first, recreated)
            assertEquals(
                "Old",
                recreated
                    .currentBrowse()
                    .contents.activities
                    .single()
                    .name,
            )
            catalog = listOf(activity("new", "New"))
            release.complete(Unit)

            assertEquals(
                "New",
                recreated
                    .awaitBrowse {
                        it.contents.activities
                            .singleOrNull()
                            ?.name == "New"
                    }.contents.activities
                    .single()
                    .name,
            )
            recreated.close()
        }

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
    fun failedMutationKeepsCanonicalStateAndReloadClearsFailureWithoutReplayingWriter() =
        runBlocking {
            val folder = folder("folder", "Folder")
            val item = activity("activity", "Match")
            val organization = LibraryOrganization(listOf(folder), emptyList())
            var mutations = 0
            var folderReads = 0
            val controller =
                LibraryController(
                    this,
                    { LibraryRoot(LibraryContents(listOf(folder), emptyList(), emptyList()), emptyList()) },
                    {
                        folderReads++
                        LibraryContents(emptyList(), listOf(item), emptyList())
                    },
                    { listOf(folder) },
                    { _, _ -> listOf(item) },
                    { organization },
                    {
                        mutations++
                        error("write failed")
                    },
                )
            controller.awaitBrowse()
            controller.awaitOrganization()
            controller.dispatch(LibraryAction.OpenFolder(folder.id))
            controller.awaitBrowse { it.folderId == folder.id }
            controller.dispatch(LibraryAction.Search("Match"))
            controller.awaitSearch()
            controller.dispatch(LibraryAction.SetFilter(LibraryKindFilter.ACTIVITIES))

            controller.dispatch(LibraryAction.SetPinned(item.id, true))
            withTimeout(2_000) { controller.state.first { !it.isMutating && it.mutationFailure != null } }

            assertEquals(folder.id, controller.currentBrowse().folderId)
            assertEquals(listOf(item), controller.awaitSearch())
            assertEquals(organization, controller.awaitOrganization())
            controller.dispatch(LibraryAction.Retry)
            withTimeout(2_000) { controller.state.first { it.mutationFailure == null } }

            assertEquals(folder.id, controller.currentBrowse().folderId)
            assertEquals("Match", controller.state.value.query)
            assertEquals(LibraryKindFilter.ACTIVITIES, controller.state.value.filter)
            assertEquals(1, mutations)
            assertTrue(folderReads >= 2)
            controller.close()
        }

    @Test
    fun unrelatedInFlightReadSuccessDoesNotDismissANewerMutationFailure() =
        runBlocking {
            val organizationRelease = CompletableDeferred<Unit>()
            val controller =
                LibraryController(
                    this,
                    { LibraryRoot(LibraryContents(emptyList(), emptyList(), emptyList()), emptyList()) },
                    { error("unused folder reader") },
                    { error("unused path reader") },
                    { _, _ -> emptyList() },
                    {
                        organizationRelease.await()
                        LibraryOrganization(emptyList(), emptyList())
                    },
                    { error("write failed") },
                )
            controller.awaitBrowse()

            controller.dispatch(LibraryAction.CreateFolder("Folder"))
            withTimeout(2_000) { controller.state.first { !it.isMutating && it.mutationFailure != null } }
            organizationRelease.complete(Unit)
            controller.awaitOrganization()

            assertEquals("write failed", controller.state.value.mutationFailure)
            controller.close()
        }

    @Test
    fun committedMutationBrowseFailureUsesBrowseRecoveryAndNeverMutationFailure() =
        runBlocking {
            val before = activity("activity", "Before")
            val after = activity("activity", "After")
            var committed = false
            var rootReads = 0
            var mutations = 0
            val controller =
                LibraryController(
                    this,
                    {
                        rootReads++
                        if (committed && rootReads == 2) error("post-commit browse failed")
                        val item = if (committed) after else before
                        LibraryRoot(LibraryContents(emptyList(), listOf(item), emptyList()), emptyList())
                    },
                    { error("unused folder reader") },
                    { error("unused path reader") },
                    { _, _ -> emptyList() },
                    { LibraryOrganization(emptyList(), emptyList()) },
                    {
                        mutations++
                        committed = true
                    },
                )
            controller.awaitBrowse()
            controller.awaitOrganization()

            controller.dispatch(LibraryAction.SetPinned(before.id, true))
            withTimeout(2_000) { controller.state.first { it.browse is LibraryLoad.Failure && !it.isMutating } }

            assertTrue(committed)
            assertNull(controller.state.value.mutationFailure)
            assertEquals(1, mutations)
            controller.dispatch(LibraryAction.Retry)
            val recovered =
                controller.awaitBrowse {
                    it.contents.activities
                        .singleOrNull()
                        ?.name == "After"
                }

            assertEquals(
                "After",
                recovered.contents.activities
                    .single()
                    .name,
            )
            assertEquals(1, mutations)
            controller.close()
        }

    @Test
    fun organizationFailuresAreRecoverableBeforeAndAfterCommittedMutation() =
        runBlocking {
            val folder = folder("folder", "Folder")
            var organizationReads = 0
            var committed = false
            val controller =
                LibraryController(
                    this,
                    { LibraryRoot(LibraryContents(listOf(folder), emptyList(), emptyList()), emptyList()) },
                    { LibraryContents(emptyList(), emptyList(), emptyList()) },
                    { listOf(folder) },
                    { _, _ -> emptyList() },
                    {
                        organizationReads++
                        if (organizationReads == 1 || organizationReads == 3) error("organization failed")
                        LibraryOrganization(listOf(folder), emptyList())
                    },
                    { committed = true },
                )
            controller.awaitBrowse()
            controller.awaitOrganizationFailure()
            controller.dispatch(LibraryAction.OpenFolder(folder.id))
            controller.awaitBrowse { it.folderId == folder.id }
            controller.dispatch(LibraryAction.Search("kept"))
            controller.awaitSearch()
            controller.dispatch(LibraryAction.SetFilter(LibraryKindFilter.SEQUENCES))

            controller.dispatch(LibraryAction.Retry)
            controller.awaitOrganization()
            assertEquals(folder.id, controller.state.value.folderId)
            assertEquals("kept", controller.state.value.query)
            assertEquals(LibraryKindFilter.SEQUENCES, controller.state.value.filter)

            controller.dispatch(LibraryAction.CreateFolder("Committed"))
            withTimeout(2_000) {
                controller.state.first { it.organization is LibraryLoad.Failure && !it.isMutating }
            }
            assertTrue(committed)
            assertNull(controller.state.value.mutationFailure)

            controller.dispatch(LibraryAction.Retry)
            controller.awaitOrganization()
            assertEquals(folder.id, controller.state.value.folderId)
            assertEquals("kept", controller.state.value.query)
            assertEquals(LibraryKindFilter.SEQUENCES, controller.state.value.filter)
            controller.close()
        }

    @Test
    fun successfulMutationClearsAnOlderMutationFailure() =
        runBlocking {
            var attempts = 0
            val controller =
                LibraryController(
                    this,
                    { LibraryRoot(LibraryContents(emptyList(), emptyList(), emptyList()), emptyList()) },
                    { error("unused folder reader") },
                    { error("unused path reader") },
                    { _, _ -> emptyList() },
                    { LibraryOrganization(emptyList(), emptyList()) },
                    {
                        attempts++
                        if (attempts == 1) error("first failed")
                    },
                )
            controller.awaitBrowse()
            controller.awaitOrganization()

            controller.dispatch(LibraryAction.CreateFolder("First"))
            withTimeout(2_000) { controller.state.first { !it.isMutating && it.mutationFailure != null } }
            controller.dispatch(LibraryAction.CreateFolder("Second"))
            withTimeout(2_000) { controller.state.first { attempts == 2 && !it.isMutating } }

            assertNull(controller.state.value.mutationFailure)
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

    @Test
    fun archiveActionsUseTheTemplateKindAndRefreshCanonicalBrowseAndSearch() =
        runBlocking {
            val activity = activity("activity", "Match activity")
            val sequence = sequence("sequence", "Match sequence")
            var catalog = listOf(activity, sequence)
            val mutations = mutableListOf<LibraryMutation>()
            val controller =
                LibraryController(
                    this,
                    {
                        LibraryRoot(
                            LibraryContents(emptyList(), catalog.filterActivities(), catalog.filterSequences()),
                            emptyList(),
                        )
                    },
                    { error("unused folder reader") },
                    { error("unused path reader") },
                    { query, _ -> catalog.filter { it.name.contains(query) } },
                    { LibraryOrganization(emptyList(), emptyList()) },
                    { mutation ->
                        mutations += mutation
                        val archivedId = (mutation as LibraryMutation.ArchiveTemplate).id
                        catalog = catalog.filterNot { it.id == archivedId }
                    },
                    { Instant.EPOCH },
                )
            controller.awaitBrowse()
            controller.dispatch(LibraryAction.Search("Match"))
            controller.awaitSearch { it.size == 2 }

            controller.dispatch(LibraryAction.ArchiveTemplate(activity.id))
            controller.awaitBrowse { it.contents.activities.isEmpty() }
            controller.awaitSearch { it.map(LibraryTrackable::id) == listOf(sequence.id) }
            controller.dispatch(LibraryAction.ArchiveTemplate(sequence.id))
            controller.awaitBrowse { it.contents.sequences.isEmpty() }

            assertEquals(
                listOf(
                    LibraryMutation.ArchiveTemplate(activity.id, Instant.EPOCH),
                    LibraryMutation.ArchiveTemplate(sequence.id, Instant.EPOCH),
                ),
                mutations,
            )
            controller.close()
        }

    @Test
    fun folderDeleteInspectionUsesDurableOccupancyAndLinearNonDescendantCatalog() =
        runBlocking {
            val source = folder("source", "Source")
            val child = folder("child", "Child", source.id)
            val grandchild = folder("grandchild", "Grandchild", child.id)
            val destination = folder("destination", "Destination")
            var reads = 0
            val controller =
                LibraryController(
                    this,
                    {
                        LibraryRoot(
                            LibraryContents(listOf(source, destination), emptyList(), emptyList()),
                            emptyList(),
                        )
                    },
                    {
                        error("Active Folder contents must not classify deletion occupancy")
                    },
                    { error("unused path reader") },
                    { _, _ -> emptyList() },
                    { LibraryOrganization(listOf(source, child, grandchild, destination), emptyList()) },
                    readFolderDeletionIsEmpty = {
                        reads++
                        assertEquals(source.id, it)
                        false
                    },
                )
            controller.awaitBrowse()
            controller.awaitOrganization()

            controller.dispatch(LibraryAction.RequestFolderDeletion(source))
            val options = controller.awaitFolderDeletion()

            assertFalse(options.isEmpty)
            assertEquals(listOf(destination.id), options.destinations.map(Folder::id))
            assertEquals(1, reads)
            controller.dispatch(LibraryAction.DismissFolderDeletion)
            assertNull(controller.state.value.folderDeletion)
            controller.close()
        }

    @Test
    fun emptyFolderRequiresInspectionBeforeItsSingleTransactionalDelete() =
        runBlocking {
            val folder = folder("empty", "Empty")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val mutations = mutableListOf<LibraryMutation>()
            val controller =
                LibraryController(
                    this,
                    { LibraryRoot(LibraryContents(listOf(folder), emptyList(), emptyList()), emptyList()) },
                    { LibraryContents(emptyList(), emptyList(), emptyList()) },
                    { error("unused path reader") },
                    { _, _ -> emptyList() },
                    { LibraryOrganization(listOf(folder), emptyList()) },
                    {
                        mutations += it
                        started.complete(Unit)
                        release.await()
                    },
                    { Instant.EPOCH },
                    readFolderDeletionIsEmpty = { true },
                )
            controller.awaitBrowse()
            controller.dispatch(LibraryAction.DeleteEmptyFolder(folder.id))
            kotlinx.coroutines.yield()
            assertTrue(mutations.isEmpty())

            controller.dispatch(LibraryAction.RequestFolderDeletion(folder))
            assertTrue(controller.awaitFolderDeletion().isEmpty)
            controller.dispatch(LibraryAction.DeleteEmptyFolder(folder.id))
            started.await()
            controller.dispatch(LibraryAction.DeleteEmptyFolder(folder.id))
            release.complete(Unit)
            withTimeout(2_000) { controller.state.first { mutations.isNotEmpty() && !it.isMutating } }

            assertEquals(
                listOf(LibraryMutation.DeleteEmptyFolder(folder.id, Instant.EPOCH)),
                mutations,
            )
            controller.close()
        }

    @Test
    fun staleEmptyFolderConfirmationUsesGuardedWriterAndCanBeReinspected() =
        runBlocking {
            val folder = folder("empty", "Empty")
            var isEmpty = true
            val mutations = mutableListOf<LibraryMutation>()
            val controller =
                LibraryController(
                    this,
                    { LibraryRoot(LibraryContents(listOf(folder), emptyList(), emptyList()), emptyList()) },
                    { LibraryContents(emptyList(), emptyList(), emptyList()) },
                    { error("unused path reader") },
                    { _, _ -> emptyList() },
                    { LibraryOrganization(listOf(folder), emptyList()) },
                    { mutation ->
                        mutations += mutation
                        error("Folder is not empty")
                    },
                    { Instant.EPOCH },
                    readFolderDeletionIsEmpty = { isEmpty },
                )
            controller.awaitBrowse()
            controller.dispatch(LibraryAction.RequestFolderDeletion(folder))
            assertTrue(controller.awaitFolderDeletion().isEmpty)

            isEmpty = false
            controller.dispatch(LibraryAction.DeleteEmptyFolder(folder.id))
            withTimeout(2_000) { controller.state.first { !it.isMutating && it.mutationFailure != null } }
            assertEquals(listOf(LibraryMutation.DeleteEmptyFolder(folder.id, Instant.EPOCH)), mutations)

            controller.dispatch(LibraryAction.RequestFolderDeletion(folder))
            assertFalse(controller.awaitFolderDeletion().isEmpty)
            controller.close()
        }

    @Test
    fun nonEmptyFolderAcceptsOnlyAnExplicitInspectedDispositionAndBlocksDuplicateConfirmation() =
        runBlocking {
            val source = folder("source", "Source")
            val destination = folder("destination", "Destination")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val mutations = mutableListOf<LibraryMutation>()
            val controller =
                LibraryController(
                    this,
                    {
                        LibraryRoot(
                            LibraryContents(listOf(source, destination), emptyList(), emptyList()),
                            emptyList(),
                        )
                    },
                    { LibraryContents(emptyList(), emptyList(), listOf(sequence("nested", "Nested"))) },
                    { error("unused path reader") },
                    { _, _ -> emptyList() },
                    { LibraryOrganization(listOf(source, destination), emptyList()) },
                    {
                        mutations += it
                        started.complete(Unit)
                        release.await()
                    },
                    { Instant.EPOCH },
                    readFolderDeletionIsEmpty = { false },
                )
            controller.awaitBrowse()
            controller.dispatch(LibraryAction.DeleteFolderAndArchiveContents(source.id))
            kotlinx.coroutines.yield()
            assertTrue(mutations.isEmpty())

            controller.dispatch(LibraryAction.RequestFolderDeletion(source))
            assertFalse(controller.awaitFolderDeletion().isEmpty)
            controller.dispatch(LibraryAction.DeleteFolderMovingContents(source.id, destination.id))
            started.await()
            controller.dispatch(LibraryAction.DeleteFolderMovingContents(source.id, destination.id))
            release.complete(Unit)
            withTimeout(2_000) { controller.state.first { !it.isMutating } }

            assertEquals(
                listOf(LibraryMutation.DeleteFolderMovingContents(source.id, destination.id, Instant.EPOCH)),
                mutations,
            )
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

    private suspend fun LibraryController.awaitOrganization(): LibraryOrganization =
        withTimeout(2_000) { state.first { it.organization is LibraryLoad.Content } }
            .let { (it.organization as LibraryLoad.Content).value }

    private suspend fun LibraryController.awaitOrganizationFailure() =
        withTimeout(2_000) { state.first { it.organization is LibraryLoad.Failure } }

    private suspend fun LibraryController.awaitFolderDeletion(): LibraryFolderDeletionOptions =
        withTimeout(2_000) {
            state.first { it.folderDeletion?.options is LibraryLoad.Content }
        }.folderDeletion!!.let { (it.options as LibraryLoad.Content).value }

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

    private fun List<LibraryTrackable>.filterSequences() = filter { it.id is LibraryTemplateId.Sequence }

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
