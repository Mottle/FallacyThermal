package dev.deepslate.fallacy.thermal.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.deepslate.fallacy.thermal.ModAttachments
import dev.deepslate.fallacy.utils.command.GameCommand
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos

data object QueryState : GameCommand {
    override val source: String = "fallacy thermal query_state %i<x> %i<z>"

    override val suggestions: Map<String, SuggestionProvider<CommandSourceStack>> = emptyMap()

    override val permissionRequired: String = "fallacy.command.thermal.query_state"

    override fun execute(context: CommandContext<CommandSourceStack>): Int {
        val x = IntegerArgumentType.getInteger(context, "x")
        val z = IntegerArgumentType.getInteger(context, "z")

        val chunk = context.source.level.getChunk(BlockPos(x, 0, z)) ?: return 0
        val state = chunk.getData(ModAttachments.HEAT_PROCESS_STATE)
        context.source.sendSuccess({ state.toComponent() }, true)

        return Command.SINGLE_SUCCESS
    }
}