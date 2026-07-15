package com.eventledger.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountClientProperties.class)
public class AccountClientConfiguration {

    @Bean
    ClientHttpRequestFactoryBuilderCustomizer<HttpComponentsClientHttpRequestFactoryBuilder>
            disableApacheAutomaticRetries() {
        return builder -> builder.withHttpClientCustomizer(
                httpClientBuilder -> httpClientBuilder.disableAutomaticRetries());
    }

    @Bean
    RestClient accountRestClient(RestClient.Builder builder, AccountClientProperties properties) {
        return builder.baseUrl(properties.baseUrl().toString()).build();
    }
}
