package dev.perflab.ktserve.ktor

import dev.perflab.ktserve.HttpField
import dev.perflab.ktserve.Request
import dev.perflab.ktserve.RequestContent
import dev.perflab.ktserve.Response
import dev.perflab.ktserve.ResponseWriter
import dev.perflab.ktserve.response
import dev.perflab.ktserve.streamSuspending
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.RequestConnectionPoint
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parseQueryString
import io.ktor.server.application.Application
import io.ktor.server.application.PipelineCall
import io.ktor.server.engine.BaseApplicationCall
import io.ktor.server.engine.BaseApplicationRequest
import io.ktor.server.engine.BaseApplicationResponse
import io.ktor.server.request.RequestCookies
import io.ktor.server.response.ResponseHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

internal suspend fun executeCall(
    application: Application,
    request: Request,
    content: RequestContent,
    host: String,
    port: Int,
    execute: suspend (PipelineCall) -> Unit,
): Response = coroutineScope {
    val input = if (request.bodyLength == 0) null else ByteChannel(autoFlush = true)
    val upload = input?.let { channel ->
        launch {
            try {
                while (true) channel.writeFully(content.readChunk() ?: break)
            } finally {
                channel.flushAndClose()
            }
        }
    }
    val call = KtServeCall(application, request, input ?: ByteReadChannel.Empty, host, port, coroutineContext)
    try {
        execute(call)
        call.response.result ?: response(ByteArray(0), status = 500)
    } finally {
        upload?.cancel()
        input?.cancel(null)
    }
}

private class KtServeCall(
    application: Application,
    request: Request,
    input: ByteReadChannel,
    host: String,
    port: Int,
    override val coroutineContext: CoroutineContext,
) : BaseApplicationCall(application), CoroutineScope {
    override val request = KtServeRequest(this, request, input, host, port)
    override val response = KtServeResponse(this)

    init {
        putResponseAttribute()
    }
}

private class KtServeRequest(
    call: PipelineCall,
    private val source: Request,
    protected override val engineReceiveChannel: ByteReadChannel,
    host: String,
    port: Int,
) : BaseApplicationRequest(call) {
    override val cookies by lazy { RequestCookies(this) }
    protected override val engineHeaders: Headers = HeadersBuilder().apply {
        for (field in source.headers()) append(field.name, field.value)
    }.build()

    override val rawQueryParameters: Parameters by lazy {
        source.query?.let { parseQueryString(it, decode = false) } ?: Parameters.Empty
    }
    override val queryParameters: Parameters by lazy {
        source.query?.let { parseQueryString(it) } ?: Parameters.Empty
    }

    override val local: RequestConnectionPoint = KtServeConnectionPoint(source, host, port)
}

private class KtServeConnectionPoint(request: Request, address: String, accessPort: Int) : RequestConnectionPoint {
    private val authority = request.header("host")
    override val scheme = "http"
    override val version = request.version
    override val uri = request.path + (request.query?.let { "?$it" } ?: "")
    override val method = HttpMethod.parse(request.method)
    override val localPort = accessPort
    override val localHost = address
    override val localAddress = address
    override val serverPort = authority?.substringAfterLast(':', "")?.toIntOrNull() ?: accessPort
    override val serverHost = authority?.substringBeforeLast(':') ?: address
    override val remoteHost = "unknown"
    override val remoteAddress = "unknown"
    override val remotePort = 0

    @Deprecated("Use serverHost")
    override val host get() = serverHost

    @Deprecated("Use serverPort")
    override val port get() = serverPort
}

private class KtServeResponse(call: PipelineCall) : BaseApplicationResponse(call) {
    private val fields = HeadersBuilder()
    var result: Response? = null
        private set

    override val headers = object : ResponseHeaders() {
        override fun engineAppendHeader(name: String, value: String) {
            fields.append(name, value)
        }

        override fun getEngineHeaderNames(): List<String> = fields.names().toList()

        override fun getEngineHeaderValues(name: String): List<String> = fields.getAll(name).orEmpty()
    }

    override fun setStatus(statusCode: HttpStatusCode) = Unit

    override suspend fun responseChannel(): ByteWriteChannel = error("KtServe sends through its response writer")

    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        result = response(ByteArray(0), status = 501)
    }

    @OptIn(InternalAPI::class)
    override suspend fun respondOutgoingContent(content: OutgoingContent) {
        if (content is OutgoingContent.ContentWrapper) {
            respondOutgoingContent(content.delegate())
            return
        }
        val bytes = (content as? OutgoingContent.ByteArrayContent)?.bytes()
        commitHeaders(content)
        val code = status()?.value ?: 200
        val type = fields[HttpHeaders.ContentType]
        val length = content.contentLength
        result = when (content) {
            is OutgoingContent.ByteArrayContent -> response(checkNotNull(bytes), code, type)
            is OutgoingContent.NoContent -> response(ByteArray(0), code, type)
            is OutgoingContent.ProtocolUpgrade -> response(ByteArray(0), status = 501)
            is OutgoingContent.ReadChannelContent -> stream(length, type, code) {
                val input = content.readFrom()
                try {
                    writeFrom(input)
                } finally {
                    input.cancel(null)
                }
            }
            is OutgoingContent.WriteChannelContent -> stream(length, type, code) {
                coroutineScope {
                    val output = ByteChannel(autoFlush = true)
                    val writer = launch {
                        try {
                            content.writeTo(output)
                        } finally {
                            output.flushAndClose()
                        }
                    }
                    try {
                        writeFrom(output)
                    } finally {
                        writer.cancel()
                        output.cancel(null)
                    }
                }
            }
            is OutgoingContent.ContentWrapper -> error("Ktor content was not unwrapped")
        }.withHeaders(*applicationFields())
        isSent = true
    }

    private fun applicationFields(): Array<HttpField> = buildList {
        for (name in fields.names()) {
            if (name.lowercase() in managedFields) continue
            for (value in fields.getAll(name).orEmpty()) add(HttpField(name, value))
        }
    }.toTypedArray()

    private fun stream(length: Long?, type: String?, status: Int, produce: suspend ResponseWriter.() -> Unit): Response {
        if (length != null && length !in 0..Int.MAX_VALUE.toLong()) return response(ByteArray(0), status = 500)
        if (status in listOf(204, 205, 304)) return response(ByteArray(0), status, type)
        return streamSuspending(length?.toInt(), type, status, produce)
    }

    private companion object {
        val managedFields = setOf(
            "connection", "content-length", "content-type", "keep-alive", "proxy-connection",
            "te", "trailer", "transfer-encoding", "upgrade",
        )
    }
}

private suspend fun ResponseWriter.writeFrom(channel: ByteReadChannel) {
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count < 0) return
        if (count > 0) write(buffer, length = count)
    }
}
