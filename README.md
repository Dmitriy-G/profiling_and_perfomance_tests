# profiling_and_perfomance_tests

A Spring Boot 4.1.2 HTTP application for comparing garbage collectors under load from Gatling. The root POM aggregates two Maven subprojects:

```text
pom.xml                     # Aggregator (packaging: pom)
testable_application/
  pom.xml                   # Spring Boot application, Java 26
  src/main/
  src/test/
load-tests/
  pom.xml                   # Gatling runner, Java 17
  src/test/java/            # Java simulation scripts
  src/test/resources/
```

The application requires a full JDK 26 and Maven. Each module owns its dependencies and Java target; Spring Boot dependency management applies only to `testable_application`.

### IntelliJ IDEA

Import the repository's root `pom.xml` as a Maven project, or use **Reload All Maven Projects** if it is already open. IntelliJ discovers both `testable_application` and `load-tests` from the root `<modules>` list and marks their `src/test/java` directories as test sources. This resolves the "Java file is located outside of the module source root" warning for Gatling simulations.

Build both modules with JDK 26 before starting the application. This runs the application's tests and compiles the Gatling simulation; load traffic runs only with the explicit `gatling:test` goal:

```powershell
mvn clean verify
New-Item -ItemType Directory -Force recordings | Out-Null
```

## 1. Start with the selected GC and JFR enabled

Run one of these commands from the project root. Each starts the same application with a fixed 512 MiB heap and writes a separate JFR file:

```powershell
# G1
java -Xms512m -Xmx512m -XX:+UseG1GC '-XX:StartFlightRecording=filename=recordings/g1.jfr,settings=profile,dumponexit=true,maxsize=256m' -jar testable_application/target/testable_application.jar

# Parallel
java -Xms512m -Xmx512m -XX:+UseParallelGC '-XX:StartFlightRecording=filename=recordings/parallel.jfr,settings=profile,dumponexit=true,maxsize=256m' -jar testable_application/target/testable_application.jar

# Serial
java -Xms512m -Xmx512m -XX:+UseSerialGC '-XX:StartFlightRecording=filename=recordings/serial.jfr,settings=profile,dumponexit=true,maxsize=256m' -jar testable_application/target/testable_application.jar

# ZGC
java -Xms512m -Xmx512m -XX:+UseZGC '-XX:StartFlightRecording=filename=recordings/z.jfr,settings=profile,dumponexit=true,maxsize=256m' -jar testable_application/target/testable_application.jar
```

The server listens on port `8080` until stopped with Ctrl+C. GC selection and JFR recording are JVM options before `-jar`. To change the server port, add `--server.port=8081` after the JAR filename. Collector availability depends on your JDK/platform.

## 2. Generate traffic with Gatling

The test is [WorkSimulation.java](load-tests/src/test/java/com/example/load/WorkSimulation.java). It sends empty-body `POST http://localhost:8080/work?iterations=100` requests at a constant arrival rate, checking HTTP `200` and 100 completed iterations in each JSON response. Each simulated user sends one request. Shared HTTP connections allow reuse across users. No GUI, account, or separately installed Gatling distribution is needed; Maven downloads the pinned dependencies on the first run.

Start the application with JDK 26 as described above. In a **separate terminal**, use an installed JDK 17, 21, or 25 for Gatling. Check Maven's selected runtime with `mvn -version`; select one of these JDKs in the load-test terminal if necessary.

Gatling's documented supported runtimes are LTS JDKs; the load-test module compiles to Java 17 and does not inherit the application's Spring Boot dependencies or Java 26 target. See the [Gatling installation guide](https://docs.gatling.io/tutorials/test-as-code/java-jvm/installation-guide/).

Run all commands below from the repository root. First check connectivity with a small smoke run:

```powershell
mvn -B -pl load-tests gatling:test '-Drps=1' '-Dduration=5'
```

Warm up the running application, then measure it with a separate run so warmup traffic is excluded from the measured Gatling results:

```powershell
# Warmup: use the same rate and workload size as the measurement
mvn -B -pl load-tests gatling:test '-Dduration=30'

# Measurement: 10 requests/second for 180 seconds, 100 iterations/request
mvn -B -pl load-tests gatling:test
```

The simulation accepts only two configuration properties. Duration is measured in seconds:

| Property | Default | Meaning |
| --- | --- | --- |
| `rps` | `10` | Requests started per second; positive integer |
| `duration` | `180` | Injection duration in seconds; positive integer |

For example:

```powershell
mvn -B -pl load-tests gatling:test '-Drps=20' '-Dduration=120'
```

The target URL, workload of 100 iterations per request, and p95 latency limit of 500 ms are fixed in the script. Every run, including warmup, fails if any request or response check fails, no requests succeed, or p95 latency reaches 500 ms. Assertions produce a nonzero Maven exit code for CI. Requests have a 10-second timeout, and the run waits for in-flight requests after injection ends.

The console shows throughput, response-time percentiles, and failures. Each run also writes an HTML report and raw simulation log under `load-tests/target/gatling/<run>/`; open `index.html` if desired. Generated results are already ignored by Git. Run `mvn -pl load-tests clean` to remove only load-test output; a root `mvn clean` cleans both modules. The load test runs only when explicitly invoked and expects an already running application. You can also run it directly with `mvn -B -f load-tests/pom.xml gatling:test`.

Use the same rate, duration, workload size, warmup, JDK, and heap for each GC comparison. Arrival rate remains fixed as responses slow down, so concurrency can grow under overload. For more reliable measurements, run Gatling on another machine to avoid competing with the application for CPU. Gatling controls traffic; the application only does simulated work when an HTTP request arrives.

`POST /work` accepts an optional integer query parameter `iterations` (default `100`, range `1..1000`). No request body is required. Invalid values return HTTP `400`.

Each iteration allocates a graph of 16 byte arrays, each randomly sized from 256 to 4096 bytes, and fills them with random data to simulate CPU work. A shared, thread-safe ring retains the latest 128 graphs across requests, roughly 4.3 MiB of payload on average. Older graphs become garbage as requests replace them. Retention is bounded independently of request count and server thread count; in-flight requests also consume memory. There are no background load generators, sleeps, database calls, or explicit `System.gc()` calls.

The JSON response contains:

| Field | Meaning |
| --- | --- |
| `iterations` | Completed iterations |
| `payloadBytes` | Byte-array payload allocated by this request; excludes object headers, reference arrays, and HTTP overhead |
| `checksum` | Computed from the generated data |
| `elapsedNanos` | Time inside the workload service, including scheduling/GC delays; excludes HTTP handling and serialization |

At the default size, each request allocates about 3.3 MiB of payload. The Gatling script uses this fixed workload of 100 iterations per request. Measure throughput and end-to-end latency in Gatling.

## 3. Stop and inspect the JFR

After Gatling finishes, press Ctrl+C in the application terminal. `dumponexit=true` saves the recording on JVM shutdown. Open the selected file in JDK Mission Control, for example `recordings/g1.jfr`, and inspect GC pauses, heap occupancy, and allocation samples from `WorkService.simulate`.

You can also check the recording from the command line:

```powershell
jfr summary recordings/g1.jfr
```

Repeat with another GC and the same JDK, heap, iterations, and Gatling settings. The recording includes application startup and warmup; select the measurement interval in JMC. Rename the output file for repeated runs you want to keep. JFR is capped at 256 MiB, so long runs may discard older events.

`mvn clean` removes build output from both modules' `target/` directories. Existing recordings are preserved and ignored by Git.

JVM recording options: [Java launcher reference](https://docs.oracle.com/en/java/javase/26/docs/specs/man/java.html). Recording inspection: [JFR command reference](https://docs.oracle.com/en/java/javase/26/docs/specs/man/jfr.html).

## 4. Monitor with Prometheus and Grafana

The application exposes health at `http://localhost:8080/actuator/health` and metrics at `http://localhost:8080/actuator/prometheus`. Actuator and the Micrometer Prometheus registry are included in `testable_application/pom.xml`; configuration is in `testable_application/src/main/resources/application.properties`.

These instructions run the Java application on Windows and the monitoring services in Docker Desktop using Linux containers. Start the application with one of the GC commands above, then start Docker Desktop and run this command in a separate terminal from the project root:

```powershell
docker compose -f compose.monitoring.yml up -d
```

On the first run, Maven may download the added dependencies and Docker may download the monitoring images. The Compose file uses the `latest` image tags; existing local images are reused unless you explicitly pull newer ones.

Open `http://localhost:9090/targets` and check that the `spring-boot` target is **UP**. Prometheus scrapes the application every five seconds through `host.docker.internal:8080`. If you change the application port, update `monitoring/prometheus.yml` to match and restart Prometheus:

```powershell
docker compose -f compose.monitoring.yml restart prometheus
```

Open `http://localhost:3000` and sign in with username `admin` and password `local-monitoring`. You can set `GRAFANA_ADMIN_PASSWORD` in your terminal before the first startup to choose another password. This setting initializes new Grafana storage; it does not reset an existing account's password.

The Prometheus data source is configured automatically at `http://prometheus:9090`. Open **Dashboards → Monitoring** to find these dashboards:

| Dashboard | Panels | JSON file in `monitoring/grafana/dashboards/` |
| --- | --- | --- |
| JVM | Heap memory by pool; GC pause time per second; GC pauses per second | `spring-boot-jvm.json` |
| HTTP Requests | `/work` requests per second; `/work` average response time | `http-requests.json` |
| Hardware | Process CPU usage | `hardware.json` |

Generate traffic with Gatling and allow about a minute for the rate graphs to fill. HTTP panels need requests to `/work`, and GC panels need GC events.

Edit the corresponding JSON file to persist dashboard changes; Grafana reloads it automatically. The JVM dashboard retains its original UID (`spring-boot-jvm`), so existing links continue to work.

### Store dashboards in Git

Keep one dashboard JSON file per dashboard under `monitoring/grafana/dashboards/`. This directory and the provisioning configuration are tracked by Git. Grafana reads these files through the Compose bind mount and polls for changes every 30 seconds; importing through the UI is not required. If Grafana is already running with an older provisioning configuration, restart Grafana once to apply the polling interval.

For a new dashboard:

1. Create the dashboard in Grafana, select the provisioned **Prometheus** data source, add your panels, and save it.
2. Export the dashboard as JSON from Grafana. Choose the **Classic** model when offered, and keep **Export for sharing externally** off so the provisioned Prometheus data source UID is preserved.
3. Save the JSON under a new filename, for example `monitoring/grafana/dashboards/thread-usage.json`.
4. Keep a unique `uid` and title for each dashboard. Remove the instance-specific numeric `id`, or set it to `null`. Keep the UID stable when updating the same dashboard.
5. Review and commit the JSON file with Git. Grafana loads it in the **Monitoring** folder on the next poll.

For an existing dashboard, update its corresponding JSON file and commit the change. The Git-managed provisioned dashboards cannot be saved through the UI (`allowUiUpdates: false`); UI-created dashboards must be exported into this directory to become part of the repository. Grafana does not write UI changes back to Git or to the mounted files.

Dashboard JSON contains layout and queries. Historical metrics remain in the Prometheus Docker volume; they are not stored in Git.

Prometheus and Grafana listen on localhost ports `9090` and `3000`. Named Docker volumes preserve metrics, Grafana accounts, and saved dashboards between container recreations. To stop monitoring while preserving the data:

```powershell
docker compose -f compose.monitoring.yml down
```

Stop the Java application separately with Ctrl+C. Restart it with another GC to compare collectors using the same Gatling workload.
