package codes.draeger.ktserve.validation

import codes.draeger.ktserve.BodyValidation
import codes.draeger.ktserve.BodyViolation
import codes.draeger.ktserve.ServerExtension
import codes.draeger.ktserve.extension
import io.konform.validation.Invalid
import io.konform.validation.Valid
import io.konform.validation.Validation
import io.konform.validation.path.FuncRef
import io.konform.validation.path.PathClass
import io.konform.validation.path.PathIndex
import io.konform.validation.path.PathKey
import io.konform.validation.path.PathValue
import io.konform.validation.path.PropRef
import io.konform.validation.path.ValidationPath
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** Use a Konform rules object as the default validator for this JSON body type. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
expect annotation class ValidateWith(val value: KClass<out KonformRules<*>>)

interface KonformRules<T : Any> {
    val bodyType: KType
    val validation: Validation<T>

    /** Additional JSON Schema constraints for the effective body serializer. */
    fun schema(descriptor: SerialDescriptor): JsonObject? = null
}

/** Resolve validators once for typed request bodies with [ValidateWith]. */
fun validation(): ServerExtension = extension("codes.draeger.ktserve.validation") {
    resolveBodyValidation(::resolveKonformBodyValidation)
}

/** Supply these rules explicitly through the route's `jsonBody` configuration. */
inline fun <reified T : Any> KonformRules<T>.asBodyValidation(): BodyValidation<T> =
    asBodyValidation(serializer<T>().descriptor)

@PublishedApi
internal fun <T : Any> KonformRules<T>.asBodyValidation(descriptor: SerialDescriptor): BodyValidation<T> =
    BodyValidation(schema(descriptor)) { value ->
        when (val result = validation(value)) {
            is Valid -> emptyList()
            is Invalid -> result.errors.map { BodyViolation(it.message, it.path.jsonPointer(descriptor)) }
        }
    }

internal expect fun KClass<*>.findKonformRules(): KonformRules<*>?

internal fun resolveKonformBodyValidation(type: KType, descriptor: SerialDescriptor): BodyValidation<Any?>? {
    val rules = (type.classifier as? KClass<*>)?.findKonformRules() ?: return null
    require(rules.bodyType == type) {
        "@ValidateWith rules for ${rules.bodyType} cannot validate request body $type"
    }
    @Suppress("UNCHECKED_CAST") // The full body type, including nullability and arguments, was checked above.
    return (rules as KonformRules<Any>).asBodyValidation(descriptor) as BodyValidation<Any?>
}

private fun ValidationPath.jsonPointer(descriptor: SerialDescriptor): String? {
    if (segments.isEmpty()) return null
    var current = descriptor
    return buildString {
        for (segment in segments) {
            val token = when (segment) {
                is PropRef -> segment.property.name
                is PathIndex -> segment.index.toString()
                is PathKey -> segment.key.jsonKey() ?: return null
                is PathValue -> segment.value.jsonKey() ?: return null
                is FuncRef, is PathClass -> return null
            }
            val index = when (current.kind) {
                StructureKind.CLASS, StructureKind.OBJECT -> current.getElementIndex(token).takeIf { it >= 0 } ?: return null
                StructureKind.LIST -> if (segment is PathIndex) 0 else return null
                StructureKind.MAP -> if (segment is PathKey || segment is PathValue) 1 else return null
                else -> return null
            }
            current = current.getElementDescriptor(index)
            append('/').append(token.replace("~", "~0").replace("/", "~1"))
        }
    }
}

private fun Any?.jsonKey(): String? = when (this) {
    is String -> this
    is Number, is Boolean, is Char -> toString()
    else -> null
}
