package dev.deepslate.fallacy.thermal.data

import com.mojang.serialization.Codec
import dev.deepslate.fallacy.thermal.ThermodynamicsEngine
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

sealed interface Temperature : Comparable<Temperature> {
    val heat: Int

    override fun compareTo(other: Temperature): Int = heat.compareTo(other.heat)

    data class Kelvins(val value: Int) : Temperature {
        override val heat: Int get() = value.coerceIn(MIN_VALUE, MAX_VALUE)

        companion object {
            const val MIN_VALUE = ThermodynamicsEngine.Companion.MIN_HEAT

            const val MAX_VALUE = ThermodynamicsEngine.Companion.MAX_HEAT
        }

        fun toCelsius(): Celsius = Celsius(value - ThermodynamicsEngine.Companion.FREEZING_POINT)

        override fun toString(): String = "${value}K"
    }

    data class Celsius(val value: Int) : Temperature {
        override val heat: Int
            get() = value.coerceIn(
                MIN_VALUE,
                MAX_VALUE
            ) + ThermodynamicsEngine.Companion.FREEZING_POINT

        companion object {
            const val MIN_VALUE = -ThermodynamicsEngine.Companion.FREEZING_POINT
            const val MAX_VALUE =
                ThermodynamicsEngine.Companion.MAX_HEAT - ThermodynamicsEngine.Companion.FREEZING_POINT
        }

        fun toKelvins(): Kelvins = Kelvins(heat)

        override fun toString(): String = "${value}°C"
    }

    companion object {
        val CODEC: Codec<Temperature> = Codec.STRING.xmap(::fromString, Temperature::toString)

        val STREAM_CODEC: StreamCodec<ByteBuf, Temperature> =
            ByteBufCodecs.STRING_UTF8.map(::fromString, Temperature::toString)

        fun fromString(string: String): Temperature {
            val charSet = setOf('K', '°', 'C', 'k', 'c')
            val lastCharSet = setOf('K', 'C', 'k', 'c')
            require(string.all { c -> c.isDigit() || c in charSet })
            require(string.last() in lastCharSet)

            if (string.endsWith("K") || string.endsWith("k")) {
                return Kelvins(string.substring(0, string.length - 1).toInt())
            }

            if (string.endsWith("°C") || string.endsWith("°c")) {
                return Celsius(string.substring(0, string.length - 2).toInt())
            }

            if (string.endsWith("C") || string.endsWith("c")) {
                return Celsius(string.substring(0, string.length - 1).toInt())
            }

            throw IllegalArgumentException("Invalid temperature string: $string")
        }

        fun celsius(heat: Int): Celsius = Celsius(heat - ThermodynamicsEngine.Companion.FREEZING_POINT)

        fun kelvins(heat: Int): Kelvins = Kelvins(heat)
    }
}