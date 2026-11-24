package codes.draeger.ktserve.webflux

import codes.draeger.ktserve.HttpField
import codes.draeger.ktserve.Request
import codes.draeger.ktserve.RequestContent
import codes.draeger.ktserve.Response
import codes.draeger.ktserve.ResponseWriter
import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.response
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.streamSuspending
import java.net.InetSocketAddress
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpCookie
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.server.reactive.AbstractServerHttpRequest
import org.springframework.http.server.reactive.AbstractServerHttpResponse
import org.springframework.http.server.reactive.HttpHandler
import org.springframework.http.server.reactive.SslInfo
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** Run a Spring reactive HTTP handler through KtServe's JVM transport. */
fun serveWebFlux(
    handler: HttpHandler,
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    onListening: () -> Unit = {},
) {
    serve(port, host, limits, stopRequested, onListening) {
        application { request, content ->
            exchange(handler, request, content, host, port, limits.responseBodyBytes)
        }
    }
}

private suspend fun exchange(
    handler: HttpHandler, request: Request, body: RequestContent, host: String, port: Int, maxResponseBytes: Int,
): Response {
    val incoming = try {
        SpringRequest(request, body, host, port)
    } catch (_: IllegalArgumentException) {
        return response(ByteArray(0), status = 400)
    }
    // The reactive handler and its write Mono can live until the response producer finishes.
    // The request coroutine transfers ownership through the response close hook.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val outgoing = SpringResponse(maxResponseBytes, request.method == "HEAD", scope::cancel)
    scope.launch {
        try {
            handler.handle(incoming, outgoing).awaitSingleOrNull()
            outgoing.finish()
        } catch (failure: Exception) {
            outgoing.fail(failure)
        } finally {
            scope.cancel()
        }
    }
    try {
        return outgoing.ready.await()
    } catch (cancelled: CancellationException) {
        scope.cancel()
        throw cancelled
    }
}

private val buffers = DefaultDataBufferFactory.sharedInstance

private class SpringRequest(
    private val request: Request,
    content: RequestContent,
    host: String,
    port: Int,
) : AbstractServerHttpRequest(HttpMethod.valueOf(request.method), request.uri(host, port), "", request.springHeaders()) {
    private val local = InetSocketAddress.createUnresolved(host, port)
    private val body: Flux<DataBuffer> = flow {
        while (true) emit(buffers.wrap(content.readChunk() ?: break))
    }.asFlux()

    override fun getBody(): Flux<DataBuffer> = body

    override fun getLocalAddress(): InetSocketAddress = local

    override fun getRemoteAddress(): InetSocketAddress? = null

    override fun initSslInfo(): SslInfo? = null

    override fun initCookies(): MultiValueMap<String, HttpCookie> {
        val result = LinkedMultiValueMap<String, HttpCookie>()
        for (header in headers[HttpHeaders.COOKIE].orEmpty()) {
            for (part in header.split(';')) {
                val separator = part.indexOf('=')
                if (separator <= 0) continue
                val name = part.substring(0, separator).trim()
                if (name.isNotEmpty()) result.add(name, HttpCookie(name, part.substring(separator + 1).trim()))
            }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getNativeRequest(): T = request as T
}

private fun Request.uri(host: String, port: Int): URI {
    val authority = header("host") ?: "$host:$port"
    val uri = URI.create("http://$authority$path" + (query?.let { "?$it" } ?: ""))
    require(uri.rawAuthority == authority && uri.rawUserInfo == null && uri.rawPath.startsWith('/'))
    return uri
}

private fun Request.springHeaders(): HttpHeaders = HttpHeaders().apply {
    for (field in headers()) add(field.name, field.value)
}

private class SpringResponse(
    private val limit: Int,
    private val headOnly: Boolean,
    private val cancelExchange: () -> Unit,
) : AbstractServerHttpResponse(buffers) {
    val ready = CompletableDeferred<Response>()
    private val writer = CompletableDeferred<ResponseWriter>()
    private val completed = CompletableDeferred<Unit>()

    fun finish() {
        if (!ready.isCompleted) ready.complete(empty())
        completed.complete(Unit)
    }

    fun fail(cause: Exception) {
        if (!ready.isCompleted) ready.complete(response(ByteArray(0), status = 500))
        completed.completeExceptionally(cause)
    }

    override fun writeWithInternal(body: Publisher<out DataBuffer>): Mono<Void> = mono {
        val status = statusCode?.value() ?: 200
        val length = headers.contentLength.takeUnless { it < 0 }
        if (status == 204 || status == 205 || status == 304 || length == 0L) {
            Flux.from(body).doOnNext(DataBufferUtils.releaseConsumer()).then().awaitSingleOrNull()
            ready.complete(empty())
            return@mono
        }
        require(length == null || length <= limit)
        val stream = streamSuspending(length?.toInt(), headers.getFirst(HttpHeaders.CONTENT_TYPE), status, cancelExchange) {
            writer.complete(this)
            completed.await()
        }.withHeaders(*responseFields())
        ready.complete(stream)
        val output = writer.await()
        Flux.from(body)
            .map { data ->
                try {
                    val size = data.readableByteCount()
                    require(size <= limit)
                    ByteArray(size).also { data.read(it) }
                } finally {
                    DataBufferUtils.release(data)
                }
            }
            .doOnDiscard(DataBuffer::class.java, DataBufferUtils.releaseConsumer())
            .asFlow().buffer(0)
            .collect { output.write(it) }
    }.then()

    override fun writeAndFlushWithInternal(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> =
        writeWithInternal(Flux.from(body).concatMap({ Flux.from(it) }, 0))

    private fun empty(): Response {
        val code = statusCode?.value() ?: 200
        val contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE)
        val length = headers.contentLength
        val response = if (headOnly && length in 0..limit.toLong() && code != 204 && code != 205 && code != 304) {
            streamSuspending(length.toInt(), contentType, code) {}
        } else {
            response(ByteArray(0), code, contentType)
        }
        return response.withHeaders(*responseFields())
    }

    private fun responseFields(): Array<HttpField> = buildList {
        headers.forEach { name, values ->
            if (name.lowercase() !in managedFields) for (value in values) add(HttpField(name, value))
        }
        for (values in cookies.values) for (cookie in values) add(HttpField(HttpHeaders.SET_COOKIE, cookie.toString()))
    }.toTypedArray()

    override fun applyStatusCode() = Unit
    override fun applyHeaders() = Unit
    override fun applyCookies() = Unit

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getNativeResponse(): T = this as T

    private companion object {
        val managedFields = setOf(
            "connection", "content-length", "content-type", "keep-alive", "proxy-connection",
            "te", "trailer", "transfer-encoding", "upgrade",
        )
    }
}
