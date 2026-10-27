# profiling_and_perfomance_tests

A Spring Boot 4.1.2 HTTP application for comparing garbage collectors under load from JMeter. Requires a full JDK 26 and Maven; the build targets Java 26.

## 1. Start with the selected GC and JFR enabled

Run one of these commands from the project root. Each starts the same application with a fixed 512 MiB heap and writes a separate JFR file:

```powershell
# G1
java -Xms512m -Xmx512m -XX:+UseG1GC '-XX:StartFlightRecording=filename=recordings/g1.jfr,settings=profile,dumponexit=true,maxsize=256m' -jar target/profiling_and_perfomance_tests.jar

# Parallel
java -Xms512m -Xmx512m -XX:+UseParallelGC '-XX:StartFlightRecording=filename=recordings/parallel.jfr,settings=profile,dumponexit=true,maxsize=256m' -jar target/profiling_and_perfomance_tests.jar

# Serial
java -Xms512m -Xmx512m -XX:+UseSerialGC '-XX:StartFlightRecording=filename=recordings/serial.jfr,settings=profile,dumponexit=true,maxsize=256m' -jar target/profiling_and_perfomance_tests.jar

# ZGC
java -Xms512m -Xmx512m -XX:+UseZGC '-XX:StartFlightRecording=filename=recordings/z.jfr,settings=profile,dumponexit=true,maxsize=256m' -jar target/profiling_and_perfomance_tests.jar
```

The server listens on port `8080` until stopped with Ctrl+C. GC selection and JFR recording are JVM options before `-jar`. To change the server port, add `--server.port=8081` after the JAR filename. Collector availability depends on your JDK/platform.

## 3. Generate traffic with JMeter

In a JMeter test plan, add a Thread Group and an HTTP Request sampler:

| Setting | Value |
| --- | --- |
| Protocol | `http` |
| Server Name or IP | `localhost` |
| Port | `8080` |
| Method | `POST` |
| Path | `/work?iterations=100` |
| Body | Empty |

Set users, ramp-up, and duration in JMeter. Add a Response Assertion for HTTP `200`. JMeter controls all traffic, concurrency, warmup, and measurement duration; the application only does simulated work when an HTTP request arrives.

`POST /work` accepts an optional integer query parameter `iterations` (default `100`, range `1..1000`). No request body is required. Invalid values return HTTP `400`.

Each iteration allocates a graph of 16 byte arrays, each randomly sized from 256 to 4096 bytes, and fills them with random data to simulate CPU work. A shared, thread-safe ring retains the latest 128 graphs across requests, roughly 4.3 MiB of payload on average. Older graphs become garbage as requests replace them. Retention is bounded independently of request count and server thread count; in-flight requests also consume memory. There are no background load generators, sleeps, database calls, or explicit `System.gc()` calls.

The JSON response contains:

| Field | Meaning |
| --- | --- |
| `iterations` | Completed iterations |
| `payloadBytes` | Byte-array payload allocated by this request; excludes object headers, reference arrays, and HTTP overhead |
| `checksum` | Computed from the generated data |
| `elapsedNanos` | Time inside the workload service, including scheduling/GC delays; excludes HTTP handling and serialization |

At the default size, each request allocates about 3.3 MiB of payload. Increase `iterations` to increase per-request work. Measure throughput and end-to-end latency in JMeter.

## 4. Stop and inspect the JFR

After JMeter finishes, press Ctrl+C in the application terminal. `dumponexit=true` saves the recording on JVM shutdown. Open the selected file in JDK Mission Control, for example `recordings/g1.jfr`, and inspect GC pauses, heap occupancy, and allocation samples from `WorkService.simulate`.

You can also check the recording from the command line:

```powershell
jfr summary recordings/g1.jfr
```

Repeat with another GC and the same JDK, heap, iterations, and JMeter test plan. The recording includes application startup and JMeter warmup; select the measurement interval in JMC. Rename the output file for repeated runs you want to keep. JFR is capped at 256 MiB, so long runs may discard older events.

`mvn clean` removes build output in `target/`. Existing recordings are preserved and ignored by Git.

JVM recording options: [Java launcher reference](https://docs.oracle.com/en/java/javase/26/docs/specs/man/java.html). Recording inspection: [JFR command reference](https://docs.oracle.com/en/java/javase/26/docs/specs/man/jfr.html).
