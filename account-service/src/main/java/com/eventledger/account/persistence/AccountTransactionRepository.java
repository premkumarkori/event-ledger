package com.eventledger.account.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountTransactionRepository
        extends JpaRepository<AccountTransactionEntity, String> {

    List<AccountTransactionEntity>
    findTop20ByAccountIdOrderByEventTimestampDescEventIdDesc(String accountId);

    boolean existsByAccountId(String accountId);
}
