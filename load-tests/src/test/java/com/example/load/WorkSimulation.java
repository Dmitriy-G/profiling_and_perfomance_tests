package com.example.load;

import java.time.Duration;

import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class WorkSimulation extends Simulation {
    public WorkSimulation() {
        int rps = positiveInt("rps", 10);
        int durationSeconds = positiveInt("duration", 180);

        var protocol = http.baseUrl("http://localhost:8080")
                .acceptHeader("application/json")
                .shareConnections()
                .disableWarmUp();

        // One request per arriving user makes the user arrival rate the request rate.
        var work = scenario("Work")
                .exec(http("POST /work")
                        .post("/work")
                        .queryParam("iterations", 100)
                        .requestTimeout(Duration.ofSeconds(10))
                        .check(status().is(200),
                                jsonPath("$.iterations").ofInt().is(100)));

        setUp(work.injectOpen(
                constantUsersPerSec(rps).during(Duration.ofSeconds(durationSeconds))))
                .protocols(protocol)
                .assertions(
                        global().successfulRequests().count().gt(0L),
                        global().failedRequests().count().is(0L),
                        global().responseTime().percentile(95.0).lt(500));
    }

    private static int positiveInt(String name, int defaultValue) {
        String value = System.getProperty(name, Integer.toString(defaultValue));
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Use the same actionable error for malformed and out-of-range values.
        }
        throw new IllegalArgumentException(name + " must be a positive integer: " + value);
    }
}
