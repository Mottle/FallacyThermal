package dev.deepslate.fallacy.thermal

import net.minecraft.resources.ResourceLocation
import net.neoforged.fml.common.Mod
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

@Mod(TheMod.ID)
object TheMod {
    fun withID(name: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(ID, name)

    const val ID = "fallacy_thermal"

    val LOGGER: Logger = LogManager.getLogger(ID)
}
