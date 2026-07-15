package com.eventledger.account.api;

import com.eventledger.account.health.AccountHealthResponse;
import com.eventledger.account.health.LocalDatabaseCheck;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private static final String SERVICE_NAME = "account-service";

    private final LocalDatabaseCheck databaseCheck;

    public HealthController(LocalDatabaseCheck databaseCheck) {
        this.databaseCheck = databaseCheck;
    }

    @GetMapping("/health")
    public ResponseEntity<AccountHealthResponse> getAccountHealth() {
        boolean databaseUp = databaseCheck.isUp();
        AccountHealthResponse body = new AccountHealthResponse(
                databaseUp ? "UP" : "DOWN",
                SERVICE_NAME,
                Map.of("database", databaseUp ? "UP" : "DOWN"));
        HttpStatus status = databaseUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }
}
