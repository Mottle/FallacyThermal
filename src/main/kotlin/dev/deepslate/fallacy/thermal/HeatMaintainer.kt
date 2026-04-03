package dev.deepslate.fallacy.thermal

import dev.deepslate.fallacy.thermal.data.HeatStorage
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunkSection

abstract class HeatMaintainer(
    val engine: ThermodynamicsEngine,
    private val snapshots: Map<Long, ChunkSnapshot>
) {
    data class ProcessResult(
        val updates: Map<Long, HeatStorage>,
        val touchedChunks: Set<Long>
    )

    data class ChunkSnapshot(
        val chunkPos: ChunkPos,
        val sections: List<LevelChunkSection?>,
        val positiveHeat: HeatStorage,
        val negativeHeat: HeatStorage
    )

    companion object {
        const val BASE_CACHED_CHUNK_REGION_LENGTH = 7
        const val BASE_CACHED_CHUNK_REGION_AREA = BASE_CACHED_CHUNK_REGION_LENGTH * BASE_CACHED_CHUNK_REGION_LENGTH
    }

    protected enum class TaskType {
        NORM, RECHECKED, WRITE
    }

    protected data class PropagateTask(val pos: BlockPos, val propagateHeat: Int, val taskType: TaskType)

    val level: Level
        get() = engine.level

    protected val sectionCache =
        Long2ObjectLinkedOpenHashMap<List<LevelChunkSection?>>(BASE_CACHED_CHUNK_REGION_AREA * 16)

    protected val storageCache = Long2ObjectLinkedOpenHashMap<HeatStorage>(BASE_CACHED_CHUNK_REGION_AREA)

    protected val markChangedChunk = LongOpenHashSet(BASE_CACHED_CHUNK_REGION_LENGTH * 4)

    protected val increasedQueue: ObjectArrayFIFOQueue<PropagateTask> = ObjectArrayFIFOQueue(16 * 16 * 16)

    protected val decreasedQueue: ObjectArrayFIFOQueue<PropagateTask> = ObjectArrayFIFOQueue(16 * 16 * 16)
    protected val touchedChunks: MutableSet<Long> = linkedSetOf()

    protected fun isOutOfBuildHeight(pos: BlockPos): Boolean = pos.y !in level.minBuildHeight until level.maxBuildHeight
    protected fun markTouchedChunk(packedChunkPos: Long) {
        touchedChunks.add(packedChunkPos)
    }

    private fun setupCache() {
        snapshots.values.forEach { snapshot ->
            val packedChunkPos = snapshot.chunkPos.toLong()
            sectionCache.computeIfAbsent(packedChunkPos) { snapshot.sections }
            storageCache.computeIfAbsent(packedChunkPos) { query(snapshot) }
        }
    }

    abstract fun query(snapshot: ChunkSnapshot): HeatStorage

    abstract fun setHeat(pos: BlockPos, heat: Int)

    abstract fun getHeat(pos: BlockPos): Int

    protected fun getBlockStateFromCache(pos: BlockPos): BlockState {
        if (isOutOfBuildHeight(pos)) return Blocks.AIR.defaultBlockState()
        val packedChunkPos = ChunkPos.asLong(pos)
        val sections = sectionCache[packedChunkPos] ?: return Blocks.AIR.defaultBlockState()
        markTouchedChunk(packedChunkPos)
        val sectionIndex = (pos.y - level.minBuildHeight) / 16
        if (sectionIndex !in sections.indices) return Blocks.AIR.defaultBlockState()
        val section = sections[sectionIndex] ?: return Blocks.AIR.defaultBlockState()

        if (section.hasOnlyAir()) return Blocks.AIR.defaultBlockState()
        return section.getBlockState(((pos.x % 16) + 16) % 16, ((pos.y % 16) + 16) % 16, ((pos.z % 16) + 16) % 16)
    }

    abstract fun checkBlock(pos: BlockPos)

    fun processHeatChanges(changedPositions: Set<BlockPos>): ProcessResult {
        setupCache()

        if (changedPositions.isNotEmpty()) {
            for (pos in changedPositions) {
                checkBlock(pos)
            }
            performDecrease()
        }

        for (packed in markChangedChunk.iterator()) {
            updateStorage(storageCache[packed] ?: continue)
        }

        val updates = markChangedChunk.associateWith { packed -> storageCache[packed]!! }
        val accessed = linkedSetOf<Long>().apply {
            addAll(touchedChunks)
            addAll(updates.keys)
        }
        return ProcessResult(updates, accessed)
    }

    abstract fun performIncrease()

    abstract fun performDecrease()

    protected open fun updateStorage(storage: HeatStorage) {
        storage.update()
    }

    fun debugInfo() = """
        $this:
        --cache size--
        sectionCache: ${sectionCache.size}
        storageCache: ${storageCache.size}
        markChangedChunk: ${markChangedChunk.size}
        increasedQueue: ${increasedQueue.size()}
        decreasedQueue: ${decreasedQueue.size()}
        --------------
    """.trimIndent()
}
