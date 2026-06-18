---
name: Network Analyzer architecture
description: VPN-based packet capture service design for the Network Analyzer feature
---

Package: top.niunaijun.blackboxa.view.net

VPN approach: Android VpnService opens a TUN fd (TUN_IP=10.99.0.1/24, route 0.0.0.0/0).
TCP relay via TcpSession (protected java.net.Socket). 
UDP relay via UdpSession (protected java.net.DatagramSocket).

**Container-only scoping rule:**
Always call `builder.addAllowedApplication(packageName)` on the VPN Builder — no parameters, no "all traffic" mode.
This restricts the VPN to the BlackBox host package only, which includes ALL virtual apps (they share the host UID).
Never pass a specific inner-app package to addAllowedApplication — inner apps aren't separate packages from VPN's perspective.

**Critical ordering rule:**
Call VpnService.protect(socket) BEFORE the socket connects/sends anything.
For TcpSession: protect(session.socket) → handlePacket() → socket.connect() happens inside.
For UdpSession: protect(session.socket) → session.start() (starts receive relay thread).
UdpSession.start() is separate from constructor for this reason — do NOT call relay.submit() in init{}.

Socket visibility: TcpSession.socket and UdpSession.socket are `internal val` (not private)
so NetworkAnalyzerVpnService in the same package can call protect() on them.

**Raw data storage:**
PacketEvent now stores `rawData: ByteArray?` capped at PacketEvent.MAX_RAW_PER_EVENT (4096 bytes).
PacketEvent is a regular class (not data class) — ByteArray equality is broken in data classes.
Events capped at 200 per ConnectionRecord (up from 100). TcpSession and UdpSession both store raw bytes.

**Export format (ConnectionExporter):**
ZIP saved to context.getExternalFilesDir(DIRECTORY_DOWNLOADS) — no permission needed any API level.
Folder per connection: `{proto}_{dstPort}_{host}/`. 
File naming: app_N.bin (outbound), server_N.bin (first inbound), server_N.M.bin (consecutive inbound).

**Singleton tracker:** NetworkAnalyzerVpnService.tracker (companion object ConnectionTracker).
Added `getById(id: Long)` for ConnectionDetailActivity lookup without serialization.

**ConnectionDetailActivity:** receives connection id via Intent, looks up from tracker.getById().
Shows hex dump per packet event, PARSE button runs ProtobufParser (manual varint decoder), 
successful parse → pulse green animation → toggle button to switch raw⇄parsed.

**ProtobufParser:** manual implementation (no external library). Handles wire types 0,1,2,5.
Heuristic looksLikeString: >85% printable ASCII → display as string; else try nested message or bytes.

**Theme note:** Theme.BlackBox is NoActionBar — no supportActionBar. Use custom TextView back buttons 
in layouts (btn_back). onCreateOptionsMenu is never called. All buttons must be wired in the layout XML.

TLS SNI extraction: parse ClientHello extensions (type 0x0000) from first bytes of TCP payload.
HTTP inspection: parse first 4096B of TCP payload for method/host/path.
WebSocket: detected via HTTP Upgrade header — path prefixed with "WS:" in ConnectionRecord.

Session key format: "$proto|$srcIp:$srcPort→$dstIp:$dstPort"
Ring buffer caps: 500 max connections (evict oldest), 200 packet events per connection.

**Why:** Full user-space TCP relay without a library (no tun2socks/lwIP dependency).
Simple seq/ack tracking works for the common case; edge cases (TCP options, OOO packets) silently degrade.
