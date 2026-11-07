package com.example.domain.usecase.impl

/** Hard product boundary: this build is for read-only market analysis only. */
object ReadOnlyExecutionPolicy {
    const val REJECTION_CODE = "READ_ONLY_PRODUCT"

    fun requireTradingDisabled(): Nothing = throw UnsupportedOperationException(
        "$REJECTION_CODE: order submission and broker execution are not available"
    )
}
