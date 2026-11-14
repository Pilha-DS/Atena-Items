package com.openappslabs.jotter.data.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The route table is the only source of truth the desktop has about its
 * connection, so the parsing is exercised against the real file layout.
 */
class NetworkMonitorTest {

    private val header =
        "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT"

    private fun write(lines: String): File {
        val file = File.createTempFile("jotter-route", ".txt")
        file.deleteOnExit()
        file.writeText(lines)
        return file
    }

    private fun ipv4(vararg routes: String): File = write((listOf(header) + routes).joinToString("\n"))

    /** The default route: destination `00000000`, gateway flag (`0003`) set. */
    private fun defaultRoute(iface: String) =
        "$iface\t00000000\t0102A8C0\t0003\t0\t0\t600\t00000000\t0\t0\t0"

    /** A connected route for the local subnet: no gateway, so not the default. */
    private fun localRoute(iface: String) =
        "$iface\t0002A8C0\t00000000\t0001\t0\t0\t600\t00FFFFFF\t0\t0\t0"

    private fun monitor(ipv4: File, ipv6: File = File("/nonexistent-ipv6-route")) =
        NetworkMonitor(routeFile = ipv4, ipv6RouteFile = ipv6)

    @Test
    fun wifi_default_route_is_free() {
        assertEquals(NetworkCost.UNMETERED, monitor(ipv4(defaultRoute("wlan0"))).current())
    }

    @Test
    fun ethernet_default_route_is_free() {
        assertEquals(NetworkCost.UNMETERED, monitor(ipv4(defaultRoute("enp3s0"))).current())
    }

    @Test
    fun wwan_default_route_is_metered() {
        assertEquals(NetworkCost.METERED, monitor(ipv4(defaultRoute("wwan0"))).current())
    }

    @Test
    fun ppp_default_route_is_metered() {
        assertEquals(NetworkCost.METERED, monitor(ipv4(defaultRoute("ppp0"))).current())
    }

    @Test
    fun android_usb_tethering_is_metered() {
        assertEquals(NetworkCost.METERED, monitor(ipv4(defaultRoute("rmnet0"))).current())
    }

    @Test
    fun no_default_route_is_offline() {
        val routes = ipv4(localRoute("wlan0"))

        assertEquals(NetworkCost.OFFLINE, monitor(routes).current())
    }

    @Test
    fun the_loopback_is_not_a_default_route() {
        assertEquals(NetworkCost.OFFLINE, monitor(ipv4(defaultRoute("lo"))).current())
    }

    @Test
    fun an_ipv6_only_default_route_counts_as_online() {
        val ipv6 = write(
            "00000000000000000000000000000000 00 00000000000000000000000000000000 00 " +
                "00000000000000000000000000000000 00000400 00000001 00000000 00000003 eth0"
        )
        val routes = ipv4()

        assertEquals(NetworkCost.UNMETERED, monitor(routes, ipv6).current())
    }

    @Test
    fun an_unreadable_route_table_is_offline() {
        assertEquals(NetworkCost.OFFLINE, monitor(File("/nonexistent-route")).current())
    }
}
