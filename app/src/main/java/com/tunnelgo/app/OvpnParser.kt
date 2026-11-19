package com.tunnelgo.app

import java.util.UUID

data class ParsedOvpn(val id: String, val name: String, val host: String, val port: Int, val content: String)

object OvpnParser {
    private val remoteLine = Regex("(?im)^\\s*remote\\s+([^\\s#]+)\\s+(\\d{1,5})(?:\\s+([^\\s#]+))?")

    fun parse(fileName: String, content: String): ParsedOvpn {
        require(content.isNotBlank()) { "Tệp .ovpn đang trống" }
        require(content.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Tệp .ovpn vượt quá giới hạn 1 MB" }
        val remote = remoteLine.find(content) ?: throw IllegalArgumentException("Không tìm thấy dòng remote host port trong .ovpn")
        val host = remote.groupValues[1].trim()
        val port = remote.groupValues[2].toIntOrNull() ?: 0
        require(port in 1..65535) { "Port OpenVPN không hợp lệ" }
        val hasClient = Regex("(?im)^\\s*client\\s*$").containsMatchIn(content)
        val hasRemoteCert = Regex("(?im)^\\s*(remote-cert-tls|verify-x509-name)\\b").containsMatchIn(content)
        require(hasClient || hasRemoteCert) { "Tệp không giống cấu hình OpenVPN client hợp lệ" }
        val cleanName = fileName.substringAfterLast('/').removeSuffix(".ovpn").ifBlank { host }
        return ParsedOvpn(UUID.randomUUID().toString(), cleanName, host, port, content)
    }
}
