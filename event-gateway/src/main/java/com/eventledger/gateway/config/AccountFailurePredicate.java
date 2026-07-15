package com.eventledger.gateway.config;

import com.eventledger.gateway.client.AccountInfrastructureException;

import java.util.function.Predicate;

public class AccountFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable failure) {
        return failure instanceof AccountInfrastructureException;
    }
}
