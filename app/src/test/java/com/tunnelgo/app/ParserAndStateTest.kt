package com.tunnelgo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ParserAndStateTest {
    @Test fun parsesRealityVlessUri() {
        val parsed = VlessParser.parse("vless://00000000-0000-0000-0000-000000000001@edge.example:443?security=reality&pbk=publicKey&sni=www.example.com&sid=abcd&type=tcp#My%20Node")
        assertEquals("edge.example", parsed.host)
        assertEquals(443, parsed.port)
        assertEquals("My Node", parsed.name)
        assertEquals("reality", parsed.security)
    }

    @Test fun rejectsRealityWithoutPublicKey() {
        assertThrows(IllegalArgumentException::class.java) {
            VlessParser.parse("vless://00000000-0000-0000-0000-000000000001@edge.example:443?security=reality&sni=www.example.com")
        }
    }

    @Test fun doesNotMarkConnectedBeforeHealthCheck() {
        var state = ConnectionState.transition(ConnectionPhase.STOPPED, ConnectionState.Event.START)
        state = ConnectionState.transition(state, ConnectionState.Event.TUNNEL_ESTABLISHED)
        assertEquals(ConnectionPhase.TUNNEL_UP, state)
        state = ConnectionState.transition(state, ConnectionState.Event.HEALTH_CHECK_PASSED)
        assertEquals(ConnectionPhase.CONNECTED, state)
    }

    @Test fun ignoresHealthSuccessWithoutTunnel() {
        assertEquals(
            ConnectionPhase.STOPPED,
            ConnectionState.transition(ConnectionPhase.STOPPED, ConnectionState.Event.HEALTH_CHECK_PASSED)
        )
    }
}
