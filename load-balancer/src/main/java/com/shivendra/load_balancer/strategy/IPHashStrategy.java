package com.shivendra.load_balancer.strategy;

import com.shivendra.load_balancer.model.BackendServer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * IP Hash — same client IP always goes to same backend.
 *
 * Why this matters:
 * Stateful backends (session data, local cache) need the same client
 * to always hit the same server. RR/LC would scatter requests randomly.
 *
 * Limitation: if a backend goes DOWN, all clients hashed to it get
 * redistributed — session state is lost. Solution in production:
 * sticky sessions via Redis (shared session store) instead of IP hash.
 *
 * We use Math.floorMod not % to handle negative hashCodes safely.
 * String.hashCode() can return negative values on certain IPs.
 */
@Component
public class IPHashStrategy implements LoadBalancingStrategy {
    @Override
    public BackendServer select(String clientIp, List<BackendServer> backends) {
        if (backends.isEmpty()) {
            throw new IllegalStateException("No healthy backends available");
        }

        int index = Math.floorMod(clientIp.hashCode(), backends.size());
        return backends.get(index);
    }

    @Override
    public String name() {
        return "IP_HASH";
    }
}
