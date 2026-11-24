package com.maestrovpn.client.bg

import android.annotation.SuppressLint
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborEntryIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import com.maestrovpn.client.App
import com.maestrovpn.client.ktx.toList
import com.maestrovpn.client.ktx.toStringIterator
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger
import io.nekohasekai.libbox.NeighborEntry as LibboxNeighborEntry
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/**
 * "Owner unknown" is the ordinary answer for sockets the kernel cannot attribute (a few hundred a
 * minute on a busy TV box) and libbox expects it as an exception. Logging each one with a stack
 * trace flooded logcat with W/System.err and buried the lines that mattered; keep the first hit and
 * then one per [UNRESOLVED_OWNER_LOG_EVERY] so the channel stays observable without the flood.
 */
private val unresolvedOwnerHits = AtomicInteger()

private const val UNRESOLVED_OWNER_LOG_EVERY = 200

interface PlatformInterfaceWrapper : PlatformInterface {
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
    }

    override fun openTun(options: TunOptions): Int {
        error("invalid argument")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        try {
            val uid =
                App.connectivity.getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(sourceAddress, sourcePort),
                    InetSocketAddress(destinationAddress, destinationPort),
                )
            if (uid == Process.INVALID_UID) error("android: connection owner not found")
            val packages = App.packageManager.getPackagesForUid(uid)
            val owner = ConnectionOwner()
            owner.userId = uid
            owner.userName = packages?.firstOrNull() ?: ""
            owner.setAndroidPackageNames(StringArray(packages?.toList()?.iterator() ?: emptyList<String>().iterator()))
            return owner
        } catch (e: Exception) {
            val hits = unresolvedOwnerHits.incrementAndGet()
            if (hits == 1 || hits % UNRESOLVED_OWNER_LOG_EVERY == 0) {
                Log.d(
                    "PlatformInterface",
                    "connection owner unavailable (" + hits + " so far): " +
                        (e.message ?: e.javaClass.simpleName),
                )
            }
            throw e
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val networks = App.connectivity.allNetworks
        val networkInterfaces = NetworkInterface.getNetworkInterfaces().toList()
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        for (network in networks) {
            val boxInterface = LibboxNetworkInterface()
            val linkProperties = App.connectivity.getLinkProperties(network) ?: continue
            val networkCapabilities =
                App.connectivity.getNetworkCapabilities(network) ?: continue
            boxInterface.name = linkProperties.interfaceName
            val networkInterface =
                networkInterfaces.find { it.name == boxInterface.name } ?: continue
            boxInterface.dnsServer =
                StringArray(linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator())
            boxInterface.type =
                when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
            boxInterface.index = networkInterface.index
            runCatching {
                boxInterface.mtu = networkInterface.mtu
            }.onFailure {
                Log.e(
                    "PlatformInterface",
                    "failed to get mtu for interface ${boxInterface.name}",
                    it,
                )
            }
            boxInterface.addresses =
                StringArray(
                    networkInterface.interfaceAddresses.mapTo(mutableListOf()) { it.toPrefix() }
                        .iterator(),
                )
            var dumpFlags = 0
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (networkInterface.isLoopback) {
                dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
            }
            if (networkInterface.isPointToPoint) {
                dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
            }
            if (networkInterface.supportsMulticast()) {
                dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
            }
            boxInterface.flags = dumpFlags
            boxInterface.metered =
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxInterface)
        }
        return InterfaceArray(interfaces.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {
    }

    override fun readWIFIState(): WIFIState? {
        @Suppress("DEPRECATION")
        val wifiInfo =
            App.wifiManager.connectionInfo ?: return null
        var ssid = wifiInfo.ssid
        if (ssid == "<unknown ssid>") {
            return WIFIState("", "")
        }
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        return WIFIState(ssid, wifiInfo.bssid)
    }

    override fun localDNSTransport(): LocalDNSTransport? = LocalResolver

    // Срез 2: соседей через root-сервис нет (RootClient не переносится) — no-op.
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {
    }

    // Срез 2: shell/root в standalone-клиенте нет — движок не получает shell.
    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("shell not supported in this client")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession {
        error("shell not supported in this client")
    }

    override fun readSystemSSHHostKey(): String {
        error("not supported")
    }

    override fun lookupSFTPServer(): String {
        error("not supported")
    }

    override fun tailscaleHostname(): String = Settings.Global.getString(
        App.application.contentResolver,
        Settings.Global.DEVICE_NAME,
    )?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun lookupUser(username: String?): io.nekohasekai.libbox.PlatformUser {
        error("lookupUser not supported in this client")
    }

    override fun registerMyInterface(name: String?) {
    }

    /**
     * v1.14.4 additions to PlatformInterface. The TV client sends its notifications through
     * [BoxService], so an engine-side cancel is a no-op here, and the platform bridge (a Windows
     * VPN-only feature) is not used at all: the default implementations keep ProxyService and
     * VPNService compilable without a single behavioural change.
     */
    override fun cancelNotification(identifier: String?, typeID: Int) {
    }

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession? = null

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {
    }

    private class NeighborEntryArray(private val iterator: Iterator<LibboxNeighborEntry>) : NeighborEntryIterator {
        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): LibboxNeighborEntry = iterator.next()
    }

    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int {
            // not used by core
            return 0
        }

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): String = iterator.next()
    }

    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else {
        "${address.hostAddress}/$networkPrefixLength"
    }

    private val NetworkInterface.flags: Int
        @SuppressLint("SoonBlockedPrivateApi")
        get() {
            val getFlagsMethod = NetworkInterface::class.java.getDeclaredMethod("getFlags")
            return getFlagsMethod.invoke(this) as Int
        }
}
