package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
