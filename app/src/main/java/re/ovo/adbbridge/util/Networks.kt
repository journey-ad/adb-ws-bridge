package re.ovo.adbbridge.util

import java.net.Inet4Address
import java.net.NetworkInterface

fun getLanAddress(): String? {
    return NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
        ?.hostAddress
}
