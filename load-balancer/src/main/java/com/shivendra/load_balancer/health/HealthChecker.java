package com.shivendra.load_balancer.health;

import com.shivendra.load_balancer.model.BackendServer;
import com.shivendra.load_balancer.registry.BackendRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthChecker {

    private final BackendRegistry registry;

    /**
     * Tracks consecutive failures per backend id.
     * ConcurrentHashMap — health checker thread writes, no other thread reads this.
     * But defensive to use concurrent variant anyway.
     */
    private final Map<String, Integer> failureCounts = new ConcurrentHashMap<>();

    private static final int FAILURE_THRESHOLD = 3;
    private static final int TIMEOUT_MS = 2000;

    /**
     * Dedicated WebClient for health checks only.
     * Separate from the proxy WebClient — different timeout, different purpose.
     */
    private final WebClient webClient = WebClient.builder().build();

    /**
     * Runs every 5 seconds after the previous execution completes.
     * fixedDelay not fixedRate — if a check takes 6s, next one starts after,
     * not during. Prevents pile-up under slow backends.
     */
    @Scheduled(fixedDelayString = "${healthcheck.interval-ms:5000}")
    public void checkAll() {
        log.debug("Running health checks on {} backends", registry.getAllBackends().size());

        Flux.fromIterable(registry.getAllBackends())
                .flatMap(this::check)  // all backends checked in parallel
                .subscribe();
    }

    private Mono<Void> check(BackendServer backend) {
        return webClient.get()
                .uri(backend.getUrl() + "/health")
                .exchangeToMono(response -> {
                    // Any response means TCP connection succeeded and backend is alive
                    // Only 5xx means the backend itself is broken
                    if (response.statusCode().is5xxServerError()) {
                        return Mono.<Void>error(new RuntimeException(
                                "Backend returned " + response.statusCode()));
                    }
                    return response.releaseBody(); // discard body, we don't need it
                })
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .doOnSuccess(v -> onSuccess(backend))
                .doOnError(err -> onFailure(backend, err))
                .onErrorComplete()
                .then();
    }

    private void onSuccess(BackendServer backend) {
        failureCounts.put(backend.getId(), 0);
        if (!backend.isHealthy()) {
            backend.markHealthy();
            log.info("Backend {} is back UP", backend.getId());
        } else {
            log.debug("Backend {} is healthy", backend.getId());
        }
    }

    /**
     * merge() increments existing count or inserts 1 if absent.
     * Atomic operation on ConcurrentHashMap — no race condition.
     */
    private void onFailure(BackendServer backend, Throwable err) {

        int failures = failureCounts.merge(backend.getId(), 1, Integer::sum);

        log.warn("Backend {} health check failed ({}/{}): {}",
                backend.getId(), failures, FAILURE_THRESHOLD, err.getMessage());

        if (failures >= FAILURE_THRESHOLD && backend.isHealthy()) {
            backend.markUnhealthy();
            log.error("Backend {} marked DOWN after {} consecutive failures",
                    backend.getId(), failures);
        }
    }


}
