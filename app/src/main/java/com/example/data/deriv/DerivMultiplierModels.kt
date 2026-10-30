package com.example.data.deriv

import com.example.domain.model.SignalDirection
import com.destinedfx.core.broker.DerivContractLimits

/** The only direct Deriv products supported by this app's phone execution mode. */
enum class DerivMultiplierProduct(val contractType: String) {
    MULTUP("MULTUP"),
    MULTDOWN("MULTDOWN")
}

data class DerivMultiplierIntent(
    val signalId: String,
    val symbol: String,
    val strategyId: String,
    val strategyVersion: String,
    val direction: SignalDirection,
    val product: DerivMultiplierProduct,
    val stake: Double,
    val currency: String,
    val multiplier: Int,
    val stopLossAmount: Double,
    val takeProfitAmount: Double,
    val signalTimestampMs: Long,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class DerivMultiplierValidation(
    val valid: Boolean,
    val reason: String? = null
)

object DerivMultiplierRules {
    const val MAX_RISK_PERCENT = 1.0
    const val MIN_MULTIPLIER = 1
    const val MAX_MULTIPLIER = 1000
    const val MAX_SIGNAL_AGE_MS = 30_000L

    /** The default mobile bot is deliberately single-contract-at-a-time. */
    fun canOpenNewEntry(activeContractCount: Int, unresolvedExecutionCount: Int): Boolean =
        activeContractCount == 0 && unresolvedExecutionCount == 0

    /** The Android foreground bot is DEMO-only until a separate live-execution acceptance is completed. */
    fun isVerifiedDemoAccount(account: DerivAccountInfo?): Boolean =
        account != null &&
            account.loginId.isNotBlank() &&
            account.mode.equals("demo", ignoreCase = true) &&
            account.accountType.equals("demo", ignoreCase = true) &&
            account.demoVerified

    /** Floor monetary stake to cents so rounding can never push worst-case loss over the 1% cap. */
    fun safeStakeForBalance(balance: Double): Double {
        if (!balance.isFinite() || balance <= 0.0) return 0.0
        return java.math.BigDecimal.valueOf(balance)
            .multiply(java.math.BigDecimal.valueOf(MAX_RISK_PERCENT / 100.0))
            .setScale(2, java.math.RoundingMode.DOWN)
            .toDouble()
    }

    /** The trade plan must be complete, on the correct side of entry, and at least 2R. No stop shrinking or inference. */
    fun planConsistencyError(direction: SignalDirection, entry: Double, stopLoss: Double, takeProfit: Double): String? {
        if (!entry.isFinite() || !stopLoss.isFinite() || !takeProfit.isFinite() || entry <= 0.0 || stopLoss <= 0.0 || takeProfit <= 0.0) {
            return "PLAN_INCOMPLETE: entry, stop loss and take profit must all be valid prices"
        }
        val risk: Double
        val reward: Double
        when (direction) {
            SignalDirection.LONG -> { if (!(stopLoss < entry && takeProfit > entry)) return "PLAN_INVALID: LONG needs stop below entry and target above"; risk = entry - stopLoss; reward = takeProfit - entry }
            SignalDirection.SHORT -> { if (!(stopLoss > entry && takeProfit < entry)) return "PLAN_INVALID: SHORT needs stop above entry and target below"; risk = stopLoss - entry; reward = entry - takeProfit }
            else -> return "SIGNAL_INVALID: WAIT is not executable"
        }
        if (reward / risk + 1e-9 < 2.0) return "RR_TOO_LOW: reward/risk is below the 2.0R minimum"
        return null
    }

    /** These are synthetic-index families, never Forex/CFD symbols. */
    fun isSupportedUnderlying(symbol: String): Boolean {
        val normalized = symbol.trim().uppercase()
        return normalized.startsWith("1HZ") || normalized.startsWith("R_") ||
            normalized.startsWith("BOOM") || normalized.startsWith("CRASH") ||
            normalized.startsWith("JUMP") || normalized.startsWith("STX")
    }

    fun validate(
        intent: DerivMultiplierIntent,
        accountBalance: Double,
        accountCurrency: String,
        nowMs: Long = System.currentTimeMillis()
    ): DerivMultiplierValidation {
        fun invalid(message: String) = DerivMultiplierValidation(false, message)
        if (intent.signalId.isBlank()) return invalid("SIGNAL_INVALID: signal ID is required")
        if (intent.strategyId.isBlank() || intent.strategyVersion.isBlank()) return invalid("STRATEGY_INVALID: strategy ID and version are required")
        if (intent.direction !in setOf(SignalDirection.LONG, SignalDirection.SHORT)) return invalid("SIGNAL_INVALID: WAIT is not executable")
        if (!isSupportedUnderlying(intent.symbol)) return invalid("UNSUPPORTED_PRODUCT_MAPPING: ${intent.symbol} is not a supported synthetic Multiplier underlying")
        val expectedProduct = if (intent.direction == SignalDirection.LONG) DerivMultiplierProduct.MULTUP else DerivMultiplierProduct.MULTDOWN
        if (intent.product != expectedProduct) return invalid("PRODUCT_DIRECTION_MISMATCH: ${intent.product.contractType} does not match ${intent.direction}")
        if (intent.currency.isBlank() || !intent.currency.matches(Regex("[A-Za-z0-9]{2,20}"))) return invalid("CURRENCY_INVALID: account currency is invalid")
        if (!intent.currency.equals(accountCurrency, ignoreCase = true)) return invalid("CURRENCY_MISMATCH: intent currency does not match the verified account")
        if (!accountBalance.isFinite() || accountBalance <= 0.0) return invalid("ACCOUNT_INVALID: verified account balance is unavailable")
        if (!intent.stake.isFinite() || intent.stake <= 0.0) return invalid("STAKE_INVALID: stake must be positive")
        val minimumStake = DerivContractLimits.getMinimumStake(intent.symbol, intent.product.contractType)
        if (intent.stake + 1e-9 < minimumStake) return invalid("STAKE_INVALID: stake=${intent.stake} is below the known Deriv minimum=${minimumStake} for ${intent.symbol}")
        if (intent.stake > accountBalance * MAX_RISK_PERCENT / 100.0 + 1e-9) return invalid("RISK_LIMIT: maximum loss exceeds 1% of the verified account balance")
        if (intent.multiplier !in MIN_MULTIPLIER..MAX_MULTIPLIER) return invalid("MULTIPLIER_INVALID: unsupported multiplier")
        if (!intent.stopLossAmount.isFinite() || intent.stopLossAmount <= 0.0 || intent.stopLossAmount > intent.stake + 1e-9) return invalid("PROTECTION_INVALID: stop_loss must be a positive cash amount no greater than stake")
        if (!intent.takeProfitAmount.isFinite() || intent.takeProfitAmount < intent.stopLossAmount * 2.0 - 1e-9) return invalid("PROTECTION_INVALID: take_profit must represent at least 2x the cash loss limit")
        if (intent.signalTimestampMs <= 0L || nowMs - intent.signalTimestampMs !in 0..MAX_SIGNAL_AGE_MS) return invalid("SIGNAL_STALE: signal is missing or older than 30 seconds")
        return DerivMultiplierValidation(true)
    }
}
