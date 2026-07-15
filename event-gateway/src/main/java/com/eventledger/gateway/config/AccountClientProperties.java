package com.eventledger.gateway.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@ConfigurationProperties(prefix = "clients.account")
@Validated
public record AccountClientProperties(@NotNull URI baseUrl) {
}
