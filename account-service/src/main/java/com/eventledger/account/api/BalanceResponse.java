package com.eventledger.account.api;

import java.math.BigDecimal;
import java.time.Instant;

public record BalanceResponse(
        String accountId,
        String currency,
        BigDecimal balance,
        Instant asOf
) {
}
