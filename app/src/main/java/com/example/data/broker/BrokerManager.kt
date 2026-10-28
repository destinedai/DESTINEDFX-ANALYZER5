package com.example.data.broker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BrokerManager(
    val derivGateway: BrokerGateway,
    val mt5Gateway: BrokerGateway
) {
    private val unavailableOanda: BrokerGateway = UnavailableBrokerGateway(BrokerType.OANDA, "OANDA")
    private val _selectedBrokerType = MutableStateFlow(BrokerType.MT5)
    val selectedBrokerType: StateFlow<BrokerType> = _selectedBrokerType.asStateFlow()

    private val _activeBroker = MutableStateFlow<BrokerGateway>(mt5Gateway)
    val activeBroker: StateFlow<BrokerGateway> = _activeBroker.asStateFlow()

    fun selectBroker(type: BrokerType) {
        _selectedBrokerType.value = type
        _activeBroker.value = when (type) {
            BrokerType.DERIV -> derivGateway
            BrokerType.MT5 -> mt5Gateway
            BrokerType.OANDA -> unavailableOanda
        }
    }

    fun getGateway(type: BrokerType): BrokerGateway = when (type) {
        BrokerType.DERIV -> derivGateway
        BrokerType.MT5 -> mt5Gateway
        BrokerType.OANDA -> unavailableOanda
    }
}
