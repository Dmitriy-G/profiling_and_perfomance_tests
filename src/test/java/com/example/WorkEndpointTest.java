package com.example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkEndpointTest {
    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void usesDefaultWorkload() throws Exception {
        assertWorkResult(client.send(request(""), HttpResponse.BodyHandlers.ofString()), 100);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1000})
    void acceptsWorkloadBounds(int iterations) throws Exception {
        assertWorkResult(client.send(request("?iterations=" + iterations),
                HttpResponse.BodyHandlers.ofString()), iterations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1001", "invalid", "2147483648"})
    void rejectsInvalidWorkload(String iterations) throws Exception {
        HttpResponse<String> response = client.send(request("?iterations=" + iterations),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
    }

    @Test
    void handlesConcurrentRequests() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> responses = IntStream.range(0, 16)
                .mapToObj(index -> client.sendAsync(request("?iterations=5"),
                        HttpResponse.BodyHandlers.ofString()))
                .toList();
        for (CompletableFuture<HttpResponse<String>> response : responses) {
            assertWorkResult(response.get(), 5);
        }
    }

    private HttpRequest request(String query) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/work" + query))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    private void assertWorkResult(HttpResponse<String> response, int iterations) throws Exception {
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(iterations, body.get("iterations").asInt());
        long bytes = body.get("payloadBytes").asLong();
        assertTrue(bytes >= iterations * 16L * 256);
        assertTrue(bytes <= iterations * 16L * 4096);
        assertTrue(body.get("checksum").isIntegralNumber());
        assertTrue(body.get("elapsedNanos").asLong() > 0);
    }
}
