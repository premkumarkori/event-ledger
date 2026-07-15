package com.eventledger.account.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountTransactionRepository
        extends JpaRepository<AccountTransactionEntity, String> {

    List<AccountTransactionEntity>
    findTop20ByAccountIdOrderByEventTimestampDescEventIdDesc(String accountId);

    boolean existsByAccountId(String accountId);

    @Query("""
            SELECT new com.eventledger.account.persistence.BalanceProjection(
                t.accountId,
                t.currency,
                SUM(CASE WHEN t.type = com.eventledger.account.domain.TransactionType.CREDIT
                         THEN t.amount ELSE -t.amount END))
            FROM AccountTransactionEntity t
            WHERE t.accountId = :accountId
            GROUP BY t.accountId, t.currency
            """)
    Optional<BalanceProjection> findBalanceByAccountId(@Param("accountId") String accountId);
}
