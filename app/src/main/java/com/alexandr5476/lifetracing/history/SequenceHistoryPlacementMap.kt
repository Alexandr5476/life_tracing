@file:Suppress("ReturnCount", "MaxLineLength")

package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.SequenceIntervalId

private typealias PlacementEntry = Map.Entry<SequenceIntervalId, OwnerlessIntervalPlacement?>

/** Non-durable exact-ID choices. Publications share the index and one O(log N) tree path per selection. */
internal class SequenceHistoryPlacementMap private constructor(
    private val positions: Map<SequenceIntervalId, Int>,
    private val root: Node?,
    val unresolvedCount: Int,
) : AbstractMap<SequenceIntervalId, OwnerlessIntervalPlacement?>() {
    private data class Node(
        val entry: PlacementEntry,
        val left: Node? = null,
        val right: Node? = null,
    )

    override val entries: Set<PlacementEntry>
        get() =
            object : AbstractSet<PlacementEntry>() {
                override val size: Int get() = positions.size

                override fun iterator(): Iterator<PlacementEntry> =
                    sequence {
                        suspend fun SequenceScope<PlacementEntry>.visit(node: Node?) {
                            if (node == null) return
                            visit(node.left)
                            yield(node.entry)
                            visit(node.right)
                        }
                        visit(root)
                    }.iterator()
            }

    override val size: Int get() = positions.size

    override fun containsKey(key: SequenceIntervalId): Boolean = key in positions

    override fun get(key: SequenceIntervalId): OwnerlessIntervalPlacement? {
        val position = positions[key] ?: return null
        var start = 0
        var end = positions.size
        var node = root
        while (node != null) {
            val middle = (start + end) / 2
            when {
                position == middle -> return node.entry.value
                position < middle -> {
                    node = node.left
                    end = middle
                }
                else -> {
                    node = node.right
                    start = middle + 1
                }
            }
        }
        return null
    }

    fun updated(
        target: SequenceIntervalId,
        value: OwnerlessIntervalPlacement?,
    ): SequenceHistoryPlacementMap {
        val position = positions.getValue(target)

        fun replace(
            node: Node,
            start: Int,
            end: Int,
        ): Node {
            val middle = (start + end) / 2
            return when {
                position == middle -> node.copy(entry = java.util.AbstractMap.SimpleImmutableEntry(target, value))
                position < middle -> node.copy(left = replace(requireNotNull(node.left), start, middle))
                else -> node.copy(right = replace(requireNotNull(node.right), middle + 1, end))
            }
        }
        return SequenceHistoryPlacementMap(
            positions,
            replace(requireNotNull(root), 0, positions.size),
            unresolvedCount - (if (get(target) == null) 1 else 0) + (if (value == null) 1 else 0),
        )
    }

    companion object {
        fun from(values: Map<SequenceIntervalId, OwnerlessIntervalPlacement?>): SequenceHistoryPlacementMap {
            val entries = values.entries.map { java.util.AbstractMap.SimpleImmutableEntry(it.key, it.value) }

            fun build(
                start: Int,
                end: Int,
            ): Node? {
                if (start == end) return null
                val middle = (start + end) / 2
                return Node(entries[middle], build(start, middle), build(middle + 1, end))
            }
            return SequenceHistoryPlacementMap(
                values.keys.withIndex().associate { it.value to it.index },
                build(0, entries.size),
                entries.count { it.value == null },
            )
        }
    }
}
