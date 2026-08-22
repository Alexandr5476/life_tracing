package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class FolderTreeValidatorTest {
    private val root = FolderId("root")
    private val child = FolderId("child")
    private val grandchild = FolderId("grandchild")
    private val unrelated = FolderId("unrelated")
    private val parents =
        mapOf(
            root to null,
            child to root,
            grandchild to child,
            unrelated to null,
        )

    @Test
    fun `root parent is valid`() {
        assertTrue(FolderTreeValidator.canMove(child, null, parents))
    }

    @Test
    fun `unrelated existing parent is valid`() {
        assertTrue(FolderTreeValidator.canMove(child, unrelated, parents))
    }

    @Test
    fun `self parent is rejected`() {
        assertFalse(FolderTreeValidator.canMove(child, child, parents))
    }

    @Test
    fun `direct child as parent is rejected`() {
        assertFalse(FolderTreeValidator.canMove(root, child, parents))
    }

    @Test
    fun `deeper descendant as parent is rejected`() {
        assertFalse(FolderTreeValidator.canMove(root, grandchild, parents))
    }

    @Test
    fun `arbitrary valid nested depth is accepted`() {
        val deepParents =
            (1..100).associate { depth ->
                FolderId("folder-$depth") to if (depth == 1) null else FolderId("folder-${depth - 1}")
            }

        assertTrue(FolderTreeValidator.canMove(FolderId("new"), FolderId("folder-100"), deepParents))
    }

    @Test
    fun `path is root first and corrupt cycles fail explicitly`() {
        val folders =
            parents.mapValues { (id, parent) -> Folder(id, id.value, parent, Instant.EPOCH, Instant.EPOCH) }
        assertEquals(
            listOf(root, child, grandchild),
            FolderTreeValidator.path(grandchild, folders::get).map(Folder::id),
        )

        val corrupt =
            mapOf(
                root to folders.getValue(root).copy(parentFolderId = grandchild),
                child to folders.getValue(child),
                grandchild to folders.getValue(grandchild),
            )
        assertThrows(IllegalArgumentException::class.java) {
            FolderTreeValidator.path(grandchild, corrupt::get)
        }
    }

    @Test
    fun `move validation rejects descendant and corrupt destination chains`() {
        val folders =
            parents.mapValues { (id, parent) -> Folder(id, id.value, parent, Instant.EPOCH, Instant.EPOCH) }
        assertThrows(IllegalArgumentException::class.java) {
            FolderTreeValidator.requireCanMove(root, grandchild, folders::get)
        }

        val cycle =
            mapOf(
                child to folders.getValue(child).copy(parentFolderId = grandchild),
                grandchild to folders.getValue(grandchild).copy(parentFolderId = child),
            )
        assertThrows(IllegalArgumentException::class.java) {
            FolderTreeValidator.requireCanMove(unrelated, child, cycle::get)
        }
    }
}
