package dev.deepslate.fallacy.thermal.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.deepslate.fallacy.thermal.ThermodynamicsEngine
import dev.deepslate.fallacy.utils.command.GameCommand
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import java.util.concurrent.CompletableFuture

data object RegionCheck : GameCommand {
    override val source: String = "fallacy thermal region_check %i<x1> %i<z1> %i<x2> %i<z2>"

    override val suggestions: Map<String, SuggestionProvider<CommandSourceStack>> = emptyMap()

    override val permissionRequired: String? = "fallacy.command.thermal.region_check"

    private var running: Boolean = false

    override fun execute(context: CommandContext<CommandSourceStack>): Int {
        if (running) {
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
            context.source.sendFailure(Component.literal("Invalid coordinates!"))
            return 0
        }

        CompletableFuture.runAsync {
            running = true
            val size = (endChunkPos.x - startChunkPos.x + 1) * (endChunkPos.z - startChunkPos.z + 1)
            var progress = 0
            var lastRatio = 0

            for (x in startChunkPos.x..endChunkPos.x) for (z in startChunkPos.z..endChunkPos.z) {
                val chunkPos = ChunkPos(x, z)

                engine.scanChunk(chunkPos)
                ++progress

                val ratio = ((progress.toFloat() / size.toFloat()) * 100f).toInt()

                if (ratio > lastRatio) {
                    lastRatio = ratio
                    context.source.sendSuccess({ Component.literal("Scanning... $ratio%") }, true)
                }
            }

            context.source.sendSuccess({ Component.literal("Scan successful.") }, true)
            running = false
        }

        return Command.SINGLE_SUCCESS
    }
}