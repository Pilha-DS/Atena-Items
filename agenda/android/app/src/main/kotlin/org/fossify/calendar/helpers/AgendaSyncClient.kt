package org.fossify.calendar.helpers

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/** What went wrong, so the UI can show a localized sentence. */
enum class AgendaSyncErrorKind {
    NOT_CONFIGURED,
    UNAUTHORIZED,
    NETWORK,
    SERVER,
    INVALID_RESPONSE
}

class AgendaSyncException(
    val kind: AgendaSyncErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * The HTTP side of the sync.
 *
 * `HttpURLConnection` instead of OkHttp/Ktor on purpose: the app has no HTTP
 * dependency, and one POST does not justify adding one. This class does no
 * database work and no threading; the engine owns both.
 */
class AgendaSyncClient {

    fun ping(url: String, token: String): Long {
        val body = execute(url = url, token = token, path = AGENDA_SYNC_PING_PATH, body = null)
        return runCatching { JSONObject(body).optLong("serverTime") }
            .getOrDefault(System.currentTimeMillis())
    }

    fun sync(url: String, token: String, request: AgendaSyncRequest): AgendaSyncResponse {
        val body = execute(
            url = url,
            token = token,
            path = AGENDA_SYNC_PATH,
            body = request.toJson()
        )
        return try {
            parseSyncResponse(body)
        } catch (error: JSONException) {
            throw AgendaSyncException(
                AgendaSyncErrorKind.INVALID_RESPONSE,
                "could not read the server response: ${error.message}",
                error
            )
        }
    }

    private fun execute(url: String, token: String, path: String, body: String?): String {
        val connection = openConnection(url = url, token = token, path = path, body = body)
        return try {
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        url: String,
        token: String,
        path: String,
        body: String?
    ): HttpURLConnection {
        val base = baseUrl(url)
        if (base.isEmpty()) {
            throw AgendaSyncException(AgendaSyncErrorKind.NOT_CONFIGURED, "server URL is empty")
        }

        val target = parseUrl(base + path)
        return try {
            (target.openConnection() as HttpURLConnection).apply {
                configure(token = token, body = body)
            }
        } catch (error: IOException) {
            throw AgendaSyncException(
                AgendaSyncErrorKind.NETWORK,
                error.message ?: "network error",
                error
            )
        }
    }

    private fun HttpURLConnection.configure(token: String, body: String?) {
        requestMethod = if (body == null) "GET" else "POST"
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        useCaches = false
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun parseUrl(value: String): URL = try {
        URL(value)
    } catch (error: MalformedURLException) {
        throw AgendaSyncException(
            AgendaSyncErrorKind.NOT_CONFIGURED,
            "invalid server URL: $value",
            error
        )
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val code = readStatusCode(connection)
        val body = readBody(connection, code)

        if (code in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            return body
        }
        if (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN) {
            throw AgendaSyncException(
                AgendaSyncErrorKind.UNAUTHORIZED,
                "the server rejected the token"
            )
        }

        val detail = runCatching { JSONObject(body).optString("error") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        throw AgendaSyncException(AgendaSyncErrorKind.SERVER, detail ?: "HTTP $code")
    }

    private fun readStatusCode(connection: HttpURLConnection): Int = try {
        connection.responseCode
    } catch (error: IOException) {
        throw networkError(error)
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String = try {
        val stream = if (code in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    } catch (error: IOException) {
        throw networkError(error)
    }

    private fun networkError(error: IOException) = AgendaSyncException(
        AgendaSyncErrorKind.NETWORK,
        error.message ?: "network error",
        error
    )

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val HTTP_SUCCESS_MIN = 200
        private const val HTTP_SUCCESS_MAX = 299
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403

        /**
         * Accepts what people actually type: a domain, an IP, a trailing slash,
         * or a full endpoint copied from the docs.
         *
         * No scheme? A private/LAN address almost always speaks plain HTTP,
         * while a public domain is almost always behind TLS, so the guess is
         * `http` for those and `https` for everything else. Typing the scheme
         * explicitly always wins.
         */
        fun baseUrl(raw: String): String {
            var value = raw.trim()
            if (value.isEmpty()) return ""
            if (!value.contains("://")) {
                val host = value.substringBefore('/').substringBefore(':')
                value = if (isLocalHost(host)) "http://$value" else "https://$value"
            }
            value = value.trimEnd('/')
            value = value.removeSuffix(AGENDA_SYNC_PATH).removeSuffix("/api/v1")
            return value.trimEnd('/')
        }

        /** localhost, 127.x, 10.x, 192.168.x, 172.16-31.x and *.local. */
        @Suppress("MagicNumber")
        fun isLocalHost(host: String): Boolean {
            val name = host.lowercase()
            if (name == "localhost" || name.endsWith(".local") || name.endsWith(".lan")) return true
            if (name == "::1" || name == "[::1]") return true
            val parts = name.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return when {
                parts[0] == 127 -> true
                parts[0] == 10 -> true
                parts[0] == 192 && parts[1] == 168 -> true
                parts[0] == 172 && parts[1] in 16..31 -> true
                else -> false
            }
        }
    }
}
