package com.alexandr5476.lifetracing.domain

object FolderTreeValidator {
    fun canMove(
        folderId: FolderId,
        parentFolderId: FolderId?,
        parents: Map<FolderId, FolderId?>,
    ): Boolean {
        var ancestor = parentFolderId
        val visited = mutableSetOf<FolderId>()

        while (ancestor != null && visited.add(ancestor)) {
            if (ancestor == folderId) return false
            ancestor = parents[ancestor]
        }

        return ancestor == null
    }
}
