package codes.draeger.ktserve.validation

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
actual annotation class ValidateWith(actual val value: KClass<out KonformRules<*>>)

internal actual fun KClass<*>.findKonformRules(): KonformRules<*>? {
    val declaration = java.getAnnotation(ValidateWith::class.java)?.value?.java ?: return null
    return try {
        declaration.getField("INSTANCE").get(null) as KonformRules<*>
    } catch (cause: ReflectiveOperationException) {
        throw IllegalArgumentException("@ValidateWith must reference a public rules object: ${declaration.name}", cause)
    }
}
