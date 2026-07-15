package com.eventledger.gateway.api;

import com.eventledger.gateway.health.GatewayHealthResponse;
import com.eventledger.gateway.health.GatewayHealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final GatewayHealthService healthService;

    public HealthController(GatewayHealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public ResponseEntity<GatewayHealthResponse> getGatewayHealth() {
        GatewayHealthResponse body = healthService.currentHealth();
        HttpStatus status = "DOWN".equals(body.status()) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }
}
