package com.tunnelgo.app

enum class ConnectionPhase { STOPPED, CONNECTING, TUNNEL_UP, CONNECTED, RECONNECTING, ERROR }

/** Reducer thuần: CONNECTED chỉ hợp lệ sau khi tunnel-up đã qua health check. */
object ConnectionState {
    fun transition(current: ConnectionPhase, event: Event): ConnectionPhase = when (event) {
        Event.START -> if (current == ConnectionPhase.STOPPED || current == ConnectionPhase.ERROR) ConnectionPhase.CONNECTING else current
        Event.TUNNEL_ESTABLISHED -> if (current == ConnectionPhase.CONNECTING || current == ConnectionPhase.RECONNECTING) ConnectionPhase.TUNNEL_UP else current
        Event.HEALTH_CHECK_PASSED -> if (current == ConnectionPhase.TUNNEL_UP) ConnectionPhase.CONNECTED else current
        Event.NETWORK_CHANGED -> if (current == ConnectionPhase.CONNECTED || current == ConnectionPhase.TUNNEL_UP) ConnectionPhase.RECONNECTING else current
        Event.FAIL -> ConnectionPhase.ERROR
        Event.STOP -> ConnectionPhase.STOPPED
    }
    enum class Event { START, TUNNEL_ESTABLISHED, HEALTH_CHECK_PASSED, NETWORK_CHANGED, FAIL, STOP }
}
