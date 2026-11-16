@file:Suppress("ReturnCount", "MaxLineLength")

package com.alexandr5476.lifetracing.history

private typealias TimestampEntry = Map.Entry<SequenceHistoryTimestampTarget, SequenceHistoryTimestampDraft>

/** Immutable draft values: edits share the index and all but one O(log N) tree path. */
internal class SequenceHistoryTimestampMap private constructor(
    private val positions: Map<SequenceHistoryTimestampTarget, Int>,
    private val root: Node?,
) : AbstractMap<SequenceHistoryTimestampTarget, SequenceHistoryTimestampDraft>() {
    private data class Node(
        val entry: TimestampEntry,
        val left: Node? = null,
        val right: Node? = null,
    )

    override val entries: Set<TimestampEntry>
        get() =
            object : AbstractSet<TimestampEntry>() {
                override val size: Int get() = positions.size

                override fun iterator(): Iterator<TimestampEntry> =
                    sequence {
                        suspend fun SequenceScope<TimestampEntry>.visit(node: Node?) {
                            if (node == null) return
                            visit(node.left)
                            yield(node.entry)
                            visit(node.right)
                        }
                        visit(root)
                    }.iterator()
            }

    override fun containsKey(key: SequenceHistoryTimestampTarget): Boolean = key in positions

    override fun get(key: SequenceHistoryTimestampTarget): SequenceHistoryTimestampDraft? {
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
        target: SequenceHistoryTimestampTarget,
        value: SequenceHistoryTimestampDraft,
    ): SequenceHistoryTimestampMap {
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
        return SequenceHistoryTimestampMap(positions, replace(requireNotNull(root), 0, positions.size))
    }

    companion object {
        fun from(
            values: Map<SequenceHistoryTimestampTarget, SequenceHistoryTimestampDraft>,
        ): SequenceHistoryTimestampMap {
            val entries = values.entries.map { java.util.AbstractMap.SimpleImmutableEntry(it.key, it.value) }

            fun build(
                start: Int,
                end: Int,
            ): Node? {
                if (start == end) return null
                val middle = (start + end) / 2
                return Node(entries[middle], build(start, middle), build(middle + 1, end))
            }
            return SequenceHistoryTimestampMap(
                values.keys.withIndex().associate { it.value to it.index },
                build(0, entries.size),
            )
        }
    }
}

internal fun Map<SequenceHistoryTimestampTarget, SequenceHistoryTimestampDraft>.withTimestamp(
    target: SequenceHistoryTimestampTarget,
    value: SequenceHistoryTimestampDraft,
): Map<SequenceHistoryTimestampTarget, SequenceHistoryTimestampDraft> =
    (this as? SequenceHistoryTimestampMap ?: SequenceHistoryTimestampMap.from(this)).updated(target, value)
