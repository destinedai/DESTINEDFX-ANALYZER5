package com.example

import com.example.data.broker.BrokerManager
import com.example.data.broker.BrokerType
import com.example.data.broker.UnavailableBrokerGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BrokerManagerRoutingTest {
    @Test fun derivUsesItsOwnGatewayAndMt5BrandsShareTheManagedTransport() {
        val deriv = UnavailableBrokerGateway(BrokerType.DERIV, "Deriv test")
        val mt5 = UnavailableBrokerGateway(BrokerType.MT5, "MT5 test")
        val manager = BrokerManager(derivGateway = deriv, mt5Gateway = mt5)

        assertSame(deriv, manager.getGateway(BrokerType.DERIV))
        assertSame(mt5, manager.getGateway(BrokerType.MT5))
        assertSame(mt5, manager.getGateway(BrokerType.FBS))
        assertSame(mt5, manager.getGateway(BrokerType.EXNESS))
        assertSame(mt5, manager.getGateway(BrokerType.HFM))

        manager.selectBroker(BrokerType.DERIV)
        assertSame(deriv, manager.activeBroker.value)
        manager.selectBroker(BrokerType.FBS)
        assertSame(mt5, manager.activeBroker.value)
    }
}
