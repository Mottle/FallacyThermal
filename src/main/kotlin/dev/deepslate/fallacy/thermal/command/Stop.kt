package dev.deepslate.fallacy.thermal.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.deepslate.fallacy.thermal.impl.EnvironmentThermodynamicsEngine
import dev.deepslate.fallacy.utils.command.GameCommand
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

object Stop : GameCommand {
    override val source: String = "fallacy thermal stop"

    override val suggestions: Map<String, SuggestionProvider<CommandSourceStack>> = emptyMap()

    override val permissionRequired: String = "fallacy.command.thermal.stop"

    override fun execute(context: CommandContext<CommandSourceStack>): Int {
        EnvironmentThermodynamicsEngine.STOPED = !EnvironmentThermodynamicsEngine.STOPED
        context.source.sendSuccess(
            { Component.literal("Set Thermal Engine ${if (EnvironmentThermodynamicsEngine.STOPED) "stoped" else "started"}") },
            true
        )
        return Command.SINGLE_SUCCESS
    }
}