package com.eventledger.gateway.health;

import java.util.Map;

public record GatewayHealthResponse(
        String status,
        String service,
        Map<String, String> checks) {
}
