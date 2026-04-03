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
            storage.snapshot().toList().map { it?.let { Either.right(it) } ?: Either.left(0) }

        fun of(chunk: ChunkAccess): HeatStorage {
            val size = (chunk.maxBuildHeight - chunk.minBuildHeight) / 16
            return HeatStorage(Array(size) { null })
        }

        fun of(holder: IAttachmentHolder): HeatStorage {
            if (holder is ChunkAccess) return of(holder)
            return HeatStorage()
        }
    }

    private var heatStorage: Array<HeatNibble?> = data

    private var sharedSections: BooleanArray = BooleanArray(data.size)

    fun snapshot(): Array<HeatNibble?> = synchronized(this) {
        Array(heatStorage.size) { idx ->
            heatStorage[idx]?.let { HeatNibble(it.toByteArray()) }
        }
    }

    fun copy(): HeatStorage = synchronized(this) {
        val storageCopy = HeatStorage(heatStorage.copyOf())
        val shared = BooleanArray(heatStorage.size) { idx -> heatStorage[idx] != null }
        sharedSections = shared.copyOf()
        storageCopy.sharedSections = shared
        storageCopy
    }

    private fun ensureWritable(index: Int, full: Boolean): HeatNibble {
        if (heatStorage[index] == null) {
            heatStorage[index] = if (full) HeatNibble.full() else HeatNibble.empty()
            sharedSections[index] = false
            return heatStorage[index]!!
        }

        if (sharedSections[index]) {
            heatStorage[index] = HeatNibble(heatStorage[index]!!.toByteArray())
            sharedSections[index] = false
        }

        return heatStorage[index]!!
    }

    fun getOrInitEmpty(index: Int): HeatNibble {
        synchronized(this) {
            return ensureWritable(index, false)
        }
    }

    fun getOrInitFull(index: Int): HeatNibble {
        synchronized(this) {
            return ensureWritable(index, true)
        }
    }

    operator fun get(index: Int) = synchronized(this) { if (index !in 0..<size) null else heatStorage[index] }

    fun freeEmpty() {
        synchronized(this) {
            for (idx in 0 until size) {
                val nibble = heatStorage[idx] ?: continue
                if (nibble.isAllZero()) {
                    heatStorage[idx] = null
                    sharedSections[idx] = false
                }
            }
        }
    }

    fun freeFull() {
        synchronized(this) {
            for (idx in 0 until size) {
                val nibble = heatStorage[idx] ?: continue
                if (nibble.isAllOne()) {
                    heatStorage[idx] = null
                    sharedSections[idx] = false
                }
            }
        }
    }

    val size: Int
        get() = synchronized(this) { heatStorage.size }

    fun update() {
        freeEmpty()
        heatStorage.forEach { it?.update() }
    }

    fun updateFull() {
        freeFull()
        heatStorage.forEach { it?.update() }
    }

    fun toPlain(): ArrayList<Array<Array<IntArray>>?> {
        val arrays = arrayListOf<Array<Array<IntArray>>?>()
        val snapshot = synchronized(this) { heatStorage.copyOf() }
        for (nibble in snapshot) {
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
