package dev.perflab.ktserve

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class SuspendedGet(
    scope: CoroutineScope,
    signalApplicationProgress: (() -> Unit)?,
    handler: suspend () -> Response,
) {
    private val completed = Channel<Response>(1, onUndeliveredElement = { it.releaseSource() })
    private val job = scope.launch {
        val response = try {
            handler()
        } catch (_: Exception) {
            // In particular, an application can cancel itself while the socket stays open.
            emptyResponse(500)
        }
        // Failed trySend leaves ownership with the producer. A successful send transfers it.
        if (completed.trySend(response).isSuccess) signalApplicationProgress?.invoke() else response.releaseSource()
    }

    fun takeResponse(): Response? = completed.tryReceive().getOrNull()

    fun cancel() {
        completed.cancel()
        job.cancel()
    }
}

private fun Response.releaseSource() {
    stream?.discard()
}
