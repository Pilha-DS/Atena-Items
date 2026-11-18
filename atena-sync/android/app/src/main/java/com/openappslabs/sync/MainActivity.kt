/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Sync
 *
 * Atena Sync is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Sync is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Sync.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.sync

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val info = TextView(this).apply {
            text = "Atena Sync ${BuildInfo.VERSION}\nLoopback: http://127.0.0.1:$DEFAULT_LOOPBACK_PORT\nLAN: $DEFAULT_LAN_PORT"
            textSize = 16f
        }
        val start = Button(this).apply {
            text = getString(R.string.start_service)
            setOnClickListener { SyncForegroundService.start(this@MainActivity) }
        }
        val stop = Button(this).apply {
            text = getString(R.string.stop_service)
            setOnClickListener { SyncForegroundService.stop(this@MainActivity) }
        }
        root.addView(info)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
        SyncForegroundService.start(this)
    }
}
