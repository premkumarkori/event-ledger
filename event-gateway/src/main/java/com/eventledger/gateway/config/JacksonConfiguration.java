package com.eventledger.gateway.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

@Configuration(proxyBeanMethods = false)
public class JacksonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer preserveMetadataDecimals() {
        return builder -> builder.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
}
