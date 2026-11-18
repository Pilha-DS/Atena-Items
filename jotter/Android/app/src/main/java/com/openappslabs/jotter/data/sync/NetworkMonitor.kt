/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.jotter.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "may this request cost the user money?" for the sync loop.
 *
 * `ConnectivityManager` instead of listening to broadcasts: the cost of the
 * active network is a question with one answer at any moment, and one
 * `NetworkCallback` is enough to know when the answer changes.
 *
 * A VPN or a hotspot over Wi-Fi is metered only if the system says so: the
 * `METERED` flag is the user's own declaration ("this connection is
 * expensive"), which is exactly the intent to respect here.
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) {

    private val _cost = MutableStateFlow(current())
    val cost: StateFlow<NetworkCost> = _cost.asStateFlow()

    init {
        try {
            manager()?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _cost.value = current()
                }

                override fun onLost(network: Network) {
                    _cost.value = current()
                }
            })
        } catch (error: Exception) {
            // The permission is declared in the manifest; if it is ever revoked
            // the loop still works, it just cannot tell free data from paid
            // data and stays on the safe, slow cadence only when it must.
            Log.w(TAG, "could not watch the network: ${error.message}")
        }
    }

    /** Reads the cost of the active network now, without waiting for a callback. */
    fun current(): NetworkCost {
        val manager = manager() ?: return NetworkCost.UNMETERED
        val network = manager.activeNetwork ?: return NetworkCost.OFFLINE
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return NetworkCost.OFFLINE

        val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (!usable) return NetworkCost.OFFLINE

        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkCost.UNMETERED
        } else {
            NetworkCost.METERED
        }
    }

    private fun manager(): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private companion object {
        const val TAG = "JotterSync"
    }
}
