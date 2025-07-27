package dev.deepslate.fallacy.thermal

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

fun BlockState.isHeatSource(): Boolean = ThermodynamicsEngine.isHeatSource(this)

fun BlockState.getEpitaxialHeat(level: Level, pos: BlockPos): Int =
    ThermodynamicsEngine.getEpitaxialHeat(this, level, pos)

fun BlockState.getIntrinsicHeat(level: Level, pos: BlockPos): Int =
    ThermodynamicsEngine.getIntrinsicHeat(this, level, pos)

fun BlockState.getThermalConductivity(level: Level, pos: BlockPos): Float =
    ThermodynamicsEngine.getThermalConductivity(this, level, pos)