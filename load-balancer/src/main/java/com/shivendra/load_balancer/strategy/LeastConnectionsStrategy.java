package com.shivendra.load_balancer.strategy;

import com.shivendra.load_balancer.model.BackendServer;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Least Connections — always route to backend with fewest active connections.
 *
 * Why this matters over Round Robin:
 * RR assumes all requests take equal time. Reality: some requests take 1ms,
 * some take 500ms. RR can pile 100 slow requests on backend-1 while
 * backend-2 is idle. LC adapts to actual load in real time.
 *
 * Thread safety: activeConnections is AtomicInteger on BackendServer.
 * Reading it here is safe — worst case we pick a backend that gets
 * one extra connection in the gap between read and increment.
 * That's acceptable — we don't need a lock for approximate least connections.
 */
@Component
public class LeastConnectionsStrategy implements LoadBalancingStrategy {

    @Override
    public BackendServer select(String clientIp, List<BackendServer> backends) {
        if (backends.isEmpty()) {

            throw new IllegalStateException("No healthy backends available");
        }

        return backends.stream()
                .min(Comparator.comparingInt(
                        b -> b.getActiveConnections().get()))
                .orElseThrow(() ->
                        new IllegalStateException("No healthy backends available"));
    }

    @Override
    public String name() {
        return "LEAST_CONNECTIONS";
    }

}
