package dev.deepslate.fallacy.thermal

import dev.deepslate.fallacy.thermal.data.HeatStorage
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunkSection

//随用随销毁
abstract class HeatMaintainer(val engine: ThermodynamicsEngine) {

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

    //所有的key都是ChunkPos::toLong
    protected val chunkCache = Long2ObjectLinkedOpenHashMap<ChunkAccess>(BASE_CACHED_CHUNK_REGION_AREA)

    protected val sectionCache =
        Long2ObjectLinkedOpenHashMap<List<LevelChunkSection>>(BASE_CACHED_CHUNK_REGION_AREA * 16)

    protected val storageCache = Long2ObjectLinkedOpenHashMap<HeatStorage>(BASE_CACHED_CHUNK_REGION_AREA)

    protected val markChangedChunk = LongOpenHashSet(BASE_CACHED_CHUNK_REGION_LENGTH * 4)

    protected val increasedQueue: ObjectArrayFIFOQueue<PropagateTask> = ObjectArrayFIFOQueue(16 * 16 * 16)

    protected val decreasedQueue: ObjectArrayFIFOQueue<PropagateTask> = ObjectArrayFIFOQueue(16 * 16 * 16)

    protected fun isOutOfBuildHeight(pos: BlockPos): Boolean = pos.y !in level.minBuildHeight until level.maxBuildHeight

    protected fun getLoadedChunk(chunkX: Int, chunkZ: Int): ChunkAccess? =
        (level as? ServerLevel)?.chunkSource?.getChunkNow(chunkX, chunkZ)

    private fun setupCache(level: Level, centerChunkPos: ChunkPos) {
        val radius = 3

        for (dx in -radius..radius) for (dz in -radius..radius) {
            val chunk = getLoadedChunk(centerChunkPos.x + dx, centerChunkPos.z + dz) ?: continue
            val chunkPos = chunk.pos
            val packedChunkPos = chunkPos.toLong()
            chunkCache[packedChunkPos] = chunk
            sectionCache.computeIfAbsent(packedChunkPos) { chunk.sections.toList() }
            storageCache.computeIfAbsent(packedChunkPos) { query(chunk) }
        }
    }

    abstract fun query(chunk: ChunkAccess): HeatStorage

    fun freeCache() {
        chunkCache.clear()
        sectionCache.clear()
        storageCache.clear()

        chunkCache.trim(BASE_CACHED_CHUNK_REGION_AREA)
        sectionCache.trim(BASE_CACHED_CHUNK_REGION_AREA * 16)
        storageCache.trim(BASE_CACHED_CHUNK_REGION_AREA)
        markChangedChunk.trim(BASE_CACHED_CHUNK_REGION_LENGTH * 4)
    }

    abstract fun setHeat(pos: BlockPos, heat: Int)

    abstract fun getHeat(pos: BlockPos): Int

    protected fun getBlockStateFromCache(pos: BlockPos): BlockState {
        if (isOutOfBuildHeight(pos)) return Blocks.AIR.defaultBlockState()
        val sections = sectionCache[ChunkPos.asLong(pos)] ?: return Blocks.AIR.defaultBlockState()
        val sectionIndex = (pos.y - level.minBuildHeight) / 16
        if (sectionIndex !in sections.indices) return Blocks.AIR.defaultBlockState()
        val section = sections[sectionIndex]

        if (section.hasOnlyAir()) return Blocks.AIR.defaultBlockState()
        return section.getBlockState(((pos.x % 16) + 16) % 16, ((pos.y % 16) + 16) % 16, ((pos.z % 16) + 16) % 16)
    }

    abstract fun checkBlock(pos: BlockPos)

    fun processHeatChanges(chunkPos: ChunkPos, changedPositions: Set<BlockPos>) {
        try {
            setupCache(level, chunkPos)
            val packed = chunkPos.toLong()
            val chunk = chunkCache[packed] ?: return
            if (changedPositions.isNotEmpty()) propagateBlockChanges(chunk, changedPositions)

            for (packed in markChangedChunk.iterator()) {
                updateStorage(storageCache[packed] ?: continue)
                chunkCache[packed]?.isUnsaved = true
            }
        } catch (e: Exception) {
            TheMod.LOGGER.error(e)
            getLoadedChunk(chunkPos.x, chunkPos.z)?.setData(ModAttachments.HEAT_PROCESS_STATE, HeatProcessState.ERROR)
        }
//        markChangedChunk.clear()
    }

    private fun propagateBlockChanges(chunk: ChunkAccess, positions: Set<BlockPos>) {
        for (pos in positions) {
            checkBlock(pos)
        }

        performDecrease()
    }

    abstract fun performIncrease()

    abstract fun performDecrease()

    protected open fun updateStorage(storage: HeatStorage) {
        storage.update()
    }

    fun debugInfo() = """
        $this:
        --cache size--
        chunkCache: ${chunkCache.size}
        sectionCache: ${sectionCache.size}
        storageCache: ${storageCache.size}
        markChangedChunk: ${markChangedChunk.size}
        increasedQueue: ${increasedQueue.size()}
        decreasedQueue: ${decreasedQueue.size()}
        --------------
    """.trimIndent()
}
