package com.shivendra.load_balancer.controller;

import com.shivendra.load_balancer.model.BackendServer;
import com.shivendra.load_balancer.proxy.ProxyHandler;
import com.shivendra.load_balancer.registry.BackendRegistry;
import com.shivendra.load_balancer.strategy.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final BackendRegistry registry;
    private final ProxyHandler proxyHandler;
    private final StrategyRegistry strategyRegistry;

    /**
     * List all backends with live stats.
     * Shows healthy/unhealthy status, active connections, total requests served.
     * Use this to observe load distribution across backends in real time.
     */
    @GetMapping("/backends")
    public ResponseEntity<List<Map<String, Object>>> getBackends() {
        List<Map<String, Object>> backends = registry.getAllBackends().stream()
                .map(b -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", b.getId());
                    map.put("host", b.getHost());
                    map.put("port", b.getPort());
                    map.put("weight", b.getWeight());
                    map.put("healthy", b.isHealthy());
                    map.put("activeConnections", b.getActiveConnections().get());
                    map.put("totalRequestsServed", b.getTotalRequestsServed().get());
                    map.put("totalFailures", b.getTotalFailures().get());
                    return map;
                })
                .toList();
        return ResponseEntity.ok(backends);
    }

    /**
     * Register a new backend at runtime — no restart needed.
     * YAML is the baseline; this is the runtime override.
     */
    @PostMapping("/backends")
    public ResponseEntity<Map<String, String>> registerBackend(
            @RequestBody Map<String, String> body) {
        try {
            BackendServer server = new BackendServer(
                    body.get("id"),
                    body.get("host"),
                    Integer.parseInt(body.get("port")),
                    Integer.parseInt(body.getOrDefault("weight", "1"))
            );
            registry.register(server);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message", "Backend registered: " + server.getId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }


    /**
     * Deregister a backend at runtime.
     * In-flight requests complete normally — we only stop NEW requests
     * from going to this backend.
     */
    @DeleteMapping("/backends/{id}")
    public ResponseEntity<Map<String, String>> deregisterBackend(@PathVariable String id) {
        registry.deregister(id);
        return ResponseEntity.ok(Map.of("message", "Backend deregistered: " + id));
    }

    /**
     * Hot-swap the load balancing algorithm at runtime.
     * Zero downtime — next request uses new strategy.
     * Previous in-flight requests complete with old strategy.
     */
    @PutMapping("/strategy")
    public ResponseEntity<Map<String, String>> switchStrategy(
            @RequestBody Map<String, String> body) {
        try {
            String strategyName = body.get("strategy");
            log.info("Switching strategy to: {}", strategyName);
            if (strategyName == null || strategyName.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "strategy field is required"));
            }
            proxyHandler.setStrategy(strategyRegistry.get(strategyName));
            return ResponseEntity.ok(
                    Map.of("message", "Strategy switched to: " + strategyName));
        } catch (IllegalArgumentException e) {
            log.error("Strategy switch failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Available strategies — so callers know valid values for /strategy.
     */
    @GetMapping("/strategies")
    public ResponseEntity<Map<String, Object>> getStrategies() {
        return ResponseEntity.ok(Map.of(
                "available", strategyRegistry.availableStrategies()
        ));
    }


}
