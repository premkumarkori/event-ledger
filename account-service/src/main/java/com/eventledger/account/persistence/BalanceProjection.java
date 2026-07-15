package com.eventledger.account.persistence;

import java.math.BigDecimal;

public record BalanceProjection(String accountId, String currency, BigDecimal balance) {
}
