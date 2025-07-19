package dev.deepslate.fallacy.thermal.datapack

import com.mojang.serialization.Codec
import dev.deepslate.fallacy.thermal.TheMod
import dev.deepslate.fallacy.thermal.data.Temperature
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.biome.Biomes

data class BiomeHeatConfiguration(val biomeMap: Map<ResourceLocation, Temperature>) {

    companion object {

        val CODEC: Codec<BiomeHeatConfiguration> = Codec.unboundedMap(ResourceLocation.CODEC, Temperature.CODEC)
            .xmap(::BiomeHeatConfiguration, BiomeHeatConfiguration::biomeMap)

        val STREAM_CODEC: StreamCodec<ByteBuf, BiomeHeatConfiguration> =
            ByteBufCodecs.map(::HashMap, ResourceLocation.STREAM_CODEC, Temperature.STREAM_CODEC, 256)
                .map(::BiomeHeatConfiguration, BiomeHeatConfiguration::toHashMap)

        val CONFIGURATION_KEY: ResourceKey<BiomeHeatConfiguration> =
            ResourceKey.create(ModDatapacks.BIOME_HEAT_REGISTRY_KEY, TheMod.withID("configuration"))

        val DEFAULT = Temperature.celsius(15)

        private fun getDefault() = hashMapOf<ResourceLocation, Temperature>(
            Biomes.WARM_OCEAN.location() to Temperature.celsius(25),
            Biomes.LUKEWARM_OCEAN.location() to Temperature.celsius(15),
            Biomes.DEEP_LUKEWARM_OCEAN.location() to Temperature.celsius(12),
            Biomes.OCEAN.location() to Temperature.celsius(10),
            Biomes.DEEP_OCEAN.location() to Temperature.celsius(7),
            Biomes.COLD_OCEAN.location() to Temperature.celsius(5),
            Biomes.DEEP_COLD_OCEAN.location() to Temperature.celsius(2),
            Biomes.RIVER.location() to Temperature.celsius(15),
            Biomes.DEEP_FROZEN_OCEAN.location() to Temperature.celsius(-23),
            Biomes.THE_VOID.location() to Temperature.celsius(0),
            Biomes.LUSH_CAVES.location() to Temperature.celsius(16),
            Biomes.PLAINS.location() to Temperature.celsius(20),
            Biomes.BEACH.location() to Temperature.celsius(20),
            Biomes.SUNFLOWER_PLAINS.location() to Temperature.celsius(23),
            Biomes.DEEP_DARK.location() to Temperature.celsius(10),
            Biomes.DRIPSTONE_CAVES.location() to Temperature.celsius(15),
            Biomes.FROZEN_RIVER.location() to Temperature.celsius(-15),
            Biomes.FROZEN_OCEAN.location() to Temperature.celsius(-20),
            Biomes.SNOWY_PLAINS.location() to Temperature.celsius(-15),
            Biomes.ICE_SPIKES.location() to Temperature.celsius(-35),
            Biomes.GROVE.location() to Temperature.celsius(-25),
            Biomes.FROZEN_PEAKS.location() to Temperature.celsius(-25),
            Biomes.JAGGED_PEAKS.location() to Temperature.celsius(-30),
            Biomes.SNOWY_SLOPES.location() to Temperature.celsius(-20),
            Biomes.SNOWY_TAIGA.location() to Temperature.celsius(-18),
            Biomes.SNOWY_BEACH.location() to Temperature.celsius(-12),
            Biomes.MEADOW.location() to Temperature.celsius(15),
            Biomes.CHERRY_GROVE.location() to Temperature.celsius(20),
            Biomes.DESERT.location() to Temperature.celsius(45),
            Biomes.SAVANNA.location() to Temperature.celsius(35),
            Biomes.SAVANNA_PLATEAU.location() to Temperature.celsius(35),
            Biomes.WINDSWEPT_SAVANNA.location() to Temperature.celsius(35),
            Biomes.BADLANDS.location() to Temperature.celsius(41),
            Biomes.ERODED_BADLANDS.location() to Temperature.celsius(41),
            Biomes.WOODED_BADLANDS.location() to Temperature.celsius(40),
            Biomes.FOREST.location() to Temperature.celsius(20),
            Biomes.FLOWER_FOREST.location() to Temperature.celsius(23),
            Biomes.DARK_FOREST.location() to Temperature.celsius(17),
            Biomes.BIRCH_FOREST.location() to Temperature.celsius(20),
            Biomes.OLD_GROWTH_BIRCH_FOREST.location() to Temperature.celsius(15),
            Biomes.OLD_GROWTH_PINE_TAIGA.location() to Temperature.celsius(10),
            Biomes.OLD_GROWTH_SPRUCE_TAIGA.location() to Temperature.celsius(5),
            Biomes.TAIGA.location() to Temperature.celsius(10),
            Biomes.WINDSWEPT_GRAVELLY_HILLS.location() to Temperature.celsius(12),
            Biomes.WINDSWEPT_FOREST.location() to Temperature.celsius(15),
            Biomes.WINDSWEPT_HILLS.location() to Temperature.celsius(12),
            Biomes.STONY_SHORE.location() to Temperature.celsius(20),
            Biomes.JUNGLE.location() to Temperature.celsius(30),
            Biomes.BAMBOO_JUNGLE.location() to Temperature.celsius(25),
            Biomes.SPARSE_JUNGLE.location() to Temperature.celsius(30),
            Biomes.MUSHROOM_FIELDS.location() to Temperature.celsius(30),
            Biomes.STONY_PEAKS.location() to Temperature.celsius(5),
            Biomes.MANGROVE_SWAMP.location() to Temperature.celsius(30),
            Biomes.SWAMP.location() to Temperature.celsius(28)
        )

        fun generateDefaultPack() = BiomeHeatConfiguration(getDefault())
    }

    fun query(namespacedId: ResourceLocation): Temperature = biomeMap[namespacedId] ?: DEFAULT

    private fun toHashMap() = HashMap(biomeMap)
}