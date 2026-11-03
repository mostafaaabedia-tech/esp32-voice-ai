package com.voiceai.esp32.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.voiceai.esp32.data.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Finds the ESP32 and the PC (Ollama) on the network the phone is on. */
object NetScan {
    data class FoundEsp(val ip: String, val info: DeviceInfo)
    data class FoundPc(val ip: String, val models: Int)

    /**
     * The phone's local networks, like "192.168.80". Includes the phone's own
     * hotspot when the ESP32 and the PC are connected to the phone.
     */
    fun localPrefixes(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { n -> listOf("wlan", "swlan", "ap", "softap", "eth", "rndis", "bt-pan").any { n.name.startsWith(it) } }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
            .map { it.hostAddress!!.substringBeforeLast('.') }
            .distinct()
    }.getOrDefault(emptyList())

    /** The phone's own IP addresses on those networks (shown in the app). */
    fun phoneIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { a -> a.isSiteLocalAddress } }
            .map { it.hostAddress!! }
    }.getOrDefault(emptyList())

    private fun open(ip: String, port: Int, ms: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, port), ms); true }
    }.getOrDefault(false)

    /**
     * Checks every address (x.x.x.1 - 254) of the phone's networks for a VoiceAI
     * ESP32 (port 80) and Ollama (port 11434). Takes about 5-10 seconds.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scan(onProgress: (Int) -> Unit = {}): Pair<List<FoundEsp>, List<FoundPc>> = coroutineScope {
        val io = Dispatchers.IO.limitedParallelism(64)
        val prefixes = localPrefixes()
        val mine = phoneIps().toSet()
        val ips = prefixes.flatMap { p -> (1..254).map { "$p.$it" } }.filter { it !in mine }
        var done = 0
        val results = ips.map { ip ->
            async(io) {
                val esp = if (open(ip, 80, 400)) Api.probeEsp(ip)?.let { FoundEsp(ip, it) } else null
                val pc = if (open(ip, 11434, 400)) Api.probeOllama(ip)?.let { FoundPc(ip, it) } else null
                synchronized(this@NetScan) { done++; if (done % 16 == 0) onProgress(done * 100 / ips.size.coerceAtLeast(1)) }
                esp to pc
            }
        }.awaitAll()
        onProgress(100)
        results.mapNotNull { it.first } to results.mapNotNull { it.second }
    }

    /**
     * Setup mode: the ESP32's own hotspot has no internet, so Android would send
     * our requests over mobile data. Bind the app to WiFi while setting it up.
     */
    fun bindToWifi(ctx: Context, on: Boolean): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        if (!on) { cm.bindProcessToNetwork(null); return true }
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        return wifi != null && cm.bindProcessToNetwork(wifi)
    }

    suspend fun ping(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) { open(ip, port, 1500) }
}
