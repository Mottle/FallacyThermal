package dev.deepslate.fallacy.thermal.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.deepslate.fallacy.thermal.impl.EnvironmentThermodynamicsEngine
import dev.deepslate.fallacy.utils.command.GameCommand
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

data object EngineState : GameCommand {
    override val source: String = "fallacy thermal engine_state"

    override val suggestions: Map<String, SuggestionProvider<CommandSourceStack>> = emptyMap()

    override val permissionRequired: String = "fallacy.command.thermal.engine_state"

    override fun execute(context: CommandContext<CommandSourceStack>): Int {
        val level = context.source.level
        val engine = EnvironmentThermodynamicsEngine.getEnvironmentEngineOrNull(level) ?: return 0
        val c1 = Component.literal("state: ").withStyle(ChatFormatting.ITALIC).withStyle(ChatFormatting.AQUA)
        if (EnvironmentThermodynamicsEngine.STOPPED) c1.append(
            Component.literal("STOPPED\n").withStyle(ChatFormatting.RED)
                .withStyle(ChatFormatting.BOLD)
        )
        else c1.append(Component.literal("RUNNING\n").withStyle(ChatFormatting.GREEN).withStyle(ChatFormatting.BOLD))

        context.source.sendSystemMessage(c1.append(Component.literal("scan task size: ${engine.scanTaskCount}, update task size: ${engine.maintainTaskCount}")))

        return Command.SINGLE_SUCCESS
    }
}