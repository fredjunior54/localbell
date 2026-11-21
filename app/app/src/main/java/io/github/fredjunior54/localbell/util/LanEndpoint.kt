package io.github.fredjunior54.localbell.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl

object LanEndpoint {
    private val address = Regex("([A-Za-z0-9.-]{1,253})(?::([0-9]{1,5}))?")

    fun parse(input: String): HttpUrl? {
        val match = address.matchEntire(input.trim()) ?: return null
        val host = match.groupValues[1]
        val port = match.groupValues[2].ifEmpty { "19420" }.toInt()
        if (port !in 1..65535 || (host.all { it.isDigit() || it == '.' } && !allows(host)))
            return null
        return runCatching { HttpUrl.Builder().scheme("https").host(host).port(port).build() }
            .getOrNull()
    }

    fun allows(host: String): Boolean {
        val bytes = host.split('.').map { it.toIntOrNull() ?: return false }
        if (bytes.size != 4 || bytes.any { it !in 0..255 } || bytes.joinToString(".") != host)
            return false
        return when (bytes[0]) {
            10 -> true
            100 -> bytes[1] in 64..127
            169 -> bytes[1] == 254
            172 -> bytes[1] in 16..31
            192 -> bytes[1] == 168
            else -> false
        }
    }

    fun isOverlay(host: String) = host.startsWith("100.") && allows(host)

    // Only IPv4 is supported. IPv6 answers cannot be used for a connection.
    val dns = Dns { host -> validatedIpv4(Dns.SYSTEM.lookup(host)) }

    internal fun validatedIpv4(addresses: List<InetAddress>): List<InetAddress> {
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        if (ipv4.isEmpty() || ipv4.any { !allows(it.hostAddress.orEmpty()) }) {
            throw UnknownHostException("No permitted LAN address")
        }
        return ipv4
    }
}
