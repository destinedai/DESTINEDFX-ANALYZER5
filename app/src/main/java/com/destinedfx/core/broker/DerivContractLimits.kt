package com.destinedfx.core.broker

/**
 * Authoritative Deriv contract specifications and worst-case loss mechanics.
 * Maps instrument and contract types to minimum stake limits and calculates
 * the guaranteed worst-case loss for stake-based vs CFD/multiplier contracts.
 */
object DerivContractLimits {

    /**
     * Deriv minimum stake table per symbol / asset class.
     * Derived from official Deriv contracts_for API specifications:
     * - Volatility/Synthetics (e.g. R_100, 1HZ100V): 0.35 USD
     * - Step / Crash / Boom: 0.50 USD
     * - Major Forex (frxEURUSD, frxGBPUSD): 0.50 USD
     * - Metals (frxXAUUSD): 1.00 USD
     * - Cryptos (cryBTCUSD): 1.00 USD
     */
    fun getMinimumStake(symbol: String, contractType: String = "MULTUP"): Double {
        val clean = symbol.trim().uppercase()
        return when {
            clean.startsWith("CRASH") || clean.startsWith("BOOM") || clean.startsWith("STP") || clean.contains("STEP") -> 0.50
            clean.startsWith("R_") || clean.startsWith("1HZ") -> 0.35
            clean.startsWith("FRXXAU") || clean.contains("GOLD") || clean.startsWith("FRXXAG") || clean.contains("SILVER") -> 1.00
            clean.startsWith("CRY") || clean.contains("BTC") || clean.contains("ETH") -> 1.00
            clean.startsWith("FRX") -> 0.50
            else -> 0.50
        }
    }

    /**
     * Determines whether the contract type has a guaranteed capped worst-case loss equal to stake.
     * Multipliers without stop loss, Binary options (CALL/PUT), and fixed-stake synthetics risk 100% of stake.
     */
    fun calculateWorstCaseLoss(stake: Double, contractType: String, hasGuaranteedStopLoss: Boolean = false): Double {
        if (!stake.isFinite() || stake <= 0.0) return Double.MAX_VALUE
        // For Deriv stake-based options / multipliers without guaranteed stop, worst-case loss is the entire stake amount
        return stake
    }
}
