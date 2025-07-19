package dev.deepslate.fallacy.thermal.data

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos

class HeatProcessQueue : Iterable<HeatProcessQueue.HeatTask> {
    private val threadUnsafeLinkedMap = Long2ObjectLinkedOpenHashMap<HeatTask>(2048)

    fun contains(chunkPos: ChunkPos) = synchronized(this) { threadUnsafeLinkedMap.containsKey(chunkPos.toLong()) }

    val empty: Boolean
        get() = synchronized(this) { threadUnsafeLinkedMap.isEmpty() }

    val size: Int
        get() = synchronized(this) { threadUnsafeLinkedMap.size }

    fun enqueueBlockChange(pos: BlockPos) {
        val chunkPos = ChunkPos(pos)
        val packed = chunkPos.toLong()
        val immutablePos = pos.immutable()
        synchronized(this) {
            if (threadUnsafeLinkedMap.containsKey(packed)) {
                threadUnsafeLinkedMap[packed].changedPosition + immutablePos
            } else {
                threadUnsafeLinkedMap[packed] = HeatTask(chunkPos, mutableSetOf(immutablePos), false)
            }
        }
    }

    fun enqueueAll(chunkPos: ChunkPos, positions: Iterable<BlockPos>, initialized: Boolean = false) {
        val packed = chunkPos.toLong()
        synchronized(this) {
            if (threadUnsafeLinkedMap.containsKey(packed)) {
                threadUnsafeLinkedMap[packed].changedPosition += positions
            } else {
                threadUnsafeLinkedMap[packed] = HeatTask(chunkPos, positions.toMutableSet(), initialized)
            }
        }
    }

    fun dequeue(): HeatTask? {
        synchronized(this) {
            return threadUnsafeLinkedMap.pollFirstEntry()?.value
        }
    }

    data class HeatTask(
        val chunkPos: ChunkPos,
        val changedPosition: MutableSet<BlockPos>,
        val initialized: Boolean
    )

    override fun iterator(): Iterator<HeatTask> = threadUnsafeLinkedMap.values.iterator()
}