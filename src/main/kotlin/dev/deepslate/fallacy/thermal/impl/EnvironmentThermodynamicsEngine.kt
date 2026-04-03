package dev.deepslate.fallacy.thermal.impl

import dev.deepslate.fallacy.thermal.*
import dev.deepslate.fallacy.thermal.data.HeatProcessQueue
import dev.deepslate.fallacy.thermal.data.HeatStorage
import dev.deepslate.fallacy.thermal.datapack.BiomeHeatConfiguration
import dev.deepslate.fallacy.thermal.datapack.ModDatapacks
import dev.deepslate.fallacy.thermal.inject.ThermalExtension
import dev.deepslate.fallacy.utils.Worker
import dev.deepslate.fallacy.utils.datapack
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.chunk.LevelChunkSection
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.OnDatapackSyncEvent
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.math.max

open class EnvironmentThermodynamicsEngine(override val level: Level) : ThermodynamicsEngine(), HeatStorageCache {

    private data class TaskSnapshot(
        val chunks: Map<Long, HeatMaintainer.ChunkSnapshot>,
        val versions: Map<Long, Int>
    )

    private data class TaskRegion(
        val chunks: List<ChunkPos>,
        val packed: Set<Long>
    )

    companion object {
        private const val TASK_REGION_RADIUS = 3
        private val MAX_PARALLEL_TASKS = max(2, Runtime.getRuntime().availableProcessors().coerceAtMost(8))

        fun getEnvironmentEngineOrNull(level: ServerLevel): EnvironmentThermodynamicsEngine? =
            runCatching { (level as? ThermalExtension)?.`fallacy$getThermalEngine`() as? EnvironmentThermodynamicsEngine }.getOrNull()
    }

    private val heatQueue: HeatProcessQueue = HeatProcessQueue()

    private val chunkScanner = ChunkScanner(this, heatQueue)

    private val positiveHeatCache: MutableMap<Long, HeatStorage> = ConcurrentHashMap()

    private val negativeHeatCache: MutableMap<Long, HeatStorage> = ConcurrentHashMap()

    @Volatile
    private var stopped = false

    @Volatile
    private var closing = false

    private val activeTaskCount = AtomicInteger(0)

    private val regionLocks: MutableMap<Long, ReentrantReadWriteLock> = ConcurrentHashMap()

    private val reservedTaskRegions = ConcurrentSkipListSet<Long>()

    override val cache: HeatStorageCache = this

    fun toggleStopped(): Boolean {
        stopped = !stopped
        return stopped
    }

    fun isStopped(): Boolean = stopped

    fun stop() {
        closing = true
        chunkScanner.stop()

        record.map {
            val chunkPos = ChunkPos(it)
            getLoadedChunk(chunkPos.x, chunkPos.z)
        }.filterNotNull().forEach { chunkScanner.setProcessState(it, HeatProcessState.STERN) }

        heatQueue.snapshot().forEach {
            val chunk = getLoadedChunk(it.chunkPos.x, it.chunkPos.z) ?: return@forEach
            chunkScanner.setProcessState(chunk, HeatProcessState.STERN)
        }
    }

    val scanTaskCount: Int
        get() = chunkScanner.taskCount

    val maintainTaskCount: Int
        get() = activeTaskCount.get()

    val heatQueueSize: Int
        get() = heatQueue.size

    fun getLoadedChunk(chunkX: Int, chunkZ: Int) = (level as? ServerLevel)?.chunkSource?.getChunkNow(chunkX, chunkZ)

    fun bumpChunkVersion(chunkPos: ChunkPos): Int {
        val chunk = getLoadedChunk(chunkPos.x, chunkPos.z) ?: return 0
        val nextVersion = chunk.getData(ModAttachments.HEAT_TASK_VERSION) + 1
        chunk.setData(ModAttachments.HEAT_TASK_VERSION, nextVersion)
        return nextVersion
    }

    private fun getChunkVersion(chunkPos: ChunkPos): Int {
        val chunk = getLoadedChunk(chunkPos.x, chunkPos.z) ?: return -1
        return chunk.getData(ModAttachments.HEAT_TASK_VERSION)
    }

    private fun taskRegion(task: HeatProcessQueue.HeatTask): TaskRegion {
        val changedChunks = task.changedPosition
            .map(::ChunkPos)
            .ifEmpty { listOf(task.chunkPos) }

        val minX = changedChunks.minOf { it.x } - TASK_REGION_RADIUS
        val maxX = changedChunks.maxOf { it.x } + TASK_REGION_RADIUS
        val minZ = changedChunks.minOf { it.z } - TASK_REGION_RADIUS
        val maxZ = changedChunks.maxOf { it.z } + TASK_REGION_RADIUS

        val chunks = buildList((maxX - minX + 1) * (maxZ - minZ + 1)) {
            for (x in minX..maxX) {
                for (z in minZ..maxZ) {
                    add(ChunkPos(x, z))
                }
            }
        }
        return TaskRegion(chunks, chunks.mapTo(LinkedHashSet(chunks.size), ChunkPos::toLong))
    }

    private fun tryReserveTask(region: TaskRegion): Boolean {
        return synchronized(reservedTaskRegions) {
            if (region.packed.any(reservedTaskRegions::contains)) return false
            reservedTaskRegions.addAll(region.packed)
            true
        }
    }

    private fun releaseTask(region: TaskRegion) {
        reservedTaskRegions.removeAll(region.packed)
    }

    private fun getChunkLock(chunkPos: ChunkPos): ReentrantReadWriteLock =
        regionLocks.computeIfAbsent(chunkPos.toLong()) { ReentrantReadWriteLock() }

    private fun <T> withChunkReadLock(chunkPos: ChunkPos, action: () -> T): T =
        getChunkLock(chunkPos).read(action)

    private fun <T> withTaskWriteLocks(region: TaskRegion, action: () -> T): T {
        val locks = region.chunks
            .sortedWith(compareBy<ChunkPos> { it.x }.thenBy { it.z })
            .map { getChunkLock(it).writeLock() }

        locks.forEach { it.lock() }
        try {
            return action()
        } finally {
            locks.asReversed().forEach { it.unlock() }
        }
    }

    private fun snapshotSection(section: LevelChunkSection?): LevelChunkSection? =
        section?.let { LevelChunkSection(it.states.copy(), it.biomes) }

    private fun snapshotTask(region: TaskRegion): TaskSnapshot {
        val snapshots = mutableMapOf<Long, HeatMaintainer.ChunkSnapshot>()
        val versions = mutableMapOf<Long, Int>()

        region.chunks.forEach { chunkPos ->
            val chunk = getLoadedChunk(chunkPos.x, chunkPos.z) ?: return@forEach
            val packed = chunkPos.toLong()
            snapshots[packed] = HeatMaintainer.ChunkSnapshot(
                chunkPos = chunkPos,
                sections = chunk.sections.map(::snapshotSection),
                positiveHeat = chunk.getData(ModAttachments.POSITIVE_CHUNK_HEAT).copy(),
                negativeHeat = chunk.getData(ModAttachments.NEGATIVE_CHUNK_HEAT).copy()
            )
            versions[packed] = chunk.getData(ModAttachments.HEAT_TASK_VERSION)
        }

        return TaskSnapshot(snapshots, versions)
    }

    private fun versionsMatch(snapshot: TaskSnapshot): Boolean =
        snapshot.versions.all { (packed, version) ->
            getChunkVersion(ChunkPos(packed)) == version
        }

    private fun applyStorageUpdates(
        updates: Map<Long, HeatStorage>,
        attachment: net.neoforged.neoforge.registries.DeferredHolder<net.neoforged.neoforge.attachment.AttachmentType<*>, net.neoforged.neoforge.attachment.AttachmentType<HeatStorage>>,
        cache: MutableMap<Long, HeatStorage>
    ) {
        updates.forEach { (packed, storage) ->
            val chunkPos = ChunkPos(packed)
            val chunk = getLoadedChunk(chunkPos.x, chunkPos.z) ?: return@forEach
            chunk.setData(attachment, storage)
            chunk.isUnsaved = true
            cache[packed] = storage
        }
    }

    override fun queryPositive(chunkPos: ChunkPos): HeatStorage {
        val packed = chunkPos.toLong()
        val data = positiveHeatCache[packed] ?: return level.getChunk(chunkPos.x, chunkPos.z)
            .getData(ModAttachments.POSITIVE_CHUNK_HEAT)

        return data
    }

    override fun queryNegative(chunkPos: ChunkPos): HeatStorage {
        val packed = chunkPos.toLong()
        val data = negativeHeatCache[packed] ?: return level.getChunk(chunkPos.x, chunkPos.z)
            .getData(ModAttachments.NEGATIVE_CHUNK_HEAT)

        return data
    }

    private var biomeHeatCache: BiomeHeatConfiguration? = null

    fun invalidateBiomeHeatCache() {
        biomeHeatCache = null
    }

    fun getBiomeHeat(pos: BlockPos): Int {
        if (biomeHeatCache == null) biomeHeatCache =
            level.datapack(ModDatapacks.BIOME_HEAT_REGISTRY_KEY, BiomeHeatConfiguration.CONFIGURATION_KEY)
        val biomeKey = level.getBiome(pos).key?.location() ?: return BiomeHeatConfiguration.DEFAULT.heat
        return biomeHeatCache!!.query(biomeKey).heat
    }

    override fun getHeat(pos: BlockPos): Int {
        return withChunkReadLock(ChunkPos(pos)) {
            val packedChunkPos = ChunkPos.asLong(pos)
            val index = (pos.y - level.minBuildHeight) / 16

            val positiveHeatStorage = queryPositive(ChunkPos(packedChunkPos))
            val negativeHeatStorage = queryNegative(ChunkPos(packedChunkPos))

            val positiveHeat = positiveHeatStorage[index]?.getReadable(pos.x, pos.y, pos.z) ?: MIN_HEAT
            val negativeHeat = negativeHeatStorage[index]?.getReadable(pos.x, pos.y, pos.z) ?: MAX_HEAT
            val biomeHeat = getBiomeHeat(pos)
            val sunlightHeat = getSunlightHeatDelta(pos)
            val weatherHeat = getWeatherHeatDelta(pos)
            val dayNightCycleHeat = getDayNightCycleHeatDelta(pos)
            val environmentHeat = biomeHeat + sunlightHeat + weatherHeat + dayNightCycleHeat

            val positiveImpact = if (positiveHeat > environmentHeat) positiveHeat - environmentHeat else 0
            val negativeImpact = if (negativeHeat < environmentHeat) negativeHeat - environmentHeat else 0

            environmentHeat + positiveImpact + negativeImpact
        }
    }


    protected open fun getSunlightHeatDelta(pos: BlockPos): Int {
        if (level.isNight) return 0
        if (!level.canSeeSky(pos)) return 0

        val biome = level.getBiome(pos)

        if (biome.`is`(Biomes.DESERT)) return 25
        if (biome.`is`(Biomes.BADLANDS) || biome.`is`(Biomes.ERODED_BADLANDS) || biome.`is`(Biomes.WOODED_BADLANDS)) return 15

        return 5
    }

    protected open fun getDayNightCycleHeatDelta(pos: BlockPos): Int {
        if (level.getBiome(pos).`is`(Biomes.DESERT)) {
            return if (level.isDay) 0 else -55
        }

        return if (level.isDay) 0 else -3
    }

    protected open fun getWeatherHeatDelta(pos: BlockPos): Int = 0

    override fun checkBlock(pos: BlockPos) {
        if (pos.y !in level.minBuildHeight until level.maxBuildHeight) return
        bumpChunkVersion(ChunkPos(pos))
        heatQueue.enqueueBlockChange(pos)
    }

    override fun scanChunk(chunkPos: ChunkPos, force: Boolean) {
        val chunk = getLoadedChunk(chunkPos.x, chunkPos.z) ?: return
        if (!force) chunkScanner.enqueue(chunk) else chunkScanner.forceEnqueue(chunk)
    }

    override fun runUpdates() {
        if (stopped || closing) return
        propagateChanges()
    }

    @EventBusSubscriber(modid = TheMod.ID)
    object Handler {

        @SubscribeEvent
        fun onChunkLoad(event: ChunkEvent.Load) {
            if (event.level.isClientSide) return

            val level = event.level as ServerLevel
            val chunk = event.chunk
            val packed = chunk.pos.toLong()
            val positiveData = chunk.getData(ModAttachments.POSITIVE_CHUNK_HEAT)
            val negativeData = chunk.getData(ModAttachments.NEGATIVE_CHUNK_HEAT)
            val engine = getEnvironmentEngineOrNull(level)

            engine?.positiveHeatCache?.set(packed, positiveData)
            engine?.negativeHeatCache?.set(packed, negativeData)
        }

        @SubscribeEvent
        fun onChunkUnload(event: ChunkEvent.Unload) {
            if (event.level.isClientSide) return

            val level = event.level as ServerLevel
            val chunk = event.chunk
            val packed = chunk.pos.toLong()
            val engine = getEnvironmentEngineOrNull(level)

            engine?.positiveHeatCache?.remove(packed)
            engine?.negativeHeatCache?.remove(packed)
        }

//        private val playerPosMap: MutableMap<UUID, BlockPos> = mutableMapOf()
//
//        @SubscribeEvent
//        fun debug(event: PlayerTickEvent.Post) {
//            val player = event.entity as? ServerPlayer ?: return
//            val uuid = player.uuid
//            val pos = player.blockPosition()
//            if (playerPosMap[uuid] == pos) return
//            playerPosMap[uuid] = pos
//            val heat = getHeatAt(player)
//            val celsius = Temperature.celsius(heat)
//            player.sendSystemMessage(Component.literal("heat: $celsius"))
//        }

        @SubscribeEvent(priority = EventPriority.NORMAL)
        fun onServerStop(event: ServerStoppingEvent) {
            val levels = event.server.allLevels

            levels.forEach {
                val engine = getEnvironmentEngineOrNull(it) ?: return@forEach
                engine.stop()
            }
        }

        @SubscribeEvent
        fun onDatapackSync(event: OnDatapackSyncEvent) {
            event.playerList.server.allLevels.forEach {
                getEnvironmentEngineOrNull(it)?.invalidateBiomeHeatCache()
            }
        }
    }

    //记录正在处理的区块
    private val record = ConcurrentSkipListSet<Long>()

    fun propagateChanges() {
        if (closing) return
        if (heatQueue.empty) return

        val deferredTasks = mutableListOf<HeatProcessQueue.HeatTask>()

        while (!heatQueue.empty && activeTaskCount.get() < MAX_PARALLEL_TASKS) {
            val task = heatQueue.dequeue() ?: break
            val region = taskRegion(task)
            if (!tryReserveTask(region)) {
                deferredTasks.add(task)
                continue
            }
            val positions = task.changedPosition

            if (positions.isNotEmpty()) {
                record.add(task.chunkPos.toLong())
                activeTaskCount.incrementAndGet()
                val snapshots = snapshotTask(region)
                Worker.IO_POOL.execute taskRunner@{
                    if (closing) {
                        record.remove(task.chunkPos.toLong())
                        releaseTask(region)
                        activeTaskCount.decrementAndGet()
                        return@taskRunner
                    }
                    try {
                        val positiveUpdates =
                            PositiveHeatMaintainer(this, snapshots.chunks).processHeatChanges(task.changedPosition)
                        val negativeUpdates =
                            NegativeHeatMaintainer(this, snapshots.chunks).processHeatChanges(task.changedPosition)

                        (level as? ServerLevel)?.server?.execute {
                            try {
                                if (closing) return@execute
                                withTaskWriteLocks(region) {
                                    if (!versionsMatch(snapshots)) return@withTaskWriteLocks
                                    applyStorageUpdates(
                                        positiveUpdates,
                                        ModAttachments.POSITIVE_CHUNK_HEAT,
                                        positiveHeatCache
                                    )
                                    applyStorageUpdates(
                                        negativeUpdates,
                                        ModAttachments.NEGATIVE_CHUNK_HEAT,
                                        negativeHeatCache
                                    )

                                    if (task.initialized) {
                                        val chunk = getLoadedChunk(task.chunkPos.x, task.chunkPos.z)
                                        if (chunk != null) chunkScanner.setProcessState(
                                            chunk,
                                            HeatProcessState.CORRECTED
                                        )
                                    }
                                }
                            } finally {
                                record.remove(task.chunkPos.toLong())
                                releaseTask(region)
                                activeTaskCount.decrementAndGet()
                            }
                        }
                    } catch (e: Exception) {
                        TheMod.LOGGER.error(e)
                        (level as? ServerLevel)?.server?.execute {
                            getLoadedChunk(task.chunkPos.x, task.chunkPos.z)?.setData(
                                ModAttachments.HEAT_PROCESS_STATE,
                                HeatProcessState.ERROR
                            )
                            record.remove(task.chunkPos.toLong())
                            releaseTask(region)
                            activeTaskCount.decrementAndGet()
                        } ?: run {
                            record.remove(task.chunkPos.toLong())
                            releaseTask(region)
                            activeTaskCount.decrementAndGet()
                        }
                    }
                }
            } else {
                releaseTask(region)
                if (task.initialized) {
                    val chunk = getLoadedChunk(task.chunkPos.x, task.chunkPos.z)
                    if (chunk != null) chunkScanner.setProcessState(chunk, HeatProcessState.CORRECTED)
                }
            }
        }

        deferredTasks.forEach { deferred ->
            heatQueue.enqueueAll(deferred.chunkPos, deferred.changedPosition, deferred.initialized)
        }
    }
}
