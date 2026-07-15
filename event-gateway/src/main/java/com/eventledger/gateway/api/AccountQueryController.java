package com.eventledger.gateway.api;

import com.eventledger.gateway.client.AccountBalanceResponse;
import com.eventledger.gateway.client.AccountClient;
import com.eventledger.gateway.service.EventIdentifierValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountQueryController {

    private final AccountClient accountClient;
    private final EventIdentifierValidator identifierValidator;

    public AccountQueryController(AccountClient accountClient,
                                  EventIdentifierValidator identifierValidator) {
        this.accountClient = accountClient;
        this.identifierValidator = identifierValidator;
    }

    @GetMapping("/accounts/{accountId}/balance")
    public AccountBalanceResponse getBalance(@PathVariable String accountId) {
        identifierValidator.requireValid("accountId", accountId);
        return accountClient.getBalance(accountId);
    }
}
