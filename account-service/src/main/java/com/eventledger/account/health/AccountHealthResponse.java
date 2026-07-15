package com.eventledger.account.health;

import java.util.Map;

public record AccountHealthResponse(
        String status,
        String service,
        Map<String, String> checks) {
}
