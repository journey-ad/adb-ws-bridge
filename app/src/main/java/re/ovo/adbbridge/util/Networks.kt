package re.ovo.adbbridge.util

import java.net.Inet4Address
import java.net.NetworkInterface

/** 本机全部局域网 IPv4 地址，不含回环与链路本地地址 */
fun getLanAddresses(): List<String> {
    return NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filterNot { it.isLoopbackAddress }
        .filterNot { it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()
        .toList()
}
