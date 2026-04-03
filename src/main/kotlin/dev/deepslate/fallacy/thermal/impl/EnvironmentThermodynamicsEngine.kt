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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.math.max

open class EnvironmentThermodynamicsEngine(override val level: Level) : ThermodynamicsEngine(), HeatStorageCache {

    private data class TaskSnapshot(
        val chunks: Map<Long, HeatMaintainer.ChunkSnapshot>,
        val versions: Map<Long, Int>
    )

    private data class CachedChunkSnapshot(
        val sections: List<LevelChunkSection?>,
        val positiveHeat: HeatStorage,
        val negativeHeat: HeatStorage,
        val version: Int
    )

    private data class TaskRegion(
        val chunks: List<ChunkPos>,
        val packed: Set<Long>,
        val minX: Int,
        val maxX: Int,
        val minZ: Int,
        val maxZ: Int
    ) {
        fun overlaps(other: TaskRegion): Boolean =
            minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ
    }

    private data class ScheduledTask(
        val sourceTasks: List<HeatProcessQueue.HeatTask>,
        val primaryChunk: ChunkPos,
        val region: TaskRegion,
        val changedPositions: Set<BlockPos>,
        val initializedChunks: Set<Long>
    )

    private data class ReservedRegion(
        val id: Long,
        val region: TaskRegion
    )

    companion object {
        private const val TASK_REGION_RADIUS = 3
        private const val MERGE_SCAN_LIMIT = 8
        private val MAX_PARALLEL_TASKS = max(2, Runtime.getRuntime().availableProcessors().coerceAtMost(8))
        private const val REGION_LOCK_STRIPES = 4096

        fun getEnvironmentEngineOrNull(level: ServerLevel): EnvironmentThermodynamicsEngine? =
            runCatching { (level as? ThermalExtension)?.`fallacy$getThermalEngine`() as? EnvironmentThermodynamicsEngine }.getOrNull()
    }

    private val heatQueue: HeatProcessQueue = HeatProcessQueue()

    private val chunkScanner = ChunkScanner(this, heatQueue)

    private val positiveHeatCache: MutableMap<Long, HeatStorage> = ConcurrentHashMap()

    private val negativeHeatCache: MutableMap<Long, HeatStorage> = ConcurrentHashMap()

    private fun newDefaultHeatStorage(): HeatStorage {
        val size = (level.maxBuildHeight - level.minBuildHeight) / 16
        return HeatStorage(Array(size) { null })
    }

    private val defaultPositiveHeatStorage: HeatStorage = newDefaultHeatStorage()

    private val defaultNegativeHeatStorage: HeatStorage = newDefaultHeatStorage()

    @Volatile
    private var stopped = false

    @Volatile
    private var closing = false

    private val activeTaskCount = AtomicInteger(0)

    private val regionLocks: Array<ReentrantReadWriteLock> = Array(REGION_LOCK_STRIPES) { ReentrantReadWriteLock() }

    private val reservedRegionIdGenerator = AtomicLong(0)
    private val reservedTaskRegions: MutableMap<Long, TaskRegion> = ConcurrentHashMap()

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
        return taskRegionFromChunks(changedChunks)
    }

    private fun taskRegionFromChunks(changedChunks: Collection<ChunkPos>): TaskRegion {
        val minChunkX = changedChunks.minOf { it.x }
        val maxChunkX = changedChunks.maxOf { it.x }
        val minChunkZ = changedChunks.minOf { it.z }
        val maxChunkZ = changedChunks.maxOf { it.z }

        val minX = minChunkX - TASK_REGION_RADIUS
        val maxX = maxChunkX + TASK_REGION_RADIUS
        val minZ = minChunkZ - TASK_REGION_RADIUS
        val maxZ = maxChunkZ + TASK_REGION_RADIUS

        val chunks = buildList((maxX - minX + 1) * (maxZ - minZ + 1)) {
            for (x in minX..maxX) {
                for (z in minZ..maxZ) {
                    add(ChunkPos(x, z))
                }
            }
        }
        return TaskRegion(
            chunks = chunks,
            packed = chunks.mapTo(LinkedHashSet(chunks.size), ChunkPos::toLong),
            minX = minX,
            maxX = maxX,
            minZ = minZ,
            maxZ = maxZ
        )
    }

    private fun tryReserveTask(region: TaskRegion): ReservedRegion? {
        return synchronized(reservedTaskRegions) {
            if (reservedTaskRegions.values.any { it.overlaps(region) }) return null
            val id = reservedRegionIdGenerator.incrementAndGet()
            reservedTaskRegions[id] = region
            ReservedRegion(id, region)
        }
    }

    private fun releaseTask(region: ReservedRegion) {
        synchronized(reservedTaskRegions) {
            reservedTaskRegions.remove(region.id)
        }
    }

    private fun getChunkLockStripeIndex(chunkPos: ChunkPos): Int {
        val packed = chunkPos.toLong()
        val mixed = packed xor (packed ushr 33) xor (packed ushr 17)
        return ((mixed and Long.MAX_VALUE) % REGION_LOCK_STRIPES.toLong()).toInt()
    }

    private fun getChunkLock(chunkPos: ChunkPos): ReentrantReadWriteLock {
        val index = getChunkLockStripeIndex(chunkPos)
        return regionLocks[index]
    }

    private fun <T> withChunkReadLock(chunkPos: ChunkPos, action: () -> T): T =
        getChunkLock(chunkPos).read(action)

    private fun <T> withTaskWriteLocks(region: TaskRegion, action: () -> T): T {
        val locks = region.chunks
            .asSequence()
            .map(::getChunkLockStripeIndex)
            .distinct()
            .sorted()
            .map { regionLocks[it].writeLock() }
            .toList()

        locks.forEach { it.lock() }
        try {
            return action()
        } finally {
            locks.asReversed().forEach { it.unlock() }
        }
    }

    private fun snapshotSection(section: LevelChunkSection?): LevelChunkSection? =
        section?.let { LevelChunkSection(it.states.copy(), it.biomes) }

    private fun snapshotTask(
        region: TaskRegion,
        reusableSnapshots: MutableMap<Long, CachedChunkSnapshot>
    ): TaskSnapshot {
        val snapshots = mutableMapOf<Long, HeatMaintainer.ChunkSnapshot>()
        val versions = mutableMapOf<Long, Int>()

        region.chunks.forEach { chunkPos ->
            val packed = chunkPos.toLong()
            val cachedSnapshot = reusableSnapshots[packed] ?: run {
                val chunk = getLoadedChunk(chunkPos.x, chunkPos.z) ?: return@forEach
                CachedChunkSnapshot(
                    sections = chunk.sections.map(::snapshotSection),
                    positiveHeat = chunk.getData(ModAttachments.POSITIVE_CHUNK_HEAT).copy(),
                    negativeHeat = chunk.getData(ModAttachments.NEGATIVE_CHUNK_HEAT).copy(),
                    version = chunk.getData(ModAttachments.HEAT_TASK_VERSION)
                ).also { reusableSnapshots[packed] = it }
            }
            snapshots[packed] = HeatMaintainer.ChunkSnapshot(
                chunkPos = chunkPos,
                sections = cachedSnapshot.sections,
                positiveHeat = cachedSnapshot.positiveHeat.copy(),
                negativeHeat = cachedSnapshot.negativeHeat.copy()
            )
            versions[packed] = cachedSnapshot.version
        }

        return TaskSnapshot(snapshots, versions)
    }

    private fun versionsMatch(snapshot: TaskSnapshot, touchedChunks: Set<Long>): Boolean =
        touchedChunks.all { packed ->
            val version = snapshot.versions[packed] ?: return@all false
            getChunkVersion(ChunkPos(packed)) == version
        }

    private fun changedChunks(task: HeatProcessQueue.HeatTask): Set<ChunkPos> =
        task.changedPosition
            .mapTo(LinkedHashSet()) { ChunkPos(it) }
            .ifEmpty { linkedSetOf(task.chunkPos) }

    private fun tryMergeTaskBatch(seed: HeatProcessQueue.HeatTask): ScheduledTask {
        val sourceTasks = mutableListOf(seed)
        val scannedButNotMerged = mutableListOf<HeatProcessQueue.HeatTask>()
        val mergedChangedChunks = changedChunks(seed).toMutableSet()
        val mergedPositions = seed.changedPosition.toMutableSet()
        val initializedChunks =
            linkedSetOf<Long>().also { if (seed.initialized) it.add(seed.chunkPos.toLong()) }
        var mergedRegion = taskRegionFromChunks(mergedChangedChunks)

        repeat(MERGE_SCAN_LIMIT) {
            val next = heatQueue.dequeue() ?: return@repeat
            val nextRegion = taskRegion(next)
            if (mergedRegion.overlaps(nextRegion)) {
                sourceTasks.add(next)
                mergedChangedChunks += changedChunks(next)
                mergedPositions += next.changedPosition
                if (next.initialized) initializedChunks.add(next.chunkPos.toLong())
                mergedRegion = taskRegionFromChunks(mergedChangedChunks)
            } else {
                scannedButNotMerged.add(next)
            }
        }

        scannedButNotMerged.forEach {
            heatQueue.enqueueAll(it.chunkPos, it.changedPosition, it.initialized)
        }

        return ScheduledTask(
            sourceTasks = sourceTasks,
            primaryChunk = seed.chunkPos,
            region = mergedRegion,
            changedPositions = mergedPositions,
            initializedChunks = initializedChunks
        )
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

    private fun requeueSourceTasks(sourceTasks: Collection<HeatProcessQueue.HeatTask>) {
        sourceTasks.forEach { source ->
            heatQueue.enqueueAll(source.chunkPos, source.changedPosition, source.initialized)
        }
    }

    private fun setInitializedChunkState(chunks: Set<Long>, state: HeatProcessState) {
        chunks.forEach { packed ->
            val chunkPos = ChunkPos(packed)
            val chunk = getLoadedChunk(chunkPos.x, chunkPos.z) ?: return@forEach
            chunkScanner.setProcessState(chunk, state)
        }
    }

    override fun queryPositive(chunkPos: ChunkPos): HeatStorage {
        val packed = chunkPos.toLong()
        positiveHeatCache[packed]?.let { return it }
        getLoadedChunk(chunkPos.x, chunkPos.z)?.let { return it.getData(ModAttachments.POSITIVE_CHUNK_HEAT) }

        val server = (level as? ServerLevel)?.server
        if (server != null && server.isSameThread) {
            return level.getChunk(chunkPos.x, chunkPos.z).getData(ModAttachments.POSITIVE_CHUNK_HEAT)
        }

        return defaultPositiveHeatStorage
    }

    override fun queryNegative(chunkPos: ChunkPos): HeatStorage {
        val packed = chunkPos.toLong()
        negativeHeatCache[packed]?.let { return it }
        getLoadedChunk(chunkPos.x, chunkPos.z)?.let { return it.getData(ModAttachments.NEGATIVE_CHUNK_HEAT) }

        val server = (level as? ServerLevel)?.server
        if (server != null && server.isSameThread) {
            return level.getChunk(chunkPos.x, chunkPos.z).getData(ModAttachments.NEGATIVE_CHUNK_HEAT)
        }

        return defaultNegativeHeatStorage
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
            if (engine != null) {
                val state = chunk.getData(ModAttachments.HEAT_PROCESS_STATE)
                if (
                    state == HeatProcessState.PENDING &&
                    !engine.record.contains(packed) &&
                    !engine.chunkScanner.isInFlight(chunk.pos) &&
                    !engine.heatQueue.contains(chunk.pos)
                ) {
                    engine.chunkScanner.setProcessState(chunk, HeatProcessState.UNPROCESSED)
                }
            }
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
        val reusableSnapshots = mutableMapOf<Long, CachedChunkSnapshot>()

        while (!heatQueue.empty && activeTaskCount.get() < MAX_PARALLEL_TASKS) {
            val seedTask = heatQueue.dequeue() ?: break
            val task = tryMergeTaskBatch(seedTask)
            val reservedRegion = tryReserveTask(task.region)
            if (reservedRegion == null) {
                deferredTasks += task.sourceTasks
                continue
            }
            val trackedChunks = linkedSetOf(task.primaryChunk.toLong()).apply {
                addAll(task.initializedChunks)
            }
            val positions = task.changedPositions

            if (positions.isNotEmpty()) {
                record.addAll(trackedChunks)
                activeTaskCount.incrementAndGet()
                val snapshots = snapshotTask(reservedRegion.region, reusableSnapshots)
                Worker.IO_POOL.execute taskRunner@{
                    if (closing) {
                        record.removeAll(trackedChunks)
                        releaseTask(reservedRegion)
                        activeTaskCount.decrementAndGet()
                        return@taskRunner
                    }
                    try {
                        val positiveResult =
                            PositiveHeatMaintainer(this, snapshots.chunks).processHeatChanges(task.changedPositions)
                        val negativeResult =
                            NegativeHeatMaintainer(this, snapshots.chunks).processHeatChanges(task.changedPositions)
                        val touchedChunks = HashSet<Long>(
                            positiveResult.touchedChunks.size + negativeResult.touchedChunks.size
                        ).apply {
                            addAll(positiveResult.touchedChunks)
                            addAll(negativeResult.touchedChunks)
                        }

                        (level as? ServerLevel)?.server?.execute {
                            try {
                                if (closing) return@execute
                                withTaskWriteLocks(reservedRegion.region) {
                                    if (!versionsMatch(snapshots, touchedChunks)) {
                                        requeueSourceTasks(task.sourceTasks)
                                        setInitializedChunkState(task.initializedChunks, HeatProcessState.UNPROCESSED)
                                        return@withTaskWriteLocks
                                    }
                                    applyStorageUpdates(
                                        positiveResult.updates,
                                        ModAttachments.POSITIVE_CHUNK_HEAT,
                                        positiveHeatCache
                                    )
                                    applyStorageUpdates(
                                        negativeResult.updates,
                                        ModAttachments.NEGATIVE_CHUNK_HEAT,
                                        negativeHeatCache
                                    )

                                    setInitializedChunkState(task.initializedChunks, HeatProcessState.CORRECTED)
                                }
                            } finally {
                                record.removeAll(trackedChunks)
                                releaseTask(reservedRegion)
                                activeTaskCount.decrementAndGet()
                            }
                        }
                    } catch (e: Exception) {
                        TheMod.LOGGER.error(e)
                        (level as? ServerLevel)?.server?.execute {
                            task.initializedChunks.forEach { packed ->
                                val chunkPos = ChunkPos(packed)
                                getLoadedChunk(chunkPos.x, chunkPos.z)?.setData(
                                    ModAttachments.HEAT_PROCESS_STATE,
                                    HeatProcessState.ERROR
                                )
                            }
                            record.removeAll(trackedChunks)
                            releaseTask(reservedRegion)
                            activeTaskCount.decrementAndGet()
                        } ?: run {
                            record.removeAll(trackedChunks)
                            releaseTask(reservedRegion)
                            activeTaskCount.decrementAndGet()
                        }
                    }
                }
            } else {
                releaseTask(reservedRegion)
                setInitializedChunkState(task.initializedChunks, HeatProcessState.CORRECTED)
            }
        }

        deferredTasks.forEach { deferred ->
            heatQueue.enqueueAll(deferred.chunkPos, deferred.changedPosition, deferred.initialized)
        }
    }
}
