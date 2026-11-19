package com.tunnelgo.app

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class VlessProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val userId: String,
    val security: String,
    val transport: String,
    val serverName: String?,
    val publicKey: String?,
    val shortId: String?
)

object VlessParser {
    fun parse(rawInput: String): VlessProfile {
        val raw = rawInput.trim()
        require(raw.startsWith("vless://", ignoreCase = true)) { "URI phải bắt đầu bằng vless://" }
        val uri = try { URI(raw) } catch (_: Exception) {
            throw IllegalArgumentException("URI VLESS không hợp lệ")
        }
        require(uri.scheme.equals("vless", ignoreCase = true)) { "Scheme không được hỗ trợ" }

        val userId = decode(uri.rawUserInfo ?: "").substringBefore(':').trim()
        val parsedId = try { UUID.fromString(userId) } catch (_: Exception) { null }
        require(parsedId != null) { "UUID trong URI không hợp lệ" }
        val host = uri.host?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("URI thiếu hostname hợp lệ")
        val port = uri.port
        require(port in 1..65535) { "Port phải nằm trong khoảng 1–65535" }

        val query = parseQuery(uri.rawQuery.orEmpty())
        val security = query["security"].orEmpty().ifBlank { "none" }.lowercase()
        val transport = query["type"].orEmpty().ifBlank { "tcp" }.lowercase()
        require(transport in setOf("tcp", "ws", "grpc", "http", "h2", "xhttp")) {
            "Transport '$transport' chưa được hỗ trợ trong bộ kiểm tra MVP"
        }
        val serverName = query["sni"]?.takeIf { it.isNotBlank() }
        val publicKey = query["pbk"]?.takeIf { it.isNotBlank() }
        val shortId = query["sid"]?.takeIf { it.isNotBlank() }
        if (security == "reality") {
            require(!publicKey.isNullOrBlank()) { "Profile Reality thiếu tham số pbk (public key)" }
            require(!serverName.isNullOrBlank()) { "Profile Reality thiếu tham số sni" }
        }

        val label = uri.rawFragment?.let(::decode)?.trim()?.takeIf { it.isNotBlank() } ?: host
        return VlessProfile(
            id = UUID.randomUUID().toString(),
            name = label,
            host = host,
            port = port,
            userId = parsedId.toString(),
            security = security,
            transport = transport,
            serverName = serverName,
            publicKey = publicKey,
            shortId = shortId
        )
    }

    private fun parseQuery(rawQuery: String): Map<String, String> = rawQuery
        .split('&')
        .filter { it.isNotBlank() }
        .associate { part ->
            val pieces = part.split('=', limit = 2)
            decode(pieces[0]) to decode(pieces.getOrElse(1) { "" })
        }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: Exception) { value }
}
