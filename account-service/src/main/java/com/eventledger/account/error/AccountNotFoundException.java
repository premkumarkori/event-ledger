package com.eventledger.account.error;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("no account with applied transactions for " + accountId);
    }
}
