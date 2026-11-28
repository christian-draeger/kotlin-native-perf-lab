package codes.draeger.examples.extensions

import codes.draeger.ktserve.HttpField
import codes.draeger.ktserve.RequestAttribute
import codes.draeger.ktserve.RequestDecision
import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.accesslog.accessLog
import codes.draeger.ktserve.bearer.BearerCredential
import codes.draeger.ktserve.bearer.bearer
import codes.draeger.ktserve.bearer.bearerCredential
import codes.draeger.ktserve.cors.cors
import codes.draeger.ktserve.extension
import codes.draeger.ktserve.json
import codes.draeger.ktserve.openapi.openApi
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import codes.draeger.ktserve.validation.KonformRules
import codes.draeger.ktserve.validation.ValidateWith
import codes.draeger.ktserve.validation.rules
import codes.draeger.ktserve.validation.validation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

private const val DEMO_TOKEN = "taskboard-demo"
internal const val BROWSER_ORIGIN = "http://localhost:5173"
private val workspace = RequestAttribute<String>("workspace")

private val workspaceAccess = extension("taskboard.workspace-access") {
    onRequest { context ->
        val request = context.request
        if (!request.path.startsWith("/api/")) return@onRequest RequestDecision.Continue
        val credential = request.bearerCredential
        val selected = request.header("X-Workspace")
        when {
            credential !is BearerCredential.Present || credential.token != DEMO_TOKEN ->
                RequestDecision.Respond(text("Invalid demo token", status = 401)
                    .withHeaders(HttpField("WWW-Authenticate", "Bearer")))
            selected.isNullOrBlank() -> RequestDecision.Respond(text("Select a workspace", status = 400))
            else -> {
                context[workspace] = selected
                RequestDecision.Continue
            }
        }
    }
    onResponseHeaders { context, headers ->
        context[workspace]?.let { headers.set("X-Workspace", it) }
    }
}

@Serializable
@ValidateWith(TaskDraft.Rules::class)
internal data class TaskDraft(val title: String) {
    object Rules : KonformRules<TaskDraft> by rules({ minLength(TaskDraft::title, 3) })
}

@Serializable
private data class Task(val id: Int, val title: String, val done: Boolean)

@Serializable
private data class TaskUpdate(val done: Boolean)

private class TaskStore {
    private val lock = Mutex()
    private val tasks = mutableMapOf<String, MutableMap<Int, Task>>()
    private var nextId = 1

    suspend fun list(workspace: String): List<Task> = lock.withLock {
        tasks[workspace]?.values?.toList().orEmpty()
    }

    suspend fun add(workspace: String, title: String): Task = lock.withLock {
        val task = Task(nextId++, title, done = false)
        tasks.getOrPut(workspace) { linkedMapOf() }[task.id] = task
        task
    }

    suspend fun update(workspace: String, id: Int, done: Boolean): Task? = lock.withLock {
        val board = tasks[workspace] ?: return@withLock null
        board[id]?.copy(done = done)?.also { board[id] = it }
    }
}

internal fun runServer(
    args: Array<String>,
    stopRequested: () -> Boolean = { false },
    platformExtensions: Routes.() -> Unit = {},
) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    val tasks = TaskStore()
    serve(port = port, host = "127.0.0.1", limits = ServerLimits(requestBodyBytes = 4096), stopRequested = stopRequested) {
        platformExtensions()
        install(cors {
            allowOrigins(BROWSER_ORIGIN)
            allowMethods("GET", "POST", "PATCH")
            allowHeaders("Authorization", "Content-Type", "X-Workspace")
            allowCredentials = true
        })
        install(accessLog())
        install(bearer())
        install(workspaceAccess)
        install(validation())
        install(openApi(title = "Taskboard API", version = "1.0") { serveAt("/openapi.json") })

        get("/health") { text("ok") }
        route("OPTIONS", "/api/tasks") { _, _ -> text("ordinary OPTIONS") }
        api("/api") {
            getSuspending("/tasks") { request -> tasks.list(checkNotNull(request.attribute(workspace))) }
            post("/tasks", status = 201) { input: TaskDraft -> tasks.add(checkNotNull(attribute(workspace)), input.title) }
            route("PATCH", "/tasks/{id}") { input: TaskUpdate ->
                val id = pathInt("id") ?: return@route text("Invalid task ID", status = 400)
                val task = tasks.update(checkNotNull(attribute(workspace)), id, input.done)
                if (task == null) text("Task not found", status = 404) else json(task)
            }
        }
    }
}
