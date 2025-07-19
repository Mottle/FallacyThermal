package dev.deepslate.fallacy.thermal

import dev.deepslate.fallacy.thermal.data.HeatStorage
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object ModAttachments {
    @JvmStatic
    private val registry = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, TheMod.ID)

    @JvmStatic
    internal val POSITIVE_CHUNK_HEAT: DeferredHolder<AttachmentType<*>, AttachmentType<HeatStorage>> =
        registry.register("positive_chunk_heat") { _ ->
            AttachmentType.builder(HeatStorage::of).serialize(HeatStorage.CODEC).build()
        }

    @JvmStatic
    internal val NEGATIVE_CHUNK_HEAT: DeferredHolder<AttachmentType<*>, AttachmentType<HeatStorage>> =
        registry.register("negative_chunk_heat") { _ ->
            AttachmentType.builder(HeatStorage::of).serialize(HeatStorage.CODEC).build()
        }

    @JvmStatic
    internal val HEAT_PROCESS_STATE: DeferredHolder<AttachmentType<*>, AttachmentType<HeatProcessState>> =
        registry.register("heat_process_state") { _ ->
            AttachmentType.builder { _ -> HeatProcessState.UNPROCESSED }.serialize(HeatProcessState.CODEC).build()
        }

    @EventBusSubscriber(modid = TheMod.ID)
    object RegisterHandler {
        @SubscribeEvent
        fun onModLoadCompleted(event: FMLConstructModEvent) {
            event.enqueueWork {
                registry.register(MOD_BUS)
            }
        }
    }
}