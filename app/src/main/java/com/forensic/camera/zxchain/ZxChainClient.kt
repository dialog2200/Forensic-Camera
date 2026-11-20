package com.forensic.camera.zxchain

import com.forensic.camera.crypto.GmCrypto.hmacSm3Hex
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.random.Random

/**
 * 腾讯至信链开放接口直连客户端（无服务端中转）。
 * Base: 固定为 [BASE_URL]（网关变更只需改这一处常量，不在设置页暴露）。
 * 鉴权头: Secret-Id / Signature-Time / Nonce / Signature(hex HMAC-SM3) / Cloud-Trace-Id
 */
class ZxChainClient(
    private val secretId: String,
    private val secretKey: String
) {

    companion object {
        /** 至信链开放网关（集中常量，随网关调整在此统一变更）。 */
        const val BASE_URL = "https://open.zxchain.qq.com"
    }

    class ZxException(val retCode: Int, override val message: String) : Exception(message)

    data class EvResult(
        val retCode: Int,
        val retMsg: String,
        val evId: String,
        val txId: String,
        val blockHeight: Long,
        val txTime: Long,
        val extendInfo: String
    )

    /**
     * hash 存证。
     * @param hashHex   证据文件 SHA-256 的 hex
     * @param pubKeyPem 链账户注册的公钥 PEM（必须与注册时字符串完全一致）
     * @param privKeyPem 私钥 PEM
     * @param extendInfo 附加信息（<=1024 字符，可为空串）
     */
    fun evSave(hashHex: String, pubKeyPem: String, privKeyPem: String, extendInfo: String): EvResult {
        val signMsg = pubKeyPem + "_" + hashHex + "_" + extendInfo
        val sign = com.forensic.camera.crypto.GmCrypto.signSm2Hex(privKeyPem, signMsg)
        val body = JSONObject()
            .put("publicKey", pubKeyPem)
            .put("hash", hashHex)
            .put("sign", sign)
            .put("extendInfo", extendInfo)
        val resp = post("/api/v1/ev/save", body.toString())
        val data = resp.optJSONObject("data") ?: JSONObject()
        return EvResult(
            retCode = resp.optInt("retCode", -1),
            retMsg = resp.optString("retMsg"),
            evId = data.optString("evId"),
            txId = data.optString("txId"),
            blockHeight = data.optLong("blockHeight"),
            txTime = data.optLong("txTime"),
            extendInfo = data.optString("extendInfo")
        )
    }

    /** 查询存证：evId / hash / txId 至少传一个。 */
    fun evQuery(evId: String = "", hash: String = "", txId: String = ""): EvResult {
        val params = mutableMapOf<String, String>()
        if (evId.isNotEmpty()) params["evId"] = evId
        if (hash.isNotEmpty()) params["hash"] = hash
        if (txId.isNotEmpty()) params["txId"] = txId
        val resp = get("/api/v1/ev/query", params)
        val data = resp.optJSONObject("data") ?: JSONObject()
        return EvResult(
            retCode = resp.optInt("retCode", -1),
            retMsg = resp.optString("retMsg"),
            evId = data.optString("evId"),
            txId = data.optString("txId"),
            blockHeight = data.optLong("blockHeight"),
            txTime = data.optLong("txTime"),
            extendInfo = data.optString("extendInfo")
        )
    }

    /** 申请存证证书（异步任务）。 */
    fun evCertCreate(evId: String): EvResult {
        val body = JSONObject().put("evId", evId)
        val resp = post("/api/v1/ev/cert", body.toString())
        return EvResult(resp.optInt("retCode", -1), resp.optString("retMsg"),
            evId, "", 0, 0, resp.optJSONObject("data")?.optString("status") ?: "")
    }

    /** 查询存证证书状态与下载链接。 */
    fun evCertQuery(evId: String): EvResult {
        val resp = get("/api/v1/ev/cert", mapOf("evId" to evId))
        val data = resp.optJSONObject("data") ?: JSONObject()
        return EvResult(resp.optInt("retCode", -1), resp.optString("retMsg"),
            evId, data.optString("certUrl"), 0, 0, data.optString("status"))
    }

    // ---------- HTTP ----------

    private fun authHeaders(): Map<String, String> {
        val signTime = System.currentTimeMillis().toString()
        var nonce = Random.nextInt(0, 1_000_000)
        if (nonce < 100_000) nonce += 100_000
        val signStr = "$secretId&$secretKey&$signTime&$nonce"
        val signature = hmacSm3Hex(secretKey, signStr)
        return mapOf(
            "Secret-Id" to secretId,
            "Signature" to signature,
            "Signature-Time" to signTime,
            "Nonce" to nonce.toString(),
            "Cloud-Trace-Id" to UUID.randomUUID().toString(),
            "Content-Type" to "application/json;charset=utf-8"
        )
    }

    private fun post(path: String, json: String): JSONObject {
        val url = URL(BASE_URL + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        authHeaders().forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.doOutput = true
        conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
        return readResponse(conn)
    }

    private fun get(path: String, params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val url = URL("$BASE_URL$path?$query")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        authHeaders().forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return readResponse(conn)
    }

    private fun readResponse(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        if (code !in 200..299) {
            throw ZxException(code, "HTTP $code: $text")
        }
        return JSONObject(text)
    }
}
