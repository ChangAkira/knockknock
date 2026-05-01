package com.example.testppppp.remote

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

sealed class RemoteSendResult {
    data class Success(val httpCode: Int, val bodySnippet: String) : RemoteSendResult()

    data class Failure(val message: String) : RemoteSendResult()
}

object RemoteCommandClient {

    fun postCommand(
        baseUrl: String,
        action: RemotePcAction,
        authToken: String?,
    ): RemoteSendResult {
        val root = normalizeBaseUrl(baseUrl) ?: return RemoteSendResult.Failure("Base URL 无效或为空")
        return try {
            val url = URL("$root/command")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val token = authToken?.trim().orEmpty()
            if (token.isNotEmpty()) {
                conn.setRequestProperty("X-Auth-Token", token)
            }
            conn.doOutput = true
            val json = JSONObject().put("action", action.jsonName).toString()
            conn.outputStream.use { os ->
                os.write(json.toByteArray(StandardCharsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { ins ->
                BufferedReader(InputStreamReader(ins, StandardCharsets.UTF_8)).readText()
            }.orEmpty().take(200)
            if (code in 200..299) {
                RemoteSendResult.Success(code, text)
            } else {
                RemoteSendResult.Failure("HTTP $code ${text.ifBlank { "" }}")
            }
        } catch (e: Exception) {
            RemoteSendResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    fun getHealth(baseUrl: String, authToken: String?): RemoteSendResult {
        val root = normalizeBaseUrl(baseUrl) ?: return RemoteSendResult.Failure("Base URL 无效或为空")
        return try {
            val url = URL("$root/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.requestMethod = "GET"
            val token = authToken?.trim().orEmpty()
            if (token.isNotEmpty()) {
                conn.setRequestProperty("X-Auth-Token", token)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { ins ->
                BufferedReader(InputStreamReader(ins, StandardCharsets.UTF_8)).readText()
            }.orEmpty().take(200)
            if (code in 200..299) {
                RemoteSendResult.Success(code, text)
            } else {
                RemoteSendResult.Failure("HTTP $code ${text.ifBlank { "" }}")
            }
        } catch (e: Exception) {
            RemoteSendResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    fun postLlm(
        baseUrl: String,
        spatialSector: String,
        userText: String,
        authToken: String?,
    ): RemoteSendResult {
        val root = normalizeBaseUrl(baseUrl) ?: return RemoteSendResult.Failure("Base URL 无效或为空")
        return try {
            val url = URL("$root/llm")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val token = authToken?.trim().orEmpty()
            if (token.isNotEmpty()) {
                conn.setRequestProperty("X-Auth-Token", token)
            }
            conn.doOutput = true
            val json = JSONObject()
                .put("spatial_sector", spatialSector)
                .put("user_text", userText)
                .toString()
            conn.outputStream.use { os ->
                os.write(json.toByteArray(StandardCharsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { ins ->
                BufferedReader(InputStreamReader(ins, StandardCharsets.UTF_8)).readText()
            }.orEmpty().take(2_000)
            if (code in 200..299) {
                RemoteSendResult.Success(code, text)
            } else {
                RemoteSendResult.Failure("HTTP $code ${text.ifBlank { "" }}")
            }
        } catch (e: Exception) {
            RemoteSendResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    fun normalizeBaseUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val noSlash = t.trimEnd('/')
        if (!noSlash.startsWith("http://") && !noSlash.startsWith("https://")) {
            return null
        }
        return noSlash
    }
}
