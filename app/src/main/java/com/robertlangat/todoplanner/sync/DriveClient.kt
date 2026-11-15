package com.robertlangat.todoplanner.sync

import com.robertlangat.todoplanner.core.json.Json
import com.robertlangat.todoplanner.core.json.asList
import com.robertlangat.todoplanner.core.json.asObject
import com.robertlangat.todoplanner.core.json.str
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class DriveHttpException(val code: Int, message: String) : IOException(message)

data class DriveFile(val id: String, val headRevisionId: String?, val modifiedTime: String?)

/** Minimal Drive v3 REST client limited to the hidden appDataFolder. */
class DriveClient(private val token: String) {
    companion object {
        const val SYNC_FILE = "todoplanner-sync-v1.json"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    }

    fun findSyncFiles(): List<DriveFile> {
        val q = URLEncoder.encode("name = '$SYNC_FILE' and trashed = false", "UTF-8")
        val body = request("GET", "$API/files?spaces=appDataFolder&q=$q&orderBy=createdTime&fields=files(id,headRevisionId,modifiedTime)")
        return (Json.parse(body).asObject()?.get("files").asList() ?: emptyList()).mapNotNull {
            val o = it.asObject() ?: return@mapNotNull null
            DriveFile(o.str("id") ?: return@mapNotNull null, o.str("headRevisionId"), o.str("modifiedTime"))
        }
    }

    /** The signed-in account's email, for the "Signed in as" label. */
    fun accountEmail(): String? = runCatching {
        Json.parse(request("GET", "$API/about?fields=user(emailAddress)")).asObject()?.get("user").asObject()?.str("emailAddress")
    }.getOrNull()

    fun download(id: String): String = request("GET", "$API/files/$id?alt=media")

    fun create(content: String): String {
        val boundary = "todoplanner-" + UUID.randomUUID()
        val metadata = Json.stringify(mapOf("name" to SYNC_FILE, "parents" to listOf("appDataFolder"), "mimeType" to "application/json"))
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/json\r\n\r\n$content\r\n--$boundary--"
        val resp = request("POST", "$UPLOAD/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary")
        return Json.parse(resp).asObject()?.str("id") ?: throw IOException("Drive didn't return a file id")
    }

    fun update(id: String, content: String) {
        // HttpURLConnection has no PATCH; Google APIs accept the method override header.
        request("POST", "$UPLOAD/files/$id?uploadType=media", content, "application/json", methodOverride = "PATCH")
    }

    fun delete(id: String) {
        request("DELETE", "$API/files/$id")
    }

    private fun request(method: String, url: String, body: String? = null, contentType: String? = null, methodOverride: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (methodOverride != null) conn.setRequestProperty("X-HTTP-Method-Override", methodOverride)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType ?: "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw DriveHttpException(code, "Drive error $code: ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
