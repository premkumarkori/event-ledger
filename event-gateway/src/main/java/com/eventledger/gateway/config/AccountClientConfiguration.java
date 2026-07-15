package com.eventledger.gateway.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountClientProperties.class)
public class AccountClientConfiguration {

    @Bean
    CloseableHttpClient accountHttpClient() {
        return HttpClients.custom()
                .disableAutomaticRetries()
                .build();
    }

    @Bean
    RestClient accountRestClient(RestClient.Builder builder,
                                 AccountClientProperties properties,
                                 CloseableHttpClient accountHttpClient) {
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(accountHttpClient);
        return builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
