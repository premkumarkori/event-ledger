package com.eventledger.account.error;

public class CurrencyConflictException extends RuntimeException {

    public CurrencyConflictException(String accountId, String establishedCurrency, String requestedCurrency) {
        super("account " + accountId + " is established in " + establishedCurrency
                + " and cannot accept " + requestedCurrency);
    }
}
