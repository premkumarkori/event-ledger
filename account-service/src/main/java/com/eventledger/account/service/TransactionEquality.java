package com.eventledger.account.service;

import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.persistence.AccountTransactionEntity;

final class TransactionEquality {

    private TransactionEquality() {
    }

    static boolean hasSameBusinessValues(
            AccountTransactionEntity stored, TransactionData incoming) {
        return stored.getAccountId().equals(incoming.accountId())
                && stored.getType() == incoming.type()
                && stored.getAmount().compareTo(incoming.amount()) == 0
                && stored.getCurrency().equals(incoming.currency())
                && stored.getEventTimestamp().equals(incoming.eventTimestamp());
    }
}
