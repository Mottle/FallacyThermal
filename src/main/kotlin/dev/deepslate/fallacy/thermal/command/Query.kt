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

data object Query : GameCommand {
    override val source: String = "fallacy thermal query %i<x> %i<y> %i<z>"

    override val suggestions: Map<String, SuggestionProvider<CommandSourceStack>> = emptyMap()

    override val permissionRequired: String? = "fallacy.command.thermal.query"

    override fun execute(context: CommandContext<CommandSourceStack>): Int {
        val x = IntegerArgumentType.getInteger(context, "x")
        val y = IntegerArgumentType.getInteger(context, "y")
        val z = IntegerArgumentType.getInteger(context, "z")
        val pos = BlockPos(x, y, z)
        val level = context.source.level ?: return 0
        val engine = ThermodynamicsEngine.getEngine(level)
        val heat = engine.getHeat(pos)

        context.source.sendSuccess({ Component.literal("Heat at $pos: $heat") }, false)

        return Command.SINGLE_SUCCESS
    }
}