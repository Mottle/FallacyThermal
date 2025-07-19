package dev.deepslate.fallacy.thermal.datapack

import dev.deepslate.fallacy.thermal.TheMod
import net.minecraft.core.Registry
import net.minecraft.core.RegistrySetBuilder
import net.minecraft.resources.ResourceKey
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider
import net.neoforged.neoforge.data.event.GatherDataEvent
import net.neoforged.neoforge.registries.DataPackRegistryEvent

object ModDatapacks {
    val BIOME_HEAT_REGISTRY_KEY: ResourceKey<Registry<BiomeHeatConfiguration>> =
        ResourceKey.createRegistryKey(TheMod.withID("biome"))

    @EventBusSubscriber(modid = TheMod.ID)
    object RegisterHandler {
        @SubscribeEvent
        fun register(event: DataPackRegistryEvent.NewRegistry) {
            event.dataPackRegistry(BIOME_HEAT_REGISTRY_KEY, BiomeHeatConfiguration.CODEC)
        }

        @SubscribeEvent
        fun onGatherData(event: GatherDataEvent) {
            event.generator.addProvider(event.includeServer()) { output ->
                DatapackBuiltinEntriesProvider(
                    output,
                    event.lookupProvider,
                    RegistrySetBuilder().add(BIOME_HEAT_REGISTRY_KEY) { bs ->
                        bs.register(
                            BiomeHeatConfiguration.CONFIGURATION_KEY,
                            BiomeHeatConfiguration.generateDefaultPack()
                        )
                    },
                    setOf(TheMod.ID)
                )
            }
        }
    }
}