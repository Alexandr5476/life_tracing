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

    fun requireCanMove(
        folderId: FolderId,
        parentFolderId: FolderId?,
        folderById: (FolderId) -> Folder?,
    ) {
        var ancestor = parentFolderId
        val visited = mutableSetOf<FolderId>()

        while (ancestor != null) {
            require(ancestor != folderId) { "Folder cannot move under itself or its descendant" }
            require(visited.add(ancestor)) { "Folder hierarchy contains a cycle" }
            ancestor = requireNotNull(folderById(ancestor)) { "Unknown Folder: ${ancestor.value}" }.parentFolderId
        }
    }

    fun path(
        folderId: FolderId,
        folderById: (FolderId) -> Folder?,
    ): List<Folder> {
        val reversePath = mutableListOf<Folder>()
        val visited = mutableSetOf<FolderId>()
        var current: FolderId? = folderId

        while (current != null) {
            require(visited.add(current)) { "Folder hierarchy contains a cycle" }
            val folder = requireNotNull(folderById(current)) { "Unknown Folder: ${current.value}" }
            reversePath += folder
            current = folder.parentFolderId
        }
        return reversePath.asReversed()
    }
}
