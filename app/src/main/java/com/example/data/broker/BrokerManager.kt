package com.example.data.broker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BrokerManager(
    val derivGateway: DerivBrokerGatewayAdapter,
    val mt5Gateway: MT5BrokerGatewayAdapter
) {
    private val _selectedBrokerType = MutableStateFlow(BrokerType.MT5)
    val selectedBrokerType: StateFlow<BrokerType> = _selectedBrokerType.asStateFlow()

    private val _activeBroker = MutableStateFlow<BrokerGateway>(mt5Gateway)
    val activeBroker: StateFlow<BrokerGateway> = _activeBroker.asStateFlow()

    fun selectBroker(type: BrokerType) {
        _selectedBrokerType.value = type
        _activeBroker.value = when (type) {
            BrokerType.DERIV -> mt5Gateway
            BrokerType.MT5 -> mt5Gateway
            BrokerType.OANDA -> mt5Gateway
        }
    }

    fun getGateway(type: BrokerType): BrokerGateway = when (type) {
        BrokerType.DERIV -> mt5Gateway
        BrokerType.MT5 -> mt5Gateway
        BrokerType.OANDA -> mt5Gateway
    }
}
