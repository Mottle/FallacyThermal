package dev.deepslate.fallacy.thermal.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.deepslate.fallacy.thermal.ModAttachments
import dev.deepslate.fallacy.utils.command.GameCommand
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

data object ChunkState : GameCommand {
    override val source: String = "fallacy thermal chunk_state %i<x> %i<z>"

    override val suggestions: Map<String, SuggestionProvider<CommandSourceStack>> = emptyMap()

    override val permissionRequired: String = "fallacy.command.thermal.chunk_state"

    override fun execute(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.player ?: return 0
        val x = context.getArgument("x", Int::class.java)
        val z = context.getArgument("z", Int::class.java)
        val blockPos = BlockPos(x, 0, z)
        val chunk = player.level().getChunkAt(blockPos)
        val state = chunk.getData(ModAttachments.HEAT_PROCESS_STATE)
        val component =
            Component.literal("Chunk state at $blockPos: ").withStyle(ChatFormatting.AQUA)
                .withStyle(ChatFormatting.ITALIC)
                .append(state.toComponent())

        context.source.sendSystemMessage(component)
        return Command.SINGLE_SUCCESS
    }
}