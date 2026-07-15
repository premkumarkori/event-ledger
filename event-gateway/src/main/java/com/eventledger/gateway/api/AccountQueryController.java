package com.eventledger.gateway.api;

import com.eventledger.gateway.client.AccountBalanceResponse;
import com.eventledger.gateway.service.AccountCallExecutor;
import com.eventledger.gateway.service.EventIdentifierValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountQueryController {

    private final AccountCallExecutor accountCallExecutor;
    private final EventIdentifierValidator identifierValidator;

    public AccountQueryController(AccountCallExecutor accountCallExecutor,
                                  EventIdentifierValidator identifierValidator) {
        this.accountCallExecutor = accountCallExecutor;
        this.identifierValidator = identifierValidator;
    }

    @GetMapping("/accounts/{accountId}/balance")
    public AccountBalanceResponse getBalance(@PathVariable String accountId) {
        identifierValidator.requireValid("accountId", accountId);
        return accountCallExecutor.getBalance(accountId);
    }
}
