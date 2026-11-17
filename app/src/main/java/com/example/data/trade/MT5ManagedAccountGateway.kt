package com.example.data.trade

import com.example.data.broker.HostProfile

/** Minimal managed MT5 login surface so the broker-login hand-off can be tested without a real broker. */
data class MT5ManagedAccountLogin(
    val brokerBrand: String,
    val accountMode: String,
    val login: String,
    val password: String,
    val server: String,
    val accessType: String,
    val savePasswordLocally: Boolean,
    val ownerBootstrapCode: String,
) {
    override fun toString(): String =
        "MT5ManagedAccountLogin(broker=$brokerBrand, mode=$accountMode, login=${login.length} chars, server=$server, secrets=<redacted>)"
}

interface MT5ManagedAccountGateway {
    suspend fun registerManagedAccount(login: MT5ManagedAccountLogin): MT5RemoteBridgeClient.Snapshot
    /** Reads only unauthenticated host metadata; it must not require an app pairing token. */
    suspend fun hostProfile(): HostProfile
    suspend fun state(): MT5RemoteBridgeClient.Snapshot
}
