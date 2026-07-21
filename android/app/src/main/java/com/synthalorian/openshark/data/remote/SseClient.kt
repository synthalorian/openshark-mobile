package com.synthalorian.openshark.data.remote

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

class SseClient(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun streamChat(request: ChatRequest): Flow<ChatResponseChunk> = callbackFlow {
        val requestBody = okhttp3.RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            gson.toJson(request)
        )

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/chat")
            .post(requestBody)
            .addHeader("Accept", "text/event-stream")
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d("SseClient", "SSE connection opened")
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                try {
                    val chunk = gson.fromJson(data, ChatResponseChunk::class.java)
                    trySend(chunk)

                    if (chunk.done || chunk.error != null) {
                        close()
                    }
                } catch (e: Exception) {
                    Log.e("SseClient", "Failed to parse chunk: $data", e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e("SseClient", "SSE failure", t)
                trySend(ChatResponseChunk(chunk = "", done = true, error = t?.message))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d("SseClient", "SSE connection closed")
                close()
            }
        }

        val factory = EventSources.createFactory(client)
        val eventSource = factory.newEventSource(httpRequest, listener)

        awaitClose {
            eventSource.cancel()
        }
    }
}
