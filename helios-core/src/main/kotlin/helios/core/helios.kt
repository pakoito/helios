package helios.core

import arrow.*
import arrow.core.*
import arrow.syntax.applicative.map
import arrow.syntax.option.*
import helios.instances.HeliosFacade
import helios.parser.Parser
import helios.typeclasses.Decoder
import helios.typeclasses.decoder
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

const val MaxLongString = "9223372036854775807"
const val MinLongString = "-9223372036854775808"

@prisms sealed class Json {

    companion object {
        fun fromValues(i: Iterable<Json>): JsArray = JsArray(i.toList())

        fun parseFromByteBuffer(buf: ByteBuffer): Either<Throwable, Json> =
                Parser.parseFromByteBuffer(buf, HeliosFacade).toEither()

        fun parseFromChannel(channel: ReadableByteChannel): Either<Throwable, Json> =
                Parser.parseFromChannel(channel, HeliosFacade).toEither()

        fun parseFromCharSequence(cs: CharSequence): Either<Throwable, Json> =
                Parser.parseFromCharSequence(cs, HeliosFacade).toEither()

        fun parseFromFile(file: File): Either<Throwable, Json> =
                Parser.parseFromFile(file, HeliosFacade).toEither()

        fun parseFromPath(path: String): Either<Throwable, Json> =
                Parser.parseFromPath(path, HeliosFacade).toEither()

        fun parseFromString(s: String): Either<Throwable, Json> =
                Parser.parseFromString(s, HeliosFacade).toEither()

        fun parseUnsafe(s: String): Json =
                Parser.parseFromString(s, HeliosFacade).fold({ throw it }, { it })
    }

    operator fun get(key: String): Option<Json> = when (this) {
        is JsObject -> Option.fromNullable(this.value[key])
        else -> None
    }

    inline fun <reified A> decode(decoder: Decoder<A> = decoder<A>()) =
            decoder.decode(this)

    fun <B> fold(ifJsString: (JsString) -> B,
                 ifJsNumber: (JsNumber) -> B,
                 ifJsArray: (JsArray) -> B,
                 ifJsObject: (JsObject) -> B,
                 ifJsBoolean: (JsBoolean) -> B,
                 ifJsNull: () -> B): B =
            when (this) {
                is JsString -> ifJsString(this)
                is JsNumber -> ifJsNumber(this)
                is JsArray -> ifJsArray(this)
                is JsObject -> ifJsObject(this)
                is JsNull -> ifJsNull()
                is JsBoolean -> ifJsBoolean(this)
            }

    fun add(key: String, value: Json): JsObject =
            JsObject(hashMapOf(key to value))

    fun asJsString(): Option<JsString> =
            (this as? JsString)?.some() ?: none()

    fun asJsBoolean(): Option<JsBoolean> =
            (this as? JsBoolean)?.some() ?: none()

    fun asJsNumber(): Option<JsNumber> =
            (this as? JsNumber)?.some() ?: none()

    fun asJsArray(): Option<JsArray> =
            (this as? JsArray)?.some() ?: none()

    fun asJsObject(): Option<JsObject> =
            (this as? JsObject)?.some() ?: none()

    fun asJsNull(): Option<JsNull> =
            (this as? JsNull)?.some() ?: none()

    fun merge(that: Json): Json =
            Option.applicative().map(asJsObject(), that.asJsObject(), { (lhs, rhs) ->
                lhs.toList().fold(rhs) { acc, (key, value) ->
                    rhs[key].fold({ acc.add(key, value) }, { r -> acc.add(key, value.merge(r)) })
                }
            }).ev().getOrElse { that }

    abstract fun toJsonString(): String

}

@lenses @isos data class JsBoolean(val value: Boolean) : Json() {
    override fun toJsonString(): String = "$value"
}

@lenses @isos data class JsString(val value: CharSequence) : Json() {
    override fun toJsonString(): String = """"$value""""
}

@prisms sealed class JsNumber : Json() {

    abstract fun toBigDecimal(): Option<BigDecimal>

    abstract fun toBigInteger(): Option<BigInteger>

    abstract fun toDouble(): Double

    abstract fun toLong(): Option<Long>

    fun toByte(): Option<Byte> {
        val ml = toLong()
        return when (ml) {
            is Some<Long> -> {
                val asByte: Byte = ml.t.toByte()
                if (asByte.compareTo(ml.t) == 0) Some(asByte) else None
            }
            is None -> None
        }
    }

    fun toShort(): Option<Short> {
        val ml = toLong()
        return when (ml) {
            is Some<Long> -> {
                val asShort: Short = ml.t.toShort()
                if (asShort.compareTo(ml.t) == 0) Some(asShort) else None
            }
            is None -> None
        }
    }

    fun toInt(): Option<Int> {
        val ml = toLong()
        return when (ml) {
            is Some<Long> -> {
                val asInt: Int = ml.t.toInt()
                if (asInt.compareTo(ml.t) == 0) Some(asInt) else None
            }
            is None -> None
        }
    }

    companion object {

        operator fun invoke(value: Long): JsLong = JsLong(value)

        operator fun invoke(value: Double): JsDouble = JsDouble(value)

        operator fun invoke(value: Int): JsInt = JsInt(value)

        operator fun invoke(value: Float): JsFloat = JsFloat(value)

        fun fromDecimalStringUnsafe(value: String): JsDecimal = JsDecimal(value)

        fun fromIntegralStringUnsafe(value: String): JsNumber {
            val bound = if (value[0] == '-') MinLongString else MaxLongString
            val isJsDecimal =
                    value.length < bound.length
                            || (value.length == bound.length && value <= bound)
            return if (isJsDecimal) JsDecimal(value) else {
                val longValue = java.lang.Long.parseLong(value)

                if (value[0] == '-' && longValue == 0L) JsDecimal(value) else JsLong(longValue)
            }
        }
    }
}

@lenses @isos data class JsDecimal(val value: String) : JsNumber() {
    override fun toBigDecimal(): Option<BigDecimal> = value.toBigDecimal().some()

    override fun toBigInteger(): Option<BigInteger> = toBigDecimal().map { it.toBigInteger() }

    override fun toDouble(): Double = value.toDouble()

    override fun toLong(): Option<Long> = value.toLong().some()

    override fun toJsonString(): String = value
}

@lenses @isos data class JsLong(val value: Long) : JsNumber() {
    override fun toBigDecimal(): Option<BigDecimal> = value.toBigDecimal().some()

    override fun toBigInteger(): Option<BigInteger> = toBigDecimal().map { it.toBigInteger() }

    override fun toDouble(): Double = value.toDouble()

    override fun toLong(): Option<Long> = value.some()

    override fun toJsonString(): String = "$value"
}

@lenses @isos data class JsDouble(val value: Double) : JsNumber() {
    override fun toBigDecimal(): Option<BigDecimal> = value.toBigDecimal().some()

    override fun toBigInteger(): Option<BigInteger> = toBigDecimal().map { it.toBigInteger() }

    override fun toDouble(): Double = value

    override fun toLong(): Option<Long> = value.toLong().some()

    override fun toJsonString(): String = "$value"
}

@lenses @isos data class JsFloat(val value: Float) : JsNumber() {

    override fun toBigDecimal(): Option<BigDecimal> = value.toBigDecimal().some()

    override fun toBigInteger(): Option<BigInteger> = toBigDecimal().map { it.toBigInteger() }

    override fun toDouble(): Double = value.toDouble()

    override fun toLong(): Option<Long> = value.toLong().some()

    override fun toJsonString(): String = "$value"
}

@lenses @isos data class JsInt(val value: Int) : JsNumber() {
    override fun toBigDecimal(): Option<BigDecimal> = value.toBigDecimal().some()

    override fun toBigInteger(): Option<BigInteger> = value.toBigInteger().some()

    override fun toDouble(): Double = value.toDouble()

    override fun toLong(): Option<Long> = value.toLong().some()

    override fun toJsonString(): String = "$value"
}

@lenses @isos data class JsArray(val value: List<Json>) : Json() {
    override fun toJsonString(): String =
            value.map { it.toJsonString() }.joinToString(prefix = "[", separator = ",", postfix = "]")
}
@lenses @isos data class JsObject(val value: Map<String, Json>) : Json() {

    companion object

    fun toList(): List<Tuple2<String, Json>> = value.toList().map { it.first toT it.second }

    override fun toJsonString(): String =
            value.map { (k, v) -> """"$k":${v.toJsonString()}""" }.joinToString(prefix = "{", separator = ",", postfix = "}")
}

object JsNull : Json() {
    override fun toJsonString(): String = "null"
}

val JsTrue = JsBoolean(true)
val JsFalse = JsBoolean(false)