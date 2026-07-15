package com.eventledger.account.api;

import java.math.BigDecimal;
import java.util.List;

public record AccountDetailsResponse(
        String accountId,
        String currency,
        BigDecimal balance,
        List<TransactionSummaryResponse> recentTransactions
) {
}
