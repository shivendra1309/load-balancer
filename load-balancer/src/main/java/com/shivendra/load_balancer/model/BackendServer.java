package com.shivendra.load_balancer.model;

import lombok.Getter;
import lombok.ToString;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Getter
@ToString
public class BackendServer {
    private final String id;
    private final String host;
    private final int port;
    private final int weight;

    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalRequestsServed = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);

    public BackendServer(String id, String host, int port, int weight) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.weight = weight;
    }

    public String getUrl() {
        return "http://" + host + ":" + port;
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    public void markHealthy() {
        healthy.set(true);
    }

    public void markUnhealthy() {
        healthy.set(false);
    }

    public void incrementConnections() {
        activeConnections.incrementAndGet();
        totalRequestsServed.incrementAndGet();
    }

    public void decrementConnections() {
        activeConnections.decrementAndGet();
    }

    public void incrementFailures() {
        totalFailures.incrementAndGet();
    }
}
