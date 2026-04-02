package dev.deepslate.fallacy.thermal.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.deepslate.fallacy.thermal.TheMod
import dev.deepslate.fallacy.thermal.ThermodynamicsEngine
import dev.deepslate.fallacy.utils.command.GameCommand
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.neoforged.neoforge.event.tick.LevelTickEvent
import java.util.concurrent.atomic.AtomicBoolean

@EventBusSubscriber(modid = TheMod.ID)
data object RegionCheck : GameCommand {
    private const val CHUNKS_PER_TICK = 32

    private data class ScanTask(
        val source: CommandSourceStack,
        val level: ServerLevel,
        val engine: ThermodynamicsEngine,
        val startChunkPos: ChunkPos,
        val endChunkPos: ChunkPos,
        val total: Int,
        var nextX: Int,
        var nextZ: Int,
        var progress: Int = 0,
        var lastRatio: Int = -1
    )

    override val source: String = "fallacy thermal region_check %i<x1> %i<z1> %i<x2> %i<z2>"

    override val suggestions: Map<String, SuggestionProvider<CommandSourceStack>> = emptyMap()

    override val permissionRequired: String = "fallacy.command.thermal.region_check"

    private val running = AtomicBoolean(false)
    private var activeTask: ScanTask? = null

    override fun execute(context: CommandContext<CommandSourceStack>): Int {
        if (!running.compareAndSet(false, true)) {
            context.source.sendFailure(Component.literal("Already running!"))
            return 0
        }

        val x1 = IntegerArgumentType.getInteger(context, "x1")
        val z1 = IntegerArgumentType.getInteger(context, "z1")
        val x2 = IntegerArgumentType.getInteger(context, "x2")
        val z2 = IntegerArgumentType.getInteger(context, "z2")
        val level = context.source.player?.level() as? ServerLevel ?: return 0
        val engine = ThermodynamicsEngine.getEngine(level)

        val startChunkPos = ChunkPos(BlockPos(x1, 0, z1))
        val endChunkPos = ChunkPos(BlockPos(x2, 0, z2))

        if (x1 > x2 || z1 > z2) {
            running.set(false)
            context.source.sendFailure(Component.literal("Invalid coordinates!"))
            return 0
        }

        val total = (endChunkPos.x - startChunkPos.x + 1) * (endChunkPos.z - startChunkPos.z + 1)
        activeTask = ScanTask(
            source = context.source,
            level = level,
            engine = engine,
            startChunkPos = startChunkPos,
            endChunkPos = endChunkPos,
            total = total,
            nextX = startChunkPos.x,
            nextZ = startChunkPos.z
        )
        context.source.sendSuccess({ Component.literal("Started scanning $total chunks.") }, true)

        return Command.SINGLE_SUCCESS
    }

    @SubscribeEvent
    fun onLevelTick(event: LevelTickEvent.Post) {
        val task = activeTask ?: return
        val level = event.level as? ServerLevel ?: return
        if (level != task.level) return

        var processed = 0
        while (processed < CHUNKS_PER_TICK && task.nextX <= task.endChunkPos.x) {
            task.engine.scanChunk(ChunkPos(task.nextX, task.nextZ), true)
            task.progress++
            processed++

            if (task.nextZ < task.endChunkPos.z) {
                task.nextZ++
            } else {
                task.nextZ = task.startChunkPos.z
                task.nextX++
            }
        }

        val ratio = ((task.progress.toFloat() / task.total.toFloat()) * 100f).toInt()
        if (ratio >= task.lastRatio + 5 || task.progress == task.total) {
            task.lastRatio = ratio
            task.source.sendSuccess({ Component.literal("Scanning... $ratio%") }, true)
        }

        if (task.progress >= task.total) {
            task.source.sendSuccess({ Component.literal("Scan successful.") }, true)
            activeTask = null
            running.set(false)
        }
    }
}
