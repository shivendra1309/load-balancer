package com.shivendra.load_balancer.strategy;

import com.shivendra.load_balancer.model.BackendServer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Nginx's Smooth Weighted Round Robin algorithm.
 * Distributes requests proportionally to weights WITHOUT clustering.
 *
 * Example — backends with weights [3, 2, 1]:
 * Naive WRR:    A A A B B C A A A B B C  ← clustered, bad for latency
 * Smooth WRR:   A B A C A B A B A C A B  ← evenly spread, good
 *
 * Algorithm per request:
 * 1. Each backend's currentWeight += its weight
 * 2. Pick backend with highest currentWeight
 * 3. Winner's currentWeight -= total weight of all backends
 */
@Component
public class WeightedRoundRobinStrategy implements LoadBalancingStrategy {

    /**
     * Per-backend current weight tracked as array parallel to backends list.
     * Rebuilt whenever backend list size changes.
     * volatile array reference — safe to swap atomically.
     */
    private volatile int[] currentWeights = new int[0];

    @Override
    public BackendServer select(String clientIp, List<BackendServer> backends) {
        if (backends.isEmpty()) {
            throw new IllegalStateException("No healthy backends available");
        }

        // Rebuild weights array if backend count changed
        // (backend added/removed at runtime)
        synchronized (this) {
            if (currentWeights.length != backends.size()) {
                currentWeights = new int[backends.size()];
            }

            int totalWeight = backends.stream()
                    .mapToInt(BackendServer::getWeight)
                    .sum();

            // Step 1: increment each backend's current weight by its configured weight
            for (int i = 0; i < backends.size(); i++) {
                currentWeights[i] += backends.get(i).getWeight();
            }

            // Step 2: pick the backend with highest current weight
            int selectedIndex = 0;
            for (int i = 1; i < backends.size(); i++) {
                if (currentWeights[i] > currentWeights[selectedIndex]) {
                    selectedIndex = i;  // selected index 0, 1, 0
                }
            }

            // Step 3: reduce winner's weight by total
            currentWeights[selectedIndex] -= totalWeight;

            return backends.get(selectedIndex);
        }
    }

    @Override
    public String name() {
        return "WEIGHTED_ROUND_ROBIN";
    }
}
