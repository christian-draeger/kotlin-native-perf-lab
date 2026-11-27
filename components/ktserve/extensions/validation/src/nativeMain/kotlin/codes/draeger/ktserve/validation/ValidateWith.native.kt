@file:OptIn(kotlin.reflect.ExperimentalAssociatedObjects::class)

package codes.draeger.ktserve.validation

import kotlin.reflect.AssociatedObjectKey
import kotlin.reflect.KClass
import kotlin.reflect.findAssociatedObject

@AssociatedObjectKey
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
actual annotation class ValidateWith(actual val value: KClass<out KonformRules<*>>)

internal actual fun KClass<*>.findKonformRules(): KonformRules<*>? = findAssociatedObject<ValidateWith>() as KonformRules<*>?
