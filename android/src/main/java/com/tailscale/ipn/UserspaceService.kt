// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog

/**
 * UserspaceService is a foreground service that runs Tailscale in userspace
 * networking mode. Unlike IPNService, it does NOT extend VpnService and
 * therefore requires no VPN permission. All tailnet traffic is handled
 * entirely within the Go process via netstack (gVisor); no kernel TUN device
 * is created and no system-wide VPN tunnel is established.
 */
class UserspaceService : Service() {
    private val TAG = "UserspaceService"
    private lateinit var app: App

    override fun onCreate() {
        super.onCreate()
        app = App.get()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        when (intent?.action) {
            ACTION_STOP -> {
                app.setWantRunning(false)
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                showForegroundNotification()
                app.setWantRunning(true)
                START_STICKY
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        app.setWantRunning(false)
        super.onDestroy()
    }

    private fun showForegroundNotification() {
        try {
            val hideDisconnectAction = MDMSettings.forceEnabled.flow.value.value
            val exitNodeName =
                UninitializedApp.getExitNodeName(Notifier.prefs.value, Notifier.netmap.value)
            startForeground(
                UninitializedApp.STATUS_NOTIFICATION_ID,
                UninitializedApp.get()
                    .buildStatusNotification(true, hideDisconnectAction, exitNodeName))
        } catch (e: Exception) {
            TSLog.e(TAG, "Failed to start foreground notification: $e")
        }
    }

    companion object {
        const val ACTION_START = "com.tailscale.ipn.START_USERSPACE"
        const val ACTION_STOP = "com.tailscale.ipn.STOP_USERSPACE"
    }
}
