package com.shivendra.load_balancer.proxy;

import com.shivendra.load_balancer.health.HealthChecker;
import com.shivendra.load_balancer.model.BackendServer;
import com.shivendra.load_balancer.registry.BackendRegistry;
import com.shivendra.load_balancer.strategy.LoadBalancingStrategy;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Slf4j
@Component
public class ProxyHandler {

    private final BackendRegistry registry;

    /**
     * volatile — guarantees visibility across all event loop threads.
     * When admin API swaps strategy, every thread sees the new value immediately.
     * No lock needed — reference assignment is atomic on JVM.
     */
    private volatile LoadBalancingStrategy strategy;

    private final HealthChecker healthChecker;

    /**
     * Single shared WebClient for all proxy requests.
     * WebClient is thread-safe and designed to be shared — do not create per request.
     * Configured with connection timeout and response timeout separately:
     * - connectTimeoutMillis: how long to wait to establish TCP connection
     * - responseTimeout: how long to wait for backend to respond after connection
     */
    private final WebClient webClient;

    public ProxyHandler(BackendRegistry registry, LoadBalancingStrategy strategy, HealthChecker healthChecker) {
        this.registry = registry;
        this.strategy = strategy;
        this.healthChecker = healthChecker;

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(30))
                .option(ChannelOption.SO_KEEPALIVE, true);

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public Mono<ServerResponse> handle(ServerRequest request) {
        List<BackendServer> healthy = registry.getHealthyBackends();

        if (healthy.isEmpty()) {
            log.error("No healthy backends available — returning 503");
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .bodyValue("No healthy backends available");
        }

        String clientIp = request.remoteAddress()
                .map(addr -> addr.getAddress().getHostAddress())
                .orElse("unknown");

        BackendServer backend = strategy.select(clientIp, healthy);
        log.debug("Routing request {} {} to backend {}",
                request.method(), request.path(), backend.getId());

        return forwardRequest(request, backend);
    }

    private Mono<ServerResponse> forwardRequest(ServerRequest request, BackendServer backend) {
        String targetUrl = backend.getUrl()
                + request.path()
                + (request.uri().getRawQuery() != null
                ? "?" + request.uri().getRawQuery()
                : "");

        backend.incrementConnections();

        return webClient.method(request.method())
                .uri(URI.create(targetUrl))
                .headers(h -> h.addAll(request.headers().asHttpHeaders()))
                .body(request.bodyToMono(byte[].class), byte[].class)
                .exchangeToMono(response -> {
                    // Track 5xx responses toward health failure threshold
                    if (response.statusCode().is5xxServerError()) {
                        healthChecker.recordFailure(backend);
                        log.warn("Backend {} returned 5xx: {}",
                                backend.getId(), response.statusCode());
                    } else {
                        healthChecker.recordSuccess(backend);
                    }
                    return ServerResponse.status(response.statusCode())
                            .headers(h -> h.addAll(response.headers().asHttpHeaders()))
                            .body(response.bodyToMono(byte[].class), byte[].class);
                })
                /**
                 * doFinally fires on THREE signals: onComplete, onError, onCancel.
                 * This is the guarantee that activeConnections never drifts.
                 * If client disconnects mid-request (cancel), this still fires.
                 * Never use doOnSuccess/doOnError for cleanup — they miss cancel.
                 */
                .doFinally(signal -> {
                    backend.decrementConnections();
                    log.debug("Request to backend {} completed — signal: {}, active: {}",
                            backend.getId(), signal, backend.getActiveConnections());
                })
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(TimeoutException.class, e -> {
                    log.error("Timeout waiting for backend {}", backend.getId());
                    backend.incrementFailures();
                    healthChecker.recordFailure(backend);
                    return ServerResponse.status(HttpStatus.GATEWAY_TIMEOUT)
                            .bodyValue("Backend timeout");
                })
                .onErrorResume(this::isConnectionReset, e -> {
                    log.error("Connection reset from backend {}: {}",
                            backend.getId(), e.getMessage());
                    backend.incrementFailures();
                    healthChecker.recordFailure(backend);
                    return ServerResponse.status(HttpStatus.BAD_GATEWAY)
                            .bodyValue("Backend connection reset");
                })
                .onErrorResume(e -> {
                    log.error("Unexpected error forwarding to backend {}: {}",
                            backend.getId(), e.getMessage());
                    backend.incrementFailures();
                    healthChecker.recordFailure(backend);
                    return ServerResponse.status(HttpStatus.BAD_GATEWAY)
                            .bodyValue("Backend error");
                });
    }

    private boolean isConnectionReset(Throwable e) {
        return e.getMessage() != null &&
                (e.getMessage().contains("Connection reset") ||
                        e.getMessage().contains("Broken pipe") ||
                        e instanceof java.io.IOException);
    }
    /**
     * Called by AdminController to hot-swap strategy at runtime.
     * volatile write — JVM guarantees this is visible to all threads immediately.
     * Zero downtime, zero locks, zero restarts.
     */
    public void setStrategy(LoadBalancingStrategy newStrategy) {
        this.strategy = newStrategy;
        log.info("Strategy switched to: {}", newStrategy.name());
    }
}
