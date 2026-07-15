package com.eventledger.account.api;

import com.eventledger.account.service.AccountTransactionService;
import com.eventledger.account.service.ApplyOutcome;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountTransactionController {

    private final AccountTransactionService transactionService;

    public AccountTransactionController(AccountTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody ApplyTransactionRequest request) {
        ApplyOutcome outcome = transactionService.applyTransaction(accountId, request);
        HttpStatus status = outcome.newlyApplied() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(TransactionResponse.from(outcome));
    }
}
