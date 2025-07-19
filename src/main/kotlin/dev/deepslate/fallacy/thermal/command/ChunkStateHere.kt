package dev.deepslate.fallacy.thermal.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.deepslate.fallacy.thermal.ModAttachments
import dev.deepslate.fallacy.utils.command.GameCommand
import net.minecraft.commands.CommandSourceStack

data object ChunkStateHere : GameCommand {
    override val source: String = "fallacy thermal chunk_state_here"

    override val suggestions: Map<String, SuggestionProvider<CommandSourceStack>> = emptyMap()

    override val permissionRequired: String? = "fallacy.command.thermal.chunk_state_here"

    override fun execute(context: CommandContext<CommandSourceStack>): Int {
        if (context.source.player == null) return 0
        val player = context.source.player!!
        val blockPos = player.blockPosition()
        val chunk = player.level().getChunkAt(blockPos)
        val state = chunk.getData(ModAttachments.HEAT_PROCESS_STATE)
        context.source.sendSystemMessage(state.toComponent())

        return Command.SINGLE_SUCCESS
    }
}