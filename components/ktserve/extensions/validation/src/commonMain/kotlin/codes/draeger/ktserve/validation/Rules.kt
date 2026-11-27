package codes.draeger.ktserve.validation

import io.konform.validation.ValidationBuilder
import io.konform.validation.path.ValidationPath
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

inline fun <reified T : Any> rules(noinline configure: KonformRulesBuilder<T>.() -> Unit): KonformRules<T> =
    buildRules(typeOf<T>(), configure)

class KonformRulesBuilder<T : Any> internal constructor() : ValidationBuilder<T>() {
    private val minimumLengths = linkedMapOf<String, Int>()

    /** Require at least [length] Unicode code points and expose the same `minLength` in OpenAPI. */
    fun minLength(property: KProperty1<T, String>, length: Int, jsonName: String = property.name) {
        require(length >= 0) { "minLength must be non-negative" }
        minimumLengths[jsonName] = maxOf(length, minimumLengths[jsonName] ?: 0)
        validate(ValidationPath.of(jsonName), property) {
            constrain("must have at least $length characters") { it.hasAtLeastCodePoints(length) }
        }
    }

    internal fun toRules(type: KType): KonformRules<T> {
        val validation = build()
        val lengths = minimumLengths.toMap()
        return object : KonformRules<T> {
            override val bodyType = type
            override val validation = validation

            override fun schema(descriptor: SerialDescriptor): JsonObject? = lengths.toSchema(descriptor)
        }
    }
}

@PublishedApi
internal fun <T : Any> buildRules(type: KType, configure: KonformRulesBuilder<T>.() -> Unit): KonformRules<T> =
    KonformRulesBuilder<T>().apply(configure).toRules(type)

private fun Map<String, Int>.toSchema(descriptor: SerialDescriptor): JsonObject? =
    takeIf { it.isNotEmpty() }?.let { lengths ->
        buildJsonObject {
            putJsonObject("properties") {
                for ((name, length) in lengths) {
                    val index = descriptor.getElementIndex(name)
                    require(index >= 0 && descriptor.getElementDescriptor(index).kind == PrimitiveKind.STRING) {
                        "minLength requires a JSON string property '$name' in ${descriptor.serialName}"
                    }
                    putJsonObject(name) { put("minLength", length) }
                }
            }
        }
    }

private fun String.hasAtLeastCodePoints(minimum: Int): Boolean {
    if (minimum == 0) return true
    var count = 0
    for (index in indices) {
        if (this[index].isLowSurrogate() && index > 0 && this[index - 1].isHighSurrogate()) continue
        if (++count == minimum) return true
    }
    return false
}
