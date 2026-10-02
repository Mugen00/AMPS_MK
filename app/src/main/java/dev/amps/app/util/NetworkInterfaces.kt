package dev.amps.app.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The two facts bridge discovery needs about the network the phone is on.
 *
 * A UDP broadcast datagram is only delivered to a subnet when it is addressed to
 * the broadcast address of that subnet, and the prefix length is the only way to
 * compute it: `192.168.1.42/24` has to become `192.168.1.255`. Android does not
 * hand out a broadcast address, so it is derived here.
 *
 * Everything is `internal` and never throws: an interface can disappear between
 * the enumeration and the read, and a missing network is data, not a crash.
 */

/** Broadcast address of every usable IPv4 interface, e.g. `192.168.1.255`. */
internal fun subnetBroadcastAddress(): List<Inet4Address> =
    activeIpv4Interfaces()
        .mapNotNull { (address, prefix) -> broadcastAddressOf(address, prefix) }
        .distinct()

/** Address of every usable IPv4 interface, e.g. `192.168.1.42`. */
internal fun localIPv4Addresses(): List<String> =
    activeIpv4Interfaces()
        .mapNotNull { (address, _) -> address.hostAddress }
        .distinct()

/** Loopback, down, virtual and IPv6 interfaces are useless for a LAN broadcast. */
private fun activeIpv4Interfaces(): List<Pair<Inet4Address, Int>> {
    val enumeration = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
    val result = mutableListOf<Pair<Inet4Address, Int>>()
    while (enumeration.hasMoreElements()) {
        val device = runCatching { enumeration.nextElement() }.getOrNull() ?: continue
        if (!runCatching { device.isUp && !device.isLoopback }.getOrDefault(false)) continue
        val addresses = runCatching { device.interfaceAddresses }.getOrNull() ?: continue
        // interfaceAddresses is a List, not an Enumeration.
        for (candidate in addresses) {
            val address = candidate.address as? Inet4Address ?: continue
            // getNetworkPrefixLength() is a short in Java and -1 when it is unknown.
            val prefix = candidate.networkPrefixLength.toInt().takeIf { it in 0..32 } ?: continue
            result += address to prefix
        }
    }
    return result
}

private fun broadcastAddressOf(address: Inet4Address, prefix: Int): Inet4Address? {
    val mask = if (prefix == 0) 0L else (ALL_ONES shl (32 - prefix)) and ALL_ONES
    val broadcast = address.toLong() or (mask.inv() and ALL_ONES)
    val bytes = ByteArray(4) { index -> ((broadcast shr (24 - 8 * index)) and 0xFF).toByte() }
    return runCatching { InetAddress.getByAddress(bytes) as? Inet4Address }.getOrNull()
}

private const val ALL_ONES = 0xFFFFFFFFL

/** `Inet4Address.getAddress()` is a signed 32-bit value, so it is widened first. */
private fun Inet4Address.toLong(): Long =
    address.fold(0L) { accumulator, byte -> (accumulator shl 8) or (byte.toLong() and 0xFF) }
