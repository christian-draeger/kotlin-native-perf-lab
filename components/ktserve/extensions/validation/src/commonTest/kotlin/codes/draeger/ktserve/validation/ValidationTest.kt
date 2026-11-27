package codes.draeger.ktserve.validation

import io.konform.validation.constraints.notBlank
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ValidationTest {
    @Test
    fun annotationResolvesOneRuleForRuntimeAndSchema() = runBlocking {
        val descriptor = CreateUser.serializer().descriptor
        val validation = assertNotNull(resolveKonformBodyValidation(typeOf<CreateUser>(), descriptor))
        assertEquals(
            Json.parseToJsonElement("""{"properties":{"profile~/name":{"minLength":2}}}""").jsonObject,
            validation.schema,
        )

        val error = validation.validate(CreateUser("😀")).single()
        assertEquals("/profile~0~1name", error.pointer)
        assertTrue(validation.validate(CreateUser("😀a")).isEmpty())
    }

    @Test
    fun unannotatedBodyHasNoImplicitRules() {
        assertNull(resolveKonformBodyValidation(typeOf<Item>(), Item.serializer().descriptor))
    }

    @Test
    fun wrongRulesTypeIsRejectedWhenResolvingTheRoute() {
        val failure = assertFailsWith<IllegalArgumentException> {
            resolveKonformBodyValidation(typeOf<Mismatch>(), Mismatch.serializer().descriptor)
        }
        assertTrue(failure.message.orEmpty().contains("cannot validate request body"))
    }

    @Test
    fun ordinaryKonformRulesPreserveNestedPathsAndDoNotGuessSchema() = runBlocking {
        val validation = rules<Batch> {
            Batch::items onEach { Item::name { notBlank() } }
            constrain("batch must contain items") { it.items.isNotEmpty() }
        }.asBodyValidation()

        assertNull(validation.schema)
        assertEquals("/items/1/name", validation.validate(Batch(listOf(Item("Ada"), Item("")))).single().pointer)
        assertNull(validation.validate(Batch(emptyList())).single().pointer)
        assertTrue(validation.validate(Batch(listOf(Item("Ada")))).isEmpty())
    }

    @Serializable
    @ValidateWith(CreateUser.Rules::class)
    data class CreateUser(@SerialName("profile~/name") val name: String) {
        object Rules : KonformRules<CreateUser> by rules({
            minLength(CreateUser::name, 2, jsonName = "profile~/name")
        })
    }

    @Serializable
    @ValidateWith(CreateUser.Rules::class)
    data class Mismatch(val id: Int)

    @Serializable
    data class Batch(val items: List<Item>)

    @Serializable
    data class Item(val name: String)
}
