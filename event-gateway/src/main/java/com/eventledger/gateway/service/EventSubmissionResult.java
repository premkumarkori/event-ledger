package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.StoredEvent;

public record EventSubmissionResult(StoredEvent event, boolean created) {
}
