# TailMon

A tailnet monitor for Android. Connects to your Tailscale network in userspace — no VPN permission required — and shows your tailnet peers.

## What it does

- Connects to tailnet entirely via netstack (gVisor) in-process
- No Android VPN permission or kernel TUN device needed
- Shows all peers on your tailnet
- Taildrop file sharing

## What it doesn't do

- Route arbitrary Android app traffic through tailnet (no kernel VPN tunnel)
- Exit nodes, subnet routing, split tunneling

## Building

Requires:
- Android SDK
- NDK (installed via Android Studio or `make androidsdk`)

```bash
make apk
adb install -r tailscale-debug.apk
```

## Credits

Built on top of [tailscale-android](https://github.com/tailscale/tailscale-android) by Tailscale Inc., licensed under BSD-3-Clause.

WireGuard® is a registered trademark of Jason A. Donenfeld.
