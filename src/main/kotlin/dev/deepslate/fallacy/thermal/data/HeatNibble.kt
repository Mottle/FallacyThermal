package dev.deepslate.fallacy.thermal.data

import com.mojang.serialization.Codec
import dev.deepslate.fallacy.utils.SingleWriterMultiReaderNibble
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

class HeatNibble(data: ByteArray?) : SingleWriterMultiReaderNibble(data) {
    override fun getUnitBitSize(): Int = 13

    override fun getMaxSize(): Int = 16 * 16 * 16

    companion object {
        val CODEC: Codec<HeatNibble> =
            Codec.BYTE.listOf().xmap<HeatNibble>({ HeatNibble(it.toByteArray()) }, { it.toByteArray().toList() })

        val STREAM_CODEC: StreamCodec<ByteBuf, HeatNibble> = ByteBufCodecs.BYTE.apply(ByteBufCodecs.list())
            .map({ HeatNibble(it.toByteArray()) }, { it.toByteArray().toList() })

        fun empty(): HeatNibble = HeatNibble(null)

        fun full(): HeatNibble {
            val nibble = empty()
            nibble.flip(true)
            return nibble
        }
    }
}