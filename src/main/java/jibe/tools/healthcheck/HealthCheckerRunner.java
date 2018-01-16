package jibe.tools.healthcheck;

import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.immutables.value.Value.Style.ImplementationVisibility.PRIVATE;

public class HealthCheckerRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthCheckerRunner.class);
    private static final String HEALTHCHECK_ARG = "--healthcheck=";

    public HealthCheckResults run(List<String> args) {
        LOGGER.debug("run: {}", args);
        List<String> healthCheckArgs = extractHealthCheckArgs(args);
        if (healthCheckArgs.isEmpty()) {
            LOGGER.debug("no healthcheck endpoints given");
            return new HealthCheckResultsBuilder()
                .results(Collections.emptyList())
                .allOK(true)
                .build();
        }

        List<HealthCheckEndpoint> healthCheckEndpoints = healthCheckArgs.stream()
            .map(this::toHealthCheckEndpoint).collect(Collectors.toList());

        return healthcheck(healthCheckEndpoints);
    }

    private List<String> extractHealthCheckArgs(List<String> args) {
        return args.stream()
            .filter(a -> a.startsWith(HEALTHCHECK_ARG))
            .map(a -> a.substring(HEALTHCHECK_ARG.length()))
            .collect(Collectors.toList());
    }

    private HealthCheckResults healthcheck(List<HealthCheckEndpoint> healthCheckEndpoints) {
        List<HealthCheckThreadRunner> threadRunners = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (HealthCheckEndpoint endpoint : healthCheckEndpoints) {
            HealthCheckThreadRunner threadRunner = new HealthCheckThreadRunner(endpoint);
            threadRunners.add(threadRunner);
            threads.add(new Thread(threadRunner));
        }

        threads.forEach(Thread::start);
        threads.forEach(this::join);

        List<HealthCheckResult> results = threadRunners.stream().map(r -> r.result).collect(Collectors.toList());
        return new HealthCheckResultsBuilder()
            .results(results)
            .allOK(results.stream().allMatch(HealthCheckResult::getResult))
            .build();
    }

    private void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private HealthCheckEndpoint toHealthCheckEndpoint(String arg) {
        String[] split = arg.split(",");
        if ((split.length < 2) || (split.length > 3)) {
            throw new IllegalArgumentException("usage: endpoint,max-wait-sec[,name]");
        }

        URI endpoint = uri(split[0]);
        Duration maxWait = Duration.ofSeconds(Integer.parseInt(split[1]));

        Optional<String> name = Optional.empty();
        if (split.length == 3) {
            name = Optional.of(split[2]);
        }

        return new HealthCheckEndpointBuilder()
            .endpoint(endpoint)
            .maxWait(maxWait)
            .name(name)
            .build();
    }

    private URI uri(String uri) {
        try {
            return new URI(uri);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Value.Immutable
    @Value.Style(visibility = PRIVATE)
    interface HealthCheckResult {
        HealthCheckEndpoint getHealthCheckEndpoint();

        boolean getResult();
    }

    @Value.Immutable
    @Value.Style(visibility = PRIVATE)
    interface HealthCheckResults {
        List<HealthCheckResult> getResults();

        boolean allOK();
    }

    @Value.Immutable
    @Value.Style(visibility = PRIVATE)
    interface HealthCheckEndpoint {
        URI getEndpoint();

        Optional<String> getName();

        Duration getMaxWait();
    }

    class HealthCheckThreadRunner implements Runnable {

        private HealthCheckEndpoint endpoint;
        private HealthCheckResult result;

        HealthCheckThreadRunner(HealthCheckEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void run() {
            result = healthCheckResult();
        }

        private HealthCheckResult healthCheckResult() {
            long timeoutMillis = endpoint.getMaxWait().toMillis();
            long remainingWait = timeoutMillis;
            long waitStart = System.currentTimeMillis();

            Instant lastLog = null;
            while ((result == null) || (remainingWait > 0)) {
                try {
                    result = healthCheck(endpoint);

                    if ((lastLog == null) || lastLog.plusSeconds(5).isBefore(Instant.now()) || result.getResult()) {
                        LOGGER.info(getEndpointName() + (result.getResult() ? " is ready" : " is not healthy yet..."));
                        lastLog = Instant.now();
                    }

                    if (result.getResult()) {
                        return result;
                    }

                    Thread.sleep(1000);
                    remainingWait = timeoutMillis - (System.currentTimeMillis() - waitStart);
                } catch (InterruptedException e) {
                    remainingWait = 0;
                }
            }
            return result;
        }

        private HealthCheckResult healthCheck(HealthCheckEndpoint endpoint) {
            boolean result = getHealthCheckResponse(endpoint);
            return new HealthCheckResultBuilder()
                .healthCheckEndpoint(endpoint)
                .result(result)
                .build();
        }

        private String getEndpointName() {
            return endpoint.getName().orElse(endpoint.getEndpoint().getHost());
        }

        private boolean getHealthCheckResponse(HealthCheckEndpoint endpoint) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) endpoint.getEndpoint().toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1024);
                conn.setReadTimeout(1024);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    LOGGER.debug("responseCode: {}", responseCode);
                }

                try (BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())))) {
                    return Optional.ofNullable(br.readLine()).map(s -> s.contains("OK")).orElse(false);
                }
            } catch (IOException e) {
                LOGGER.debug("io-exception: {}", e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            return false;
        }
    }
}
