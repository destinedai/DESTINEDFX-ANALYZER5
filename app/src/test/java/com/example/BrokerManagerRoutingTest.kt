package com.example

import com.example.data.broker.BrokerManager
import com.example.data.broker.BrokerType
import com.example.data.broker.UnavailableBrokerGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BrokerManagerRoutingTest {
    @Test fun derivUsesItsOwnGatewayAndOandaFailsClosed() {
        val deriv = UnavailableBrokerGateway(BrokerType.DERIV, "Deriv test")
        val mt5 = UnavailableBrokerGateway(BrokerType.MT5, "MT5 test")
        val manager = BrokerManager(derivGateway = deriv, mt5Gateway = mt5)

        assertSame(deriv, manager.getGateway(BrokerType.DERIV))
        assertSame(mt5, manager.getGateway(BrokerType.MT5))
        val oanda = manager.getGateway(BrokerType.OANDA)
        assertEquals(BrokerType.OANDA, oanda.brokerType)
        assertEquals("OANDA", oanda.displayName)

        manager.selectBroker(BrokerType.DERIV)
        assertSame(deriv, manager.activeBroker.value)
        manager.selectBroker(BrokerType.OANDA)
        assertSame(oanda, manager.activeBroker.value)
    }
}
