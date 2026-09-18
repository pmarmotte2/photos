package fr.notedefrais.app.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object RemoteAnnouncement {
    private const val ANNOUNCEMENT_URL =
        "https://82.165.175.13/fo-notes-message.txt"
    private const val MAX_MESSAGE_LENGTH = 4_000

    suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(ANNOUNCEMENT_URL).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "text/plain")
                connection.setRequestProperty("Cache-Control", "no-cache")

                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                val contentType = connection.contentType.orEmpty()
                val text = connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { reader ->
                        val buffer = CharArray(MAX_MESSAGE_LENGTH + 1)
                        var total = 0
                        while (total < buffer.size) {
                            val read = reader.read(buffer, total, buffer.size - total)
                            if (read < 0) break
                            total += read
                        }
                        String(buffer, 0, total)
                    }
                sanitizeAnnouncement(text, contentType)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}

internal fun sanitizeAnnouncement(text: String, contentType: String): String? {
    if (!contentType.substringBefore(';').trim().equals("text/plain", ignoreCase = true)) {
        return null
    }
    val normalized = text
        .replace("\r\n", "\n")
        .trim()
    return normalized
        .takeIf { it.isNotBlank() && it.length <= 4_000 }
}
