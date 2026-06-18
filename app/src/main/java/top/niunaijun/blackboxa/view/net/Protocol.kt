package top.niunaijun.blackboxa.view.net

enum class Protocol(val label: String, val colorHex: String) {
    HTTP("HTTP",  "#80CBC4"),
    HTTPS("TLS",  "#FF8A65"),
    WS("WS",    "#FFD54F"),
    WSS("WSS",   "#FFCC02"),
    DNS("DNS",   "#CE93D8"),
    UDP("UDP",   "#A5D6A7"),
    TCP("TCP",   "#4FC3F7"),
    UNKNOWN("???", "#78909C")
}

enum class ConnStatus { ALIVE, CLOSING, CLOSED, ERROR }

enum class Direction { OUTBOUND, INBOUND, BOTH }
