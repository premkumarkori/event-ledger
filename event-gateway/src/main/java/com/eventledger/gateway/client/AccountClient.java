package com.eventledger.gateway.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AccountClient {

    private static final String IDEMPOTENCY_CONFLICT = "urn:event-ledger:problem:idempotency-conflict";
    private static final String CURRENCY_CONFLICT = "urn:event-ledger:problem:currency-conflict";

    private final RestClient accountRestClient;
    private final JsonMapper jsonMapper;

    public AccountClient(RestClient accountRestClient, JsonMapper jsonMapper) {
        this.accountRestClient = accountRestClient;
        this.jsonMapper = jsonMapper;
    }

    public AccountApplyOutcome apply(String accountId, AccountClientRequest request) {
        ResponseEntity<String> response;
        try {
            response = accountRestClient.post()
                    .uri("/accounts/{accountId}/transactions", accountId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> true, (ignoredRequest, ignoredResponse) -> {
                    })
                    .toEntity(String.class);
        } catch (ResourceAccessException transportFailure) {
            return AccountApplyOutcome.retryableUnconfirmed();
        }
        return classify(accountId, request, response.getStatusCode(), response.getBody());
    }

    private AccountApplyOutcome classify(
            String accountId, AccountClientRequest request, HttpStatusCode status, String body) {
        int code = status.value();
        if (code == 200 || code == 201) {
            return classifySuccessBody(accountId, request, body);
        }
        if (code == 409) {
            return classifyConflict(body);
        }
        if (status.is5xxServerError()) {
            return AccountApplyOutcome.retryableUnconfirmed();
        }
        return AccountApplyOutcome.contractError();
    }

    private AccountApplyOutcome classifySuccessBody(
            String accountId, AccountClientRequest request, String body) {
        AccountClientResponse parsed = readResponse(body);
        if (parsed == null || !matchesRequest(accountId, request, parsed)) {
            return AccountApplyOutcome.contractError();
        }
        return AccountApplyOutcome.confirmed();
    }

    private boolean matchesRequest(
            String accountId, AccountClientRequest request, AccountClientResponse response) {
        return response.appliedAt() != null
                && request.eventId().equals(response.eventId())
                && accountId.equals(response.accountId())
                && request.type() == response.type()
                && response.amount() != null
                && request.amount().compareTo(response.amount()) == 0
                && request.currency().equals(response.currency())
                && request.eventTimestamp().equals(response.eventTimestamp());
    }

    private AccountApplyOutcome classifyConflict(String body) {
        String type = problemType(body);
        if (IDEMPOTENCY_CONFLICT.equals(type)) {
            return AccountApplyOutcome.terminalConflict(AccountApplyOutcome.ConflictType.IDEMPOTENCY);
        }
        if (CURRENCY_CONFLICT.equals(type)) {
            return AccountApplyOutcome.terminalConflict(AccountApplyOutcome.ConflictType.CURRENCY);
        }
        return AccountApplyOutcome.contractError();
    }

    private AccountClientResponse readResponse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(body, AccountClientResponse.class);
        } catch (JacksonException malformed) {
            return null;
        }
    }

    private String problemType(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readTree(body).path("type").asString();
        } catch (JacksonException malformed) {
            return null;
        }
    }
}
