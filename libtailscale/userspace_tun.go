// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"os"

	"github.com/tailscale/wireguard-go/tun"
)

// nullTUN is an in-memory tun.Device used in userspace networking mode.
// It does not require Android VPN permissions; all tailnet traffic flows
// through netstack (gVisor) entirely within the Go process.
type nullTUN struct {
	events chan tun.Event
	closed chan struct{}
}

func newNullTUN() *nullTUN {
	t := &nullTUN{
		events: make(chan tun.Event, 1),
		closed: make(chan struct{}),
	}
	// Signal to the WireGuard engine that the TUN is ready.
	t.events <- tun.EventUp
	return t
}

func (t *nullTUN) File() *os.File {
	panic("nullTUN: File() not supported in userspace mode")
}

// Read blocks until Close is called. In userspace mode all outbound
// traffic originates from netstack and is injected directly into the
// WireGuard engine, so the TUN read path is never needed.
func (t *nullTUN) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	<-t.closed
	return 0, os.ErrClosed
}

// Write discards packets. In userspace mode inbound packets are
// intercepted by netstack before they reach the TUN write path.
func (t *nullTUN) Write(bufs [][]byte, offset int) (int, error) {
	select {
	case <-t.closed:
		return 0, os.ErrClosed
	default:
		return len(bufs), nil
	}
}

func (t *nullTUN) MTU() (int, error)     { return defaultMTU, nil }
func (t *nullTUN) Name() (string, error) { return "userspace-ts", nil }
func (t *nullTUN) Events() <-chan tun.Event { return t.events }
func (t *nullTUN) BatchSize() int          { return 1 }

func (t *nullTUN) Close() error {
	select {
	case <-t.closed:
		// already closed
	default:
		close(t.closed)
	}
	return nil
}
