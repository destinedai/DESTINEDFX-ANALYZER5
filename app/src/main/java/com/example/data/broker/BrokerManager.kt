package com.example.data.broker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BrokerManager(
    val derivGateway: DerivBrokerGatewayAdapter,
    val mt5Gateway: MT5BrokerGatewayAdapter
) {
    private val _selectedBrokerType = MutableStateFlow(BrokerType.DERIV)
    val selectedBrokerType: StateFlow<BrokerType> = _selectedBrokerType.asStateFlow()

    private val _activeBroker = MutableStateFlow<BrokerGateway>(derivGateway)
    val activeBroker: StateFlow<BrokerGateway> = _activeBroker.asStateFlow()

    fun selectBroker(type: BrokerType) {
        _selectedBrokerType.value = type
        _activeBroker.value = when (type) {
            BrokerType.DERIV -> derivGateway
            BrokerType.MT5 -> mt5Gateway
            BrokerType.OANDA -> derivGateway
        }
    }

    fun getGateway(type: BrokerType): BrokerGateway = when (type) {
        BrokerType.DERIV -> derivGateway
        BrokerType.MT5 -> mt5Gateway
        BrokerType.OANDA -> derivGateway
    }
}
