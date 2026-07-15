package com.eventledger.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AccountServiceApplicationTest {

    @Test
    void contextLoadsWithServiceName(ApplicationContext context) {
        assertThat(context.getEnvironment().getProperty("spring.application.name"))
                .isEqualTo("account-service");
    }

    @Test
    void testsRunAgainstIsolatedInMemoryDatabase(ApplicationContext context) {
        String datasourceUrl = context.getEnvironment().getProperty("spring.datasource.url");
        assertThat(datasourceUrl).startsWith("jdbc:h2:mem:account-test-");
        assertThat(datasourceUrl).doesNotContain("runtime-data");
    }
}
