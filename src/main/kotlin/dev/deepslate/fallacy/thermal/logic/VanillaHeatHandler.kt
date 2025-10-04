package dev.deepslate.fallacy.thermal.logic

import dev.deepslate.fallacy.thermal.TheMod
import dev.deepslate.fallacy.thermal.ThermodynamicsEngine
import dev.deepslate.fallacy.thermal.block.BlockWithHeat
import dev.deepslate.fallacy.thermal.inject.BlockWithThermal
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent

@EventBusSubscriber(modid = TheMod.ID)
object VanillaHeatHandler {
    private val intrinsicHeatSet = setOf(
        Blocks.LAVA,
        Blocks.LAVA_CAULDRON,
        Blocks.MAGMA_BLOCK,
        Blocks.FIRE,
        Blocks.SOUL_FIRE,
        Blocks.CAMPFIRE,
        Blocks.SOUL_CAMPFIRE,

        Blocks.SNOW,
        Blocks.SNOW_BLOCK,
        Blocks.ICE,
        Blocks.BLUE_ICE,
        Blocks.PACKED_ICE,
        Blocks.POWDER_SNOW,
        Blocks.POWDER_SNOW_CAULDRON,
    )

    private fun getIntrinsicHeat(state: BlockState): Int {
        val block = state.block
        if (block == Blocks.LAVA) return ThermodynamicsEngine.fromFreezingPoint(1300)
        if (block == Blocks.LAVA_CAULDRON) return ThermodynamicsEngine.fromFreezingPoint(1300)
        if (block == Blocks.MAGMA_BLOCK) return ThermodynamicsEngine.fromFreezingPoint(600)
        if (block == Blocks.FIRE) return ThermodynamicsEngine.fromFreezingPoint(340)
        if (block == Blocks.SOUL_FIRE) return ThermodynamicsEngine.fromFreezingPoint(680)
        if (block == Blocks.CAMPFIRE && state.getValue(CampfireBlock.LIT))
            return ThermodynamicsEngine.fromFreezingPoint(340)
        if (block == Blocks.SOUL_CAMPFIRE && state.getValue(CampfireBlock.LIT))
            return ThermodynamicsEngine.fromFreezingPoint(680)

        if (block == Blocks.SNOW) return ThermodynamicsEngine.fromFreezingPoint(-25)
        if (block == Blocks.SNOW_BLOCK) return ThermodynamicsEngine.fromFreezingPoint(-25)
        if (block == Blocks.FROSTED_ICE) return ThermodynamicsEngine.fromFreezingPoint(-25)
        if (block == Blocks.ICE) return ThermodynamicsEngine.fromFreezingPoint(-25)
        if (block == Blocks.PACKED_ICE) return ThermodynamicsEngine.fromFreezingPoint(-50)
        if (block == Blocks.BLUE_ICE) return ThermodynamicsEngine.fromFreezingPoint(-75)
        if (block == Blocks.POWDER_SNOW) return ThermodynamicsEngine.fromFreezingPoint(-25)
        if (block == Blocks.POWDER_SNOW_CAULDRON) return ThermodynamicsEngine.fromFreezingPoint(-25)

        return 0
    }

    private val epitaxialHeatSet = setOf(
        Blocks.LAVA,
        Blocks.LAVA_CAULDRON,
        Blocks.MAGMA_BLOCK,
        Blocks.FIRE,
        Blocks.SOUL_FIRE,
        Blocks.CAMPFIRE,
        Blocks.SOUL_CAMPFIRE
    )

    private fun getEpitaxialHeat(state: BlockState): Int {
        val block = state.block
        if (block == Blocks.LAVA) return ThermodynamicsEngine.fromFreezingPoint(800)
        if (block == Blocks.LAVA_CAULDRON) return ThermodynamicsEngine.fromFreezingPoint(800)
        if (block == Blocks.MAGMA_BLOCK) return ThermodynamicsEngine.fromFreezingPoint(450)
        if (block == Blocks.FIRE) return ThermodynamicsEngine.fromFreezingPoint(260)
        if (block == Blocks.SOUL_FIRE) return ThermodynamicsEngine.fromFreezingPoint(460)
        if (block == Blocks.CAMPFIRE && state.getValue(CampfireBlock.LIT))
            return ThermodynamicsEngine.fromFreezingPoint(260)
        if (block == Blocks.SOUL_CAMPFIRE && state.getValue(CampfireBlock.LIT))
            return ThermodynamicsEngine.fromFreezingPoint(460)

        return 0
    }

//    fun getThermalConductivity(state: BlockState): Float {
//        if (state.isAir) return 1f
//        if (state.fluidState.isEmpty) return 0.8f
//        return 0.6f
//    }

    @SubscribeEvent
    fun onModLoadCompleted(event: FMLLoadCompleteEvent) {
        val intrinsicHeatGetter = { state: BlockState, _: Level, _: BlockPos -> getIntrinsicHeat(state) }
        val epitaxialHeatGetter = { state: BlockState, _: Level, _: BlockPos -> getEpitaxialHeat(state) }
        val airThermalConductivityGetter = { _: BlockState, _: Level, _: BlockPos -> 1f }
        val solidThermalConductivityGetter = { state: BlockState, _: Level, _: BlockPos ->
            if (state.fluidState.isEmpty) 0.6f else 0.8f
        }

        BuiltInRegistries.BLOCK.forEach { block ->
            if (block is BlockWithHeat) return@forEach

            block as BlockWithThermal

            if (block in intrinsicHeatSet) {
                block.`fallacy$setIntrinsicHeatGetter`(intrinsicHeatGetter)
            }

            if (block in epitaxialHeatSet) {
                block.`fallacy$setEpitaxialHeatGetter`(epitaxialHeatGetter)
            }

            if (block.defaultBlockState().isAir || block.defaultBlockState().isEmpty) {
                block.`fallacy$setThermalConductivityGetter`(airThermalConductivityGetter)
            } else block.`fallacy$setThermalConductivityGetter`(solidThermalConductivityGetter)
        }
    }
}