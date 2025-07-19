package dev.deepslate.fallacy.thermal

import dev.deepslate.fallacy.thermal.block.BlockWithHeat
import dev.deepslate.fallacy.thermal.inject.BlockWithThermal
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

fun BlockState.isHeatSource(): Boolean = this.block is BlockWithHeat

fun BlockState.getEpitaxialHeat(level: Level, pos: BlockPos): Int =
    (block as BlockWithThermal).`fallacy$getEpitaxialHeat`(this, level, pos)

fun BlockState.getIntrinsicHeat(level: Level, pos: BlockPos): Int =
    (block as BlockWithThermal).`fallacy$getIntrinsicHeat`(this, level, pos)

fun BlockState.getThermalConductivity(level: Level, pos: BlockPos): Float =
    (block as BlockWithThermal).`fallacy$getThermalConductivity`(this, level, pos)