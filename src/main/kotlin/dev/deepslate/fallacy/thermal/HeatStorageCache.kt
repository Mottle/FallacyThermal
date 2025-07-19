package dev.deepslate.fallacy.thermal

import dev.deepslate.fallacy.thermal.data.HeatStorage
import net.minecraft.world.level.ChunkPos

interface HeatStorageCache {
    fun queryPositive(chunkPos: ChunkPos): HeatStorage

    fun queryNegative(chunkPos: ChunkPos): HeatStorage
}