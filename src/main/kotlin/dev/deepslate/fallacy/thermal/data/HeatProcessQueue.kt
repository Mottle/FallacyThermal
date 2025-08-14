package dev.deepslate.fallacy.thermal.data

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos

class HeatProcessQueue : Iterable<HeatProcessQueue.HeatTask> {
    private val threadUnsafeLinkedMapOfChunkTask = Long2ObjectLinkedOpenHashMap<HeatTask>(2048)

    fun contains(chunkPos: ChunkPos) = synchronized(this) { threadUnsafeLinkedMapOfChunkTask.containsKey(chunkPos.toLong()) }

    val empty: Boolean
        get() = synchronized(this) { threadUnsafeLinkedMapOfChunkTask.isEmpty() }

    val size: Int
        get() = synchronized(this) { threadUnsafeLinkedMapOfChunkTask.size }

    //相同区块的方块更新被分配到相同的任务中
    //用以提升性能
    fun enqueueBlockChange(pos: BlockPos) {
        val chunkPos = ChunkPos(pos)
        val packed = chunkPos.toLong()
        val immutablePos = pos.immutable()
        synchronized(this) {
            if (threadUnsafeLinkedMapOfChunkTask.containsKey(packed)) {
                threadUnsafeLinkedMapOfChunkTask[packed].changedPosition.add(immutablePos)
            } else {
                threadUnsafeLinkedMapOfChunkTask[packed] = HeatTask(chunkPos, mutableSetOf(immutablePos), false)
            }
        }
    }

    fun enqueueAll(chunkPos: ChunkPos, positions: Iterable<BlockPos>, initialized: Boolean = false) {
        val packed = chunkPos.toLong()
        synchronized(this) {
            if (threadUnsafeLinkedMapOfChunkTask.containsKey(packed)) {
                threadUnsafeLinkedMapOfChunkTask[packed].changedPosition += positions
            } else {
                threadUnsafeLinkedMapOfChunkTask[packed] = HeatTask(chunkPos, positions.toMutableSet(), initialized)
            }
        }
    }

    fun dequeue(): HeatTask? {
        synchronized(this) {
            return threadUnsafeLinkedMapOfChunkTask.pollFirstEntry()?.value
        }
    }

    data class HeatTask(
        val chunkPos: ChunkPos,
        val changedPosition: MutableSet<BlockPos>,
        val initialized: Boolean
    )

    override fun iterator(): Iterator<HeatTask> = threadUnsafeLinkedMapOfChunkTask.values.iterator()
}