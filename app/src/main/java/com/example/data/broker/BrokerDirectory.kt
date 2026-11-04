package com.example.data.broker

/**
 * Supported MT5 broker brands only. A brand selection is a user-declared
 * connection route, never proof that the terminal is connected to that broker.
 * Exact server names are supplied by the user's own broker account.
 */
data class BrokerCompany(
    val brandId: String,
    val name: String,
    val servers: List<String> = emptyList()
)

object BrokerDirectory {
    /** FBS, Exness, HFM and actual Deriv MT5 accounts use the shared managed
     * terminal path. Deriv Options/Multipliers API accounts use a separate
     * native connector; selecting Deriv here means a real Deriv MT5 account.
     */
    val allBrokers: List<BrokerCompany> = listOf(
        BrokerCompany("FBS", "FBS Markets Inc."),
        BrokerCompany("EXNESS", "Exness"),
        BrokerCompany("HFM", "HF Markets (HFM)"),
        BrokerCompany("DERIV", "Deriv (MT5 account)")
    )

    fun byBrandId(brandId: String): BrokerCompany? =
        allBrokers.firstOrNull { it.brandId == brandId.trim().uppercase() }

    fun searchBrokers(query: String): List<BrokerCompany> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return allBrokers
        return allBrokers.filter {
            it.name.lowercase().contains(q) || it.brandId.lowercase().contains(q)
        }
    }
}
