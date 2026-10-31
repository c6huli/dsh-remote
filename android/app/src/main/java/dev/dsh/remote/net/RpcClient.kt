package dev.dsh.remote.net

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response)
        }
    })
    cont.invokeOnCancellation { if (isCanceled()) cancel() }
}

/**
 * Blocking HTTP client.
 *
 * Every entry point hops to [Dispatchers.IO] first: `ResponseBody.string()`,
 * `bytes()` and stream copies are blocking reads, and callers such as
 * viewModelScope.launch or Compose's LaunchedEffect run on the main thread —
 * reading a body there throws `NetworkOnMainThreadException`, which surfaces as
 * a message-less failure ("unknown") far from the real cause.
 */
class RpcClient(
    private val baseUrl: String,
    private val client: OkHttpClient,
) {
    private val json = DshJson.json
    private val jsonMedia = "application/json".toMediaType()

    val normalizedBase: String get() = baseUrl.trimEnd('/')

    /** POST one RPC and return the raw response body text, without schema decoding. */
    suspend fun invokeText(method: String, payload: JsonObject = JsonObject(emptyMap())): String =
        withContext(Dispatchers.IO) {
            val request = ClientRequest(
                rpcId = UUID.randomUUID().toString(),
                method = method,
                payload = payload,
            )
            val body = json.encodeToString(ClientRequest.serializer(), request).toRequestBody(jsonMedia)
            val httpRequest = Request.Builder()
                .url("$normalizedBase/api/$method")
                .post(body)
                .header("Origin", normalizedBase)
                .build()
            val response = client.newCall(httpRequest).await()
            response.use { resp ->
                val text = resp.body?.string() ?: throw IOException("empty response")
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(160)}")
                text
            }
        }

    suspend fun invoke(method: String, payload: JsonObject = JsonObject(emptyMap())): JsonElement =
        withContext(Dispatchers.IO) {
            val request = ClientRequest(
                rpcId = UUID.randomUUID().toString(),
                method = method,
                payload = payload,
            )
            val body = json.encodeToString(ClientRequest.serializer(), request).toRequestBody(jsonMedia)
            val httpRequest = Request.Builder()
                .url("$normalizedBase/api/$method")
                .post(body)
                .header("Origin", normalizedBase)
                .build()
            val response = client.newCall(httpRequest).await()
            response.use { resp ->
                val text = resp.body?.string() ?: throw IOException("empty response")
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val parsed = json.decodeFromString(ServerResponse.serializer(), text)
                val result = parsed.result
                if (!result.ok) throw RpcException(result.error?.code ?: "error", result.error?.message ?: "unknown error")
                result.value ?: JsonNull
            }
        }

    /** Answer an answerable server-request (approval / question). */
    suspend fun respond(rpcId: String, value: JsonObject) {
        withContext(Dispatchers.IO) {
            val resp = ClientResponse(rpcId = rpcId, result = RpcResult(ok = true, value = value))
            val body = json.encodeToString(ClientResponse.serializer(), resp)
                .toRequestBody(jsonMedia)
            val req = Request.Builder()
                .url("$normalizedBase/api/respond")
                .post(body)
                .header("Origin", normalizedBase)
                .build()
            val response = client.newCall(req).await()
            response.use { if (!it.isSuccessful) throw IOException("HTTP ${it.code}") }
        }
    }

    /** Plain HTTP GET returning the body as text (for the /remote helper routes). */
    suspend fun getText(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$normalizedBase$path").get().header("Origin", normalizedBase).build()
        val response = client.newCall(req).await()
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("empty response")
        }
    }

    /** Plain HTTP GET returning the body as bytes (file download). */
    suspend fun getBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$normalizedBase$path").get().header("Origin", normalizedBase).build()
        val response = client.newCall(req).await()
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.bytes() ?: throw IOException("empty response")
        }
    }

    /** Plain HTTP POST with a JSON string body (for the /remote helper routes). */
    suspend fun postJson(path: String, jsonBody: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$normalizedBase$path")
            .post(jsonBody.toRequestBody(jsonMedia))
            .header("Origin", normalizedBase)
            .build()
        val response = client.newCall(req).await()
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("empty response")
        }
    }

    /** Stream an HTTP GET response body directly to a local file (no memory buffering). */
    suspend fun downloadToFile(path: String, out: File): File = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$normalizedBase$path").get().header("Origin", normalizedBase).build()
        val response = client.newCall(req).await()
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty response")
            out.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
        }
        out
    }
}