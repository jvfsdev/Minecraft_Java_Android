package dev.prismdroid.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class HttpException(val code: Int, val body: String, url: String) : IOException("HTTP $code for $url: ${body.take(300)}")

object Http {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val UA = "PrismDroid/0.1 (Android)"

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun HttpURLConnection.readBody(): String {
        val ok = responseCode in 200..299
        val text = (if (ok) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (!ok) throw HttpException(responseCode, text, url.toString())
        return text
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) { open(url, "GET", headers).readBody() }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        json.parseToJsonElement(getText(url, headers))

    suspend fun postJson(url: String, body: JsonObject, headers: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val c = open(url, "POST", headers + ("Content-Type" to "application/json"))
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            json.parseToJsonElement(c.readBody())
        }

    suspend fun postForm(url: String, form: Map<String, String>): JsonElement =
        withContext(Dispatchers.IO) {
            val c = open(url, "POST", mapOf("Content-Type" to "application/x-www-form-urlencoded"))
            c.doOutput = true
            val encoded = form.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            c.outputStream.use { it.write(encoded.toByteArray()) }
            json.parseToJsonElement(c.readBody())
        }

    /** Downloads [url] to [dest] atomically, verifying SHA-1 when given. Skips when already valid. */
    fun download(url: String, dest: File, sha1: String?, onBytes: (Long) -> Unit = {}) {
        if (dest.isFile && (sha1 == null || sha1Of(dest) == sha1)) return
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val c = open(url, "GET", emptyMap())
                c.readTimeout = 60_000
                if (c.responseCode !in 200..299) throw HttpException(c.responseCode, "", url)
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); onBytes(n.toLong())
                        }
                    }
                }
                if (sha1 != null) {
                    val got = sha1Of(tmp)
                    if (got != sha1) throw IOException("SHA-1 mismatch for $url: expected $sha1, got $got")
                }
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("download failed: $url")
    }

    fun sha1Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
