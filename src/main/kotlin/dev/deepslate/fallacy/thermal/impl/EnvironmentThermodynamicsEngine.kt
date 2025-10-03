package dev.deepslate.fallacy.thermal.impl

import dev.deepslate.fallacy.thermal.*
import dev.deepslate.fallacy.thermal.data.HeatProcessQueue
import dev.deepslate.fallacy.thermal.data.HeatStorage
import dev.deepslate.fallacy.thermal.datapack.BiomeHeatConfiguration
import dev.deepslate.fallacy.thermal.datapack.ModDatapacks
import dev.deepslate.fallacy.thermal.inject.ThermalExtension
import dev.deepslate.fallacy.utils.Worker
import dev.deepslate.fallacy.utils.datapack
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.thread.ProcessorMailbox
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biomes
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentSkipListSet

open class EnvironmentThermodynamicsEngine(override val level: Level) : ThermodynamicsEngine(), HeatStorageCache {

    companion object {
        fun getEnvironmentEngineOrNull(level: ServerLevel): EnvironmentThermodynamicsEngine? =
            (level as? ThermalExtension)?.`fallacy$getThermalEngine`() as? EnvironmentThermodynamicsEngine

        var STOPPED = false
    }

    private val heatQueue: HeatProcessQueue = HeatProcessQueue()

    private val chunkScanner = ChunkScanner(this, heatQueue)

    private val positiveHeatCache: Long2ObjectOpenHashMap<WeakReference<HeatStorage>> = Long2ObjectOpenHashMap()

    private val negativeHeatCache: Long2ObjectOpenHashMap<WeakReference<HeatStorage>> = Long2ObjectOpenHashMap()

    override val cache: HeatStorageCache = this

    fun stop() {
        chunkScanner.stop()
        mailbox.close()

        record.map {
            val chunkPos = ChunkPos(it)
            level.getChunk(chunkPos.x, chunkPos.z)
        }.forEach { chunkScanner.setProcessState(it, HeatProcessState.STERN) }

        heatQueue.forEach {
            val chunk = level.getChunk(it.chunkPos.worldPosition)
            chunkScanner.setProcessState(chunk, HeatProcessState.STERN)
        }
    }

    val scanTaskCount: Int
        get() = chunkScanner.taskCount

    val maintainTaskCount: Int
        get() = mailbox.size()

    val heatQueueSize: Int
        get() = heatQueue.size

    override fun queryPositive(chunkPos: ChunkPos): HeatStorage {
        val packed = chunkPos.toLong()
        val data = positiveHeatCache[packed]?.get() ?: return level.getChunk(chunkPos.x, chunkPos.z)
            .getData(ModAttachments.POSITIVE_CHUNK_HEAT)

        return data
    }

    override fun queryNegative(chunkPos: ChunkPos): HeatStorage {
        val packed = chunkPos.toLong()
        val data = negativeHeatCache[packed]?.get() ?: return level.getChunk(chunkPos.x, chunkPos.z)
            .getData(ModAttachments.NEGATIVE_CHUNK_HEAT)

        return data
    }

    private var biomeHeatCache: BiomeHeatConfiguration? = null

    fun getBiomeHeat(pos: BlockPos): Int {
        if (biomeHeatCache == null) biomeHeatCache =
            level.datapack(ModDatapacks.BIOME_HEAT_REGISTRY_KEY, BiomeHeatConfiguration.CONFIGURATION_KEY)
        val biomeKey = level.getBiome(pos).key?.location() ?: return BiomeHeatConfiguration.DEFAULT.heat
        return biomeHeatCache!!.query(biomeKey).heat
    }

    override fun getHeat(pos: BlockPos): Int {
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

        val finalHeat = environmentHeat + positiveImpact + negativeImpact

        return finalHeat
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
        if (pos.y !in level.minBuildHeight..level.maxBuildHeight) return
        heatQueue.enqueueBlockChange(pos)
    }

    override fun scanChunk(chunkPos: ChunkPos, force: Boolean) {
        val chunk = level.getChunk(chunkPos.x, chunkPos.z)
        if (!force) chunkScanner.enqueue(chunk) else chunkScanner.forceEnqueue(chunk)
    }

    override fun runUpdates() {
        if (STOPPED) return
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

            engine?.positiveHeatCache[packed] = WeakReference(positiveData)
            engine?.negativeHeatCache[packed] = WeakReference(negativeData)
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
    }

    private val mailbox = ProcessorMailbox.create(Worker.IO_POOL, "fallacy-thermodynamics-process")

    //记录正在处理的区块
    private val record = ConcurrentSkipListSet<Long>()

    fun propagateChanges() {
        if (heatQueue.empty) return
        if (mailbox.size() > 200) return

        while (!heatQueue.empty) {
            val task = heatQueue.dequeue() ?: break
            val positions = task.changedPosition

            if (positions.isNotEmpty()) {
                record.add(task.chunkPos.toLong())
                mailbox.tell {
                    PositiveHeatMaintainer(this).processHeatChanges(task.chunkPos, task.changedPosition)
                    NegativeHeatMaintainer(this).processHeatChanges(task.chunkPos, task.changedPosition)

                    if (task.initialized) {
                        val chunk = level.getChunk(task.chunkPos.x, task.chunkPos.z)
                        chunkScanner.setProcessState(chunk, HeatProcessState.CORRECTED)
                    }

                    record.remove(task.chunkPos.toLong())
                }
            } else {
                if (task.initialized) {
                    val chunk = level.getChunk(task.chunkPos.x, task.chunkPos.z)

                    chunkScanner.setProcessState(chunk, HeatProcessState.CORRECTED)
                }
            }
        }
    }
}