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
            const val MIN_VALUE = ThermodynamicsEngine.MIN_HEAT

            const val MAX_VALUE = ThermodynamicsEngine.MAX_HEAT
        }

        fun toCelsius(): Celsius = Celsius(value - ThermodynamicsEngine.FREEZING_POINT)

        override fun toString(): String = "${value}K"
    }

    data class Celsius(val value: Int) : Temperature {
        override val heat: Int
            get() = value.coerceIn(
                MIN_VALUE,
                MAX_VALUE
            ) + ThermodynamicsEngine.FREEZING_POINT

        companion object {
            const val MIN_VALUE = -ThermodynamicsEngine.FREEZING_POINT
            const val MAX_VALUE =
                ThermodynamicsEngine.MAX_HEAT - ThermodynamicsEngine.FREEZING_POINT
        }

        fun toKelvins(): Kelvins = Kelvins(heat)

        override fun toString(): String = "${value}°C"
    }

    companion object {
        val CODEC: Codec<Temperature> = Codec.STRING.xmap(::fromString, Temperature::toString)

        val STREAM_CODEC: StreamCodec<ByteBuf, Temperature> =
            ByteBufCodecs.STRING_UTF8.map(::fromString, Temperature::toString)

        fun fromString(string: String): Temperature {
            val (sign, noHeadString) = if (string.firstOrNull() == '-') -1 to string.substring(1) else 1 to string

            val lastChar = noHeadString.last()
            val lastCharSet = setOf('K', 'C', 'k', 'c')

            require(lastChar in lastCharSet)

            if (lastChar.lowercaseChar() == 'c') {
                val c = noHeadString[noHeadString.length - 2]
                require(c == '°' || c.isDigit())
            }

            val digits =
                if (!noHeadString[noHeadString.length - 2].isDigit()) noHeadString.take(noHeadString.length - 2)
                else noHeadString.take(noHeadString.length - 1)

            require(digits.all { c -> c.isDigit() })

            val value = digits.toInt() * sign

            // 检查温度值范围
            when (lastChar) {
                'K', 'k' -> {
                    require(value in Kelvins.MIN_VALUE..Kelvins.MAX_VALUE)
                    return Kelvins(value)
                }

                'C', 'c' -> {
                    require(value in Celsius.MIN_VALUE..Celsius.MAX_VALUE)
                    return Celsius(value)
                }

                else -> throw IllegalArgumentException("Invalid temperature unit: $lastChar")
            }
        }

        fun celsius(heat: Int): Celsius = Celsius(heat)

        fun kelvins(heat: Int): Kelvins = Kelvins(heat)
    }
}