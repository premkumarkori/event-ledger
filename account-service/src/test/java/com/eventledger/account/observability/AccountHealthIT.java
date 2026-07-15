package com.eventledger.account.observability;

import com.eventledger.account.health.LocalDatabaseCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountHealthIT {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final JsonMapper json = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @MockitoSpyBean
    private LocalDatabaseCheck databaseCheck;

    @BeforeEach
    void resetSpy() {
        reset(databaseCheck);
    }

    @Test
    void getAccountHealth_shouldReturnUp_whenLocalDatabaseCheckSucceeds() throws Exception {
        HttpResponse<String> response = getHealth();
        JsonNode body = json.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("service").asText()).isEqualTo("account-service");
        assertThat(body.path("checks").path("database").asText()).isEqualTo("UP");
        assertThat(body.path("checks").properties()).hasSize(1);
    }

    @Test
    void getAccountHealth_shouldReturnDown_whenLocalDatabaseCheckFails() throws Exception {
        doReturn(false).when(databaseCheck).isUp();

        HttpResponse<String> response = getHealth();
        JsonNode body = json.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(body.path("status").asText()).isEqualTo("DOWN");
        assertThat(body.path("service").asText()).isEqualTo("account-service");
        assertThat(body.path("checks").path("database").asText()).isEqualTo("DOWN");
    }

    private HttpResponse<String> getHealth() throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
