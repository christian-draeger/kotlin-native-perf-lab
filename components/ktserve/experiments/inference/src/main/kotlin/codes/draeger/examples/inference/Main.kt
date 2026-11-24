package codes.draeger.examples.inference

import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.response
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.CharacterCodingException
import java.time.Duration
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_INPUT_BYTES = 1024
private const val MAX_OUTPUT_BYTES = 16 * 1024
private const val INFERENCE_TIMEOUT_MILLIS = 2000L

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toInt() ?: 8080
    val inferencePort = args.getOrNull(1)?.toInt() ?: 8081
    val endpoint = URI.create("http://127.0.0.1:$inferencePort/embed")
    val admitted = Semaphore(permits = 4)
    val limits = ServerLimits(
        connections = 64,
        requestBodyBytes = MAX_INPUT_BYTES,
        requestBodyMillis = INFERENCE_TIMEOUT_MILLIS,
        responseBodyBytes = MAX_OUTPUT_BYTES,
        handlerMillis = 5000,
    )

    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(500)).build().use { client ->
            serve(port, limits = limits) {
                application { request, content ->
                    if (request.method == "GET" && request.path == "/health") return@application text("ok")
                    if (request.path != "/embed") return@application text("Not found", 404)
                    if (request.method != "POST") return@application text("Use POST", 405)
                    if (!"text/plain".equals(request.header("content-type")?.substringBefore(';')?.trim(), ignoreCase = true)) {
                        return@application text("Use UTF-8 text/plain", 415)
                    }
                    if (!admitted.tryAcquire()) return@application text("Inference queue is full", 503)

                    try {
                        val input = ByteArrayOutputStream()
                        while (true) {
                            val chunk = content.readChunk() ?: break
                            input.write(chunk)
                        }
                        val bytes = input.toByteArray()
                        if (bytes.decodeToString(throwOnInvalidSequence = true).isBlank()) {
                            return@application text("Send a nonblank input", 400)
                        }
                        val result = withTimeoutOrNull(INFERENCE_TIMEOUT_MILLIS) {
                            infer(client, endpoint, bytes)
                        } ?: return@application text("Inference deadline exceeded", 504)
                        response(result, contentType = "application/json")
                    } catch (error: CharacterCodingException) {
                        text("Send valid UTF-8", 400)
                    } catch (error: IOException) {
                        text("Inference service unavailable", 502)
                    } catch (cancelled: CancellationException) {
                        println("Embedding request cancelled")
                        throw cancelled
                    } finally {
                        admitted.release()
                    }
                }
            }
        }
}

private suspend fun infer(client: HttpClient, endpoint: URI, input: ByteArray): ByteArray {
    val request = HttpRequest.newBuilder(endpoint)
        .header("Content-Type", "text/plain; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofByteArray(input))
        .build()
    return suspendCancellableCoroutine { continuation ->
        val exchange = client.sendAsync(request) { info ->
            val length = info.headers().firstValueAsLong("Content-Length").orElse(-1)
            val mediaType = info.headers().firstValue("Content-Type").orElse("").substringBefore(';')
            if (info.statusCode() != 200 || length !in 1..MAX_OUTPUT_BYTES || mediaType != "application/json") {
                throw IOException("Invalid inference response")
            }
            HttpResponse.BodySubscribers.ofByteArray()
        }
        // JDK HttpClient requires cancel(true) to attempt to abort the HTTP exchange.
        continuation.invokeOnCancellation { exchange.cancel(true) }
        exchange.whenComplete { result, failure ->
            when (failure) {
                null -> continuation.resume(result.body())
                is CompletionException -> continuation.resumeWithException(failure.cause ?: failure)
                else -> continuation.resumeWithException(failure)
            }
        }
    }
}
