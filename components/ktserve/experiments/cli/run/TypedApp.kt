import codes.draeger.ktserve.*
import codes.draeger.ktserve.openapi.openApi
import codes.draeger.ktserve.validation.KonformRules
import codes.draeger.ktserve.validation.ValidateWith
import codes.draeger.ktserve.validation.rules
import codes.draeger.ktserve.validation.validation
import kotlinx.serialization.Serializable

@Serializable
@ValidateWith(CreateUser.Rules::class)
data class CreateUser(val name: String) {
    object Rules : KonformRules<CreateUser> by rules({
        minLength(CreateUser::name, 2)
    })
}

@Serializable
data class User(val id: Int, val name: String)

fun main(args: Array<String>) {
    serve(port = args.firstOrNull()?.toInt() ?: 8080, host = "127.0.0.1") {
        install(validation())
        install(openApi(title = "Users", version = "1.0") {
            serveAt("/openapi.json")
        })

        api("/api") {
            get("/health") { "ok" }
            get("/users/42") { User(42, "Ada") }
            post("/users", status = 201) { input: CreateUser ->
                User(42, input.name)
            }
        }
    }
}
