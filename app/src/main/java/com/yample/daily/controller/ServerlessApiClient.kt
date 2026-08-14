package com.yample.daily.controller

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * EMQX Serverless REST API v5 客户端。
 * 用于 HTTP 方式管理某 broker 上的在线 MQTT 客户端（列表/详情/订阅/强制下线），
 * 与被控端「在线客户端管理」同一套接口，控制端侧支持多个后台配置。
 */
object ServerlessApiClient {

    /** 在线客户端信息（对应 /clients 返回的 data[]） */
    data class ServerlessClient(
        val clientId: String,
        val username: String,
        val ip: String,
        val port: String,
        val protoVer: String,
        val keepalive: String,
        val connected: Boolean,
        val connectedAt: String,
        val disconnectedAt: String,
        val subscriptionsCnt: Int,
        val recvPkt: Long,
        val sendPkt: Long,
        val recvMsg: Long,
        val sendMsg: Long
    )

    data class Subscription(val topic: String, val qos: Int)

    /** 统一返回：Ok(json) / Err(msg) */
    sealed class ApiResult {
        data class Ok(val json: JSONObject?) : ApiResult()
        data class Err(val msg: String) : ApiResult()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 校验并规范化配置；返回 (baseUrl去尾斜杠, Basic 认证头)；三缺一返回 null */
    fun normalize(backend: ServerlessBackend): Pair<String, String>? {
        val url = backend.baseUrl.trim()
        val appId = backend.appId.trim()
        val appSecret = backend.appSecret.trim()
        if (url.isBlank() || appId.isBlank() || appSecret.isBlank()) return null
        return url.removeSuffix("/") to Credentials.basic(appId, appSecret)
    }

    /** 通用请求。jsonBody 传 null 表示 GET 或带空体的 DELETE。 */
    suspend fun call(
        baseUrl: String,
        auth: String,
        path: String,
        method: String = "GET",
        jsonBody: String? = null
    ): ApiResult = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url("$baseUrl$path")
                .header("Authorization", auth)
                .header("Accept", "application/json")
            val body = jsonBody?.let { it.toRequestBody("application/json; charset=utf-8".toMediaType()) }
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete(body)
                else -> builder.post(body ?: ByteArray(0).toRequestBody())
            }
            client.newCall(builder.build()).execute().use { resp ->
                val respBody = resp.body.string()
                if (resp.code in 200..299) {
                    // 兼容两种响应形态：{"data":[...]} 对象，或 /clients/{id}/subscriptions 裸数组
                    val json = when {
                        respBody.isBlank() -> null
                        respBody.trimStart().startsWith("[") -> JSONObject().put("data", JSONArray(respBody))
                        else -> JSONObject(respBody)
                    }
                    ApiResult.Ok(json)
                } else {
                    val msg = runCatching {
                        JSONObject(respBody).optString("message", "").ifBlank { "HTTP ${resp.code}" }
                    }.getOrDefault("HTTP ${resp.code}")
                    ApiResult.Err(msg)
                }
            }
        } catch (e: Exception) {
            ApiResult.Err(e.message ?: "请求异常")
        }
    }

    suspend fun fetchClients(baseUrl: String, auth: String): ApiResult =
        call(baseUrl, auth, "/clients?limit=100")

    suspend fun fetchSubscriptions(baseUrl: String, auth: String, clientId: String): ApiResult =
        call(baseUrl, auth, "/clients/${Uri.encode(clientId)}/subscriptions")

    suspend fun kickClient(baseUrl: String, auth: String, clientId: String): ApiResult =
        call(baseUrl, auth, "/clients/${Uri.encode(clientId)}", "DELETE")

    suspend fun subscribeClient(
        baseUrl: String,
        auth: String,
        clientId: String,
        topic: String,
        qos: Int
    ): ApiResult = call(
        baseUrl, auth, "/clients/${Uri.encode(clientId)}/subscribe", "POST",
        JSONObject().put("topic", topic).put("qos", qos).toString()
    )

    suspend fun unsubscribeClient(
        baseUrl: String,
        auth: String,
        clientId: String,
        topic: String
    ): ApiResult = call(
        baseUrl, auth, "/clients/${Uri.encode(clientId)}/unsubscribe", "POST",
        JSONObject().put("topic", topic).toString()
    )

    fun parseClients(json: JSONObject?): List<ServerlessClient> {
        val array = json?.optJSONArray("data") ?: JSONArray()
        val list = mutableListOf<ServerlessClient>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val clientId = obj.optString("clientid")
            if (clientId.isBlank()) continue
            list.add(
                ServerlessClient(
                    clientId = clientId,
                    username = obj.optString("username"),
                    ip = obj.optString("ip_address"),
                    port = obj.opt("port")?.toString() ?: "",
                    protoVer = obj.opt("proto_ver")?.toString() ?: "",
                    keepalive = obj.opt("keepalive")?.toString() ?: "",
                    connected = obj.optBoolean("connected", true),
                    connectedAt = obj.optString("connected_at"),
                    disconnectedAt = obj.optString("disconnected_at"),
                    subscriptionsCnt = obj.optInt("subscriptions_cnt", 0),
                    recvPkt = obj.optLong("recv_pkt", 0),
                    sendPkt = obj.optLong("send_pkt", 0),
                    recvMsg = obj.optLong("recv_msg", 0),
                    sendMsg = obj.optLong("send_msg", 0)
                )
            )
        }
        return list
    }

    fun parseSubscriptions(json: JSONObject?): List<Subscription> {
        val array = json?.optJSONArray("data") ?: JSONArray()
        val list = mutableListOf<Subscription>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            list.add(Subscription(obj.optString("topic"), obj.optInt("qos", 0)))
        }
        return list
    }
}
