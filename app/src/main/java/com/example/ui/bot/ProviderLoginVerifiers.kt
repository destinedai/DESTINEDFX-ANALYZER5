package com.example.ui.bot

import com.example.data.broker.BrokerLoginSpec
import com.example.data.broker.LoginConnector
import com.example.data.broker.LoginMode
import com.example.data.broker.SavedBrokerAccountProfile
import com.example.data.ctrader.CTraderOAuthClient
import com.example.data.ctrader.CTraderReadOnlyGateway
import com.example.data.deriv.DerivBrokerGateway
import com.example.data.deriv.DerivConnectionState
import com.example.security.SecretsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class DerivOptionsLoginVerifier(
    private val secrets: SecretsManager,
    private val gateway: DerivBrokerGateway,
) : ProviderLoginVerifier {
    override suspend fun verify(spec: BrokerLoginSpec, form: LoginForm): ConnectObservation {
        require(spec.connector == LoginConnector.DERIV_OPTIONS)
        if (!secrets.isSecureStorageAvailable) throw ConnectSubmissionFailure("SECURE_STORAGE_UNAVAILABLE")
        if (!secrets.saveDerivAppId(form.appId)) throw ConnectSubmissionFailure("FORM_INCOMPLETE")
        val requestedMode = if (form.mode == LoginMode.DEMO) "demo" else "real"
        if (!secrets.saveDerivAccountMode(requestedMode)) throw ConnectSubmissionFailure("SECURE_STORAGE_UNAVAILABLE")
        if (!secrets.saveDerivApiTokenIfAllowed(form.token, requestedMode, form.savePassword)) {
            throw ConnectSubmissionFailure("SECURE_STORAGE_UNAVAILABLE")
        }

        val account = gateway.connectAndAuthorize(
            apiToken = form.token,
            selectedAccountId = form.accountId,
            expectedMode = requestedMode,
        ).getOrElse { throw ConnectSubmissionFailure("PROVIDER_AUTH_FAILED") }
        val metrics = gateway.accountMetrics.value
        val balance = metrics?.balance
        val retrievedAt = gateway.accountMetricsUpdatedAtMs.value
        val now = System.currentTimeMillis()
        if (gateway.connectionState.value != DerivConnectionState.READY ||
            gateway.accountInfo.value != account ||
            account.loginId.isBlank() ||
            account.authenticatedSessionId.isBlank() ||
            account.credentialGeneration.isBlank() ||
            account.accountType != account.mode ||
            account.mode !in setOf("demo", "real") ||
            retrievedAt <= 0L || retrievedAt > now || now - retrievedAt > 15_000L ||
            metrics?.currency.isNullOrBlank() ||
            metrics?.currency?.equals(account.currency, ignoreCase = true) != true ||
            balance == null || !balance.isFinite() || balance < 0.0
        ) {
            throw ConnectSubmissionFailure("PROVIDER_DATA_STALE")
        }
        if (!secrets.saveBrokerAccountProfile(SavedBrokerAccountProfile(
                specId = spec.id,
                accountId = account.loginId,
                server = "",
                mode = if (account.mode == "demo") LoginMode.DEMO else LoginMode.LIVE,
            ))
        ) throw ConnectSubmissionFailure("SECURE_STORAGE_UNAVAILABLE")
        return ConnectObservation(
            stage = ConnectStage.VERIFIED,
            accountMode = if (account.mode == "demo") "DEMO" else "LIVE",
            account = VerifiedAccountDetails(
                login = account.loginId,
                server = "",
                mode = if (account.mode == "demo") "DEMO" else "LIVE",
                currency = metrics?.currency,
                balance = balance,
                equity = null,
                freeMargin = null,
                marginLevel = null,
                leverage = null,
                retrievedAtMs = retrievedAt,
            ),
        )
    }
}

class CTraderLoginVerifier(
    private val secrets: SecretsManager,
    private val oauth: CTraderOAuthClient,
    private val gateway: CTraderReadOnlyGateway,
) : ProviderLoginVerifier {
    override suspend fun verify(spec: BrokerLoginSpec, form: LoginForm): ConnectObservation {
        require(spec.connector == LoginConnector.CTRADER)
        if (!oauth.accessTokenIsFresh()) throw ConnectSubmissionFailure("WAITING_FOR_SPOTWARE_APPROVAL")
        val live = form.mode == LoginMode.LIVE
        val accounts = gateway.listAccounts(live).getOrElse {
            throw ConnectSubmissionFailure("WAITING_FOR_SPOTWARE_APPROVAL")
        }
        val eligibleAccounts = accounts.filter { account ->
            account.isLive == live &&
                account.brokerName.isNotBlank() &&
                (spec.id != "DERIV_CTRADER" || account.brokerName.contains("deriv", ignoreCase = true))
        }
        val accountId = form.accountId?.toLongOrNull()
        if (accountId == null) {
            if (eligibleAccounts.isEmpty()) throw ConnectSubmissionFailure("ACCOUNT_IDENTITY_MISMATCH")
            return ConnectObservation(
                stage = null,
                accountChoices = eligibleAccounts.map { option ->
                    LoginAccountChoice(
                        id = option.accountId.toString(),
                        label = "${option.brokerName} · ${maskLogin(option.traderLogin.toString())}",
                        mode = if (option.isLive) LoginMode.LIVE else LoginMode.DEMO,
                    )
                },
            )
        }
        val selected = eligibleAccounts.firstOrNull { it.accountId == accountId }
            ?: throw ConnectSubmissionFailure("ACCOUNT_IDENTITY_MISMATCH")
        val verified = gateway.verify(selected.brokerName, live, selected.accountId).getOrElse {
            throw ConnectSubmissionFailure("ACCOUNT_IDENTITY_MISMATCH")
        }
        val now = System.currentTimeMillis()
        if (verified.accountId != selected.accountId ||
            verified.isLive != live ||
            !verified.brokerName.equals(selected.brokerName, ignoreCase = true) ||
            verified.receivedAtMs <= 0L || verified.receivedAtMs > now || now - verified.receivedAtMs > 15_000L ||
            verified.balance < 0.0 || !verified.balance.isFinite()
        ) throw ConnectSubmissionFailure("ACCOUNT_IDENTITY_MISMATCH")

        val symbol = gateway.listSymbols().getOrElse {
            throw ConnectSubmissionFailure("PROVIDER_DATA_STALE")
        }.firstOrNull() ?: throw ConnectSubmissionFailure("PROVIDER_DATA_STALE")
        gateway.subscribeReadOnlySpot(symbol.symbolId).getOrElse {
            throw ConnectSubmissionFailure("PROVIDER_DATA_STALE")
        }
        val freshQuote = withTimeoutOrNull(15_000L) {
            while (gateway.quotes.value?.isFresh(verified.accountId, symbol.symbolId) != true) delay(100L)
            true
        } == true
        if (!freshQuote) throw ConnectSubmissionFailure("PROVIDER_DATA_STALE")
        if (!secrets.saveBrokerAccountProfile(SavedBrokerAccountProfile(
                specId = spec.id,
                accountId = verified.accountId.toString(),
                server = verified.brokerName,
                mode = if (verified.isLive) LoginMode.LIVE else LoginMode.DEMO,
                login = verified.traderLogin.toString(),
            ))
        ) {
            throw ConnectSubmissionFailure("SECURE_STORAGE_UNAVAILABLE")
        }
        return ConnectObservation(
            stage = ConnectStage.VERIFIED,
            accountMode = if (verified.isLive) "LIVE" else "DEMO",
            account = VerifiedAccountDetails(
                login = verified.traderLogin.toString(),
                server = verified.brokerName,
                mode = if (verified.isLive) "LIVE" else "DEMO",
                currency = null,
                balance = verified.balance,
                equity = null,
                freeMargin = null,
                marginLevel = null,
                leverage = verified.leverage.takeIf { it.isFinite() && it > 0.0 }?.toInt(),
                retrievedAtMs = verified.receivedAtMs,
            ),
        )
    }

    private fun maskLogin(value: String): String =
        if (value.length <= 4) "••••" else "••••${value.takeLast(4)}"

}
