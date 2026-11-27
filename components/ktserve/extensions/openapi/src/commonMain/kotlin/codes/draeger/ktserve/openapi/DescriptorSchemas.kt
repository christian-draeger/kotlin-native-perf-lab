package codes.draeger.ktserve.openapi

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.nonNullOriginal
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/** Reflects the wire structure of the default kotlinx.serialization JSON codec. */
@OptIn(ExperimentalSerializationApi::class)
internal class DescriptorSchemas {
    private data class Component(val descriptor: SerialDescriptor, val input: Boolean, val name: String)

    private val components = mutableListOf<Component>()
    private val definitions = linkedMapOf<String, JsonObject>()

    fun definitions(): JsonObject = JsonObject(definitions.toMap())

    fun schema(descriptor: SerialDescriptor, input: Boolean): JsonObject {
        val schema = nonNullSchema(descriptor.nonNullOriginal, input)
        return if (!descriptor.isNullable || schema.isEmpty()) schema else buildJsonObject {
            put("anyOf", buildJsonArray {
                add(schema)
                add(typeSchema("null"))
            })
        }
    }

    private fun nonNullSchema(descriptor: SerialDescriptor, input: Boolean): JsonObject {
        when (descriptor.serialName) {
            "kotlin.UInt", "kotlin.ULong", "kotlin.UByte", "kotlin.UShort" -> return buildJsonObject {
                put("type", "integer")
                put("minimum", 0)
            }
            "kotlinx.serialization.json.JsonElement",
            "kotlinx.serialization.json.JsonPrimitive",
            "kotlinx.serialization.json.JsonLiteral" -> return JsonObject(emptyMap())
            "kotlinx.serialization.json.JsonNull" -> return typeSchema("null")
        }
        if (descriptor.isInline && descriptor.elementsCount == 1) {
            return schema(descriptor.getElementDescriptor(0), input)
        }
        return when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> typeSchema("boolean")
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> typeSchema("string")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT -> typeSchema("integer", "int32")
            PrimitiveKind.LONG -> typeSchema("integer", "int64")
            PrimitiveKind.FLOAT -> typeSchema("number", "float")
            PrimitiveKind.DOUBLE -> typeSchema("number", "double")
            SerialKind.ENUM -> if (input && hasAlternativeNames(descriptor)) JsonObject(emptyMap()) else buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    for (index in 0 until descriptor.elementsCount) add(descriptor.getElementName(index))
                })
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schema(descriptor.getElementDescriptor(0), input))
            }
            StructureKind.MAP -> {
                val key = descriptor.getElementDescriptor(0)
                if (key.kind != PrimitiveKind.STRING || key.isNullable) JsonObject(emptyMap()) else buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", schema(descriptor.getElementDescriptor(1), input))
                }
            }
            StructureKind.CLASS, StructureKind.OBJECT ->
                if (input && hasAlternativeNames(descriptor)) JsonObject(emptyMap()) else objectReference(descriptor, input)
            else -> JsonObject(emptyMap())
        }
    }

    private fun objectReference(descriptor: SerialDescriptor, input: Boolean): JsonObject {
        val component = components.firstOrNull { it.input == input && it.descriptor == descriptor }
        if (component != null) return reference(component.name)

        val stem = descriptor.serialName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val base = stem + if (input) "Request" else "Response"
        var name = base
        var suffix = 2
        while (components.any { it.name == name }) name = "${base}_${suffix++}"
        components.add(Component(descriptor, input, name))
        definitions[name] = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                for (index in 0 until descriptor.elementsCount) {
                    put(descriptor.getElementName(index), schema(descriptor.getElementDescriptor(index), input))
                }
            })
            val required = (0 until descriptor.elementsCount)
                .filterNot(descriptor::isElementOptional)
                .map(descriptor::getElementName)
            if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(it) } })
            if (!input || descriptor.annotations.none { it is JsonIgnoreUnknownKeys }) {
                put("additionalProperties", false)
            }
        }
        return reference(name)
    }

    private fun hasAlternativeNames(descriptor: SerialDescriptor): Boolean =
        (0 until descriptor.elementsCount).any { index ->
            descriptor.getElementAnnotations(index).any { it is JsonNames }
        }

    private fun reference(name: String) = buildJsonObject { put("\$ref", "#/components/schemas/$name") }
}

internal fun typeSchema(type: String, format: String? = null): JsonObject = buildJsonObject {
    put("type", type)
    format?.let { put("format", it) }
}
