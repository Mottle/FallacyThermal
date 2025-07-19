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
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunkSection

abstract class HeatMaintainer(val engine: ThermodynamicsEngine) {

    protected enum class TaskType {
        NORM, RECHECKED, WRITE
    }

    protected data class PropagateTask(val pos: BlockPos, val propagateHeat: Int, val taskType: TaskType)

    val level: Level
        get() = engine.level

    //所有的key都是ChunkPos::toLong
    protected val chunkCache = Long2ObjectLinkedOpenHashMap<ChunkAccess>(7 * 7)

    protected val sectionCache = Long2ObjectLinkedOpenHashMap<List<LevelChunkSection>>(7 * 7 * 16)

    protected val storageCache = Long2ObjectLinkedOpenHashMap<HeatStorage>(7 * 7)

    protected val markChangedChunk = LongOpenHashSet(7 * 4)

    protected val increasedQueue: ObjectArrayFIFOQueue<PropagateTask> = ObjectArrayFIFOQueue(16 * 16 * 16)

    protected val decreasedQueue: ObjectArrayFIFOQueue<PropagateTask> = ObjectArrayFIFOQueue(16 * 16 * 16)

    private fun setupCache(level: Level, centerChunkPos: ChunkPos, cache: HeatStorageCache) {
        val radius = 3

        for (dx in -radius..radius) for (dz in -radius..radius) {
            val chunk = level.getChunk(centerChunkPos.x + dx, centerChunkPos.z + dz)
            val chunkPos = chunk.pos
            val packedChunkPos = chunkPos.toLong()
            chunkCache[packedChunkPos] = chunk
            sectionCache.computeIfAbsent(packedChunkPos) { chunk.sections.toList() }
            storageCache.computeIfAbsent(packedChunkPos) { query(chunkPos, cache) }
        }
    }

    abstract fun query(chunkPos: ChunkPos, cache: HeatStorageCache): HeatStorage

    fun freeCache() {
        chunkCache.clear()
        sectionCache.clear()
        storageCache.clear()

        chunkCache.trim(7 * 7)
        sectionCache.trim(7 * 7 * 16)
        storageCache.trim(7 * 7)
        markChangedChunk.trim(7 * 4)
    }

    abstract fun setHeat(pos: BlockPos, heat: Int)

    abstract fun getHeat(pos: BlockPos): Int

    protected fun getBlockStateFromCache(pos: BlockPos): BlockState {
        val sections = sectionCache[ChunkPos.asLong(pos)] ?: return Blocks.AIR.defaultBlockState()
        val sectionIndex = (pos.y - level.minBuildHeight) / 16
        val section = sections[sectionIndex]

        if (section.hasOnlyAir()) return Blocks.AIR.defaultBlockState()
        return section.getBlockState(((pos.x % 16) + 16) % 16, ((pos.y % 16) + 16) % 16, ((pos.z % 16) + 16) % 16)
    }

    abstract fun checkBlock(pos: BlockPos)

    fun processHeatChanges(chunkPos: ChunkPos, changedPositions: Set<BlockPos>) {
        try {
            setupCache(level, chunkPos, engine.cache)
            val packed = chunkPos.toLong()
            val chunk = chunkCache[packed] ?: return
            if (changedPositions.isNotEmpty()) propagateBlockChanges(chunk, changedPositions)

            for (packed in markChangedChunk.iterator()) {
                storageCache[packed]?.update()
                chunkCache[packed]?.isUnsaved = true
            }
        } catch (e: Exception) {
            TheMod.LOGGER.error(e)
            level.getChunk(chunkPos.x, chunkPos.z)
                .setData(ModAttachments.HEAT_PROCESS_STATE, HeatProcessState.ERROR)
        }
    }

    private fun propagateBlockChanges(chunk: ChunkAccess, positions: Set<BlockPos>) {
        for (pos in positions) {
            checkBlock(pos)
        }

        performDecrease()
    }

    abstract fun performIncrease()

    abstract fun performDecrease()
}