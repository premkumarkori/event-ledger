package com.eventledger.gateway.client;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountBalanceResponse(
        String accountId,
        String currency,
        BigDecimal balance,
        Instant asOf
) {
}
