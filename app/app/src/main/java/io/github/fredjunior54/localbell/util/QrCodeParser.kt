package io.github.fredjunior54.localbell.util

data class PairingCode(val url: String, val fingerprint: String, val code: String)

object QrCodeParser {
    private val hex = Regex("[0-9a-f]{64}")

    fun isPrivateIpv4(host: String): Boolean {
        val pieces = host.split('.')
        if (pieces.size != 4) return false
        val bytes = pieces.map { part ->
            val byte = part.toIntOrNull() ?: return false
            if (byte !in 0..255 || byte.toString() != part) return false
            byte
        }
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168) ||
            (bytes[0] == 169 && bytes[1] == 254)
    }

    fun isCredential(value: String): Boolean = hex.matches(value)

    fun parse(data: String): PairingCode? {
        if (data.length > 180) return null
        val parts = data.split('|')
        if (parts.size != 5 || parts[0] != "localbell:1" || !isPrivateIpv4(parts[1])) return null
        val port = parts[2].toIntOrNull() ?: return null
        if (port !in 1024..65535 || port.toString() != parts[2]) return null
        if (!isCredential(parts[3]) || !isCredential(parts[4])) return null
        return PairingCode("https://${parts[1]}:$port", parts[3], parts[4])
    }
}
