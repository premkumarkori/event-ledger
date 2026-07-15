package com.eventledger.account.api;

import com.eventledger.account.service.AccountQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    private final AccountQueryService queryService;

    public AccountController(AccountQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/accounts/{accountId}/balance")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        return queryService.getBalance(accountId);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountDetailsResponse getDetails(@PathVariable String accountId) {
        return queryService.getDetails(accountId);
    }
}
