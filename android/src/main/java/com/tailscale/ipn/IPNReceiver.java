// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Objects;

/**
 * IPNReceiver allows external applications to start/stop the tailnet connection.
 */
public class IPNReceiver extends BroadcastReceiver {

    public static final String INTENT_CONNECT_VPN = "com.tailscale.ipn.CONNECT_VPN";
    public static final String INTENT_DISCONNECT_VPN = "com.tailscale.ipn.DISCONNECT_VPN";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        final String action = intent.getAction();

        if (Objects.equals(action, INTENT_CONNECT_VPN)) {
            App.get().startUserspaceVPN();
        } else if (Objects.equals(action, INTENT_DISCONNECT_VPN)) {
            App.get().stopUserspaceVPN();
        }
    }
}
