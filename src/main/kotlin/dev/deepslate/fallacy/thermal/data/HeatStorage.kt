package dev.deepslate.fallacy.thermal.data

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import net.minecraft.world.level.chunk.ChunkAccess
import net.neoforged.neoforge.attachment.IAttachmentHolder
import kotlin.jvm.optionals.getOrNull

class HeatStorage(data: Array<HeatNibble?> = arrayOf()) {
    companion object {
        private val nullableHeatNibble: Codec<Either<Short, HeatNibble>> =
            Codec.either(Codec.SHORT, HeatNibble.CODEC)

        val CODEC: Codec<HeatStorage> = nullableHeatNibble.listOf().xmap(::from, ::to)

        private fun from(list: List<Either<Short, HeatNibble>>): HeatStorage =
            HeatStorage(list.map { it.right().getOrNull() }.toTypedArray())

        private fun to(storage: HeatStorage): List<Either<Short, HeatNibble>> =
            storage.heatStorage.toList().map { it?.let { Either.right(it) } ?: Either.left(0) }

        fun of(chunk: ChunkAccess): HeatStorage {
            val size = (chunk.maxBuildHeight - chunk.minBuildHeight) / 16 + 1
            return HeatStorage(Array(size) { null })
        }

        fun of(holder: IAttachmentHolder): HeatStorage {
            if (holder is ChunkAccess) return of(holder)
            return HeatStorage()
        }
    }

    private var heatStorage: Array<HeatNibble?> = data

    fun getOrInitEmpty(index: Int): HeatNibble {
        if (heatStorage[index] == null) {
            heatStorage[index] = HeatNibble.empty()
        }
        return get(index)!!
    }

    fun getOrInitFull(index: Int): HeatNibble {
        if (heatStorage[index] == null) {
            heatStorage[index] = HeatNibble.full()
        }
        return get(index)!!
    }

    operator fun get(index: Int) = if (index !in 0..<size) null else heatStorage[index]

    fun freeEmpty() {
        for (idx in 0 until size) {
            val nibble = heatStorage[idx] ?: continue
            if (nibble.isAllZero()) {
                heatStorage[idx] = null
            }
        }
    }

    fun freeFull() {
        for (idx in 0 until size) {
            val nibble = heatStorage[idx] ?: continue
            if (nibble.isAllOne()) {
                heatStorage[idx] = null
            }
        }
    }

    val size: Int
        get() = heatStorage.size

    fun update() {
        freeEmpty()
        heatStorage.forEach { it?.update() }
    }

    fun toPlain(): ArrayList<Array<Array<IntArray>>?> {
        val arrays = arrayListOf<Array<Array<IntArray>>?>()
        for (nibble in heatStorage) {
            if (nibble == null) {
                arrays.add(null)
                continue
            }

            val array3D = Array<Array<IntArray>>(16) {
                Array<IntArray>(16) {
                    IntArray(16).apply { fill(-1) } // 显式填充
                }
            }

            for (y in 0..15) for (x in 0..15) for (z in 0..15) {
                array3D[y][x][z] = nibble.getReadable(x, y, z)
            }
            arrays.add(array3D)
        }

        return arrays
    }
}