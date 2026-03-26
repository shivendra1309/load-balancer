package com.shivendra.load_balancer.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds all available strategies, keyed by name.
 * Spring injects ALL LoadBalancingStrategy implementations automatically
 * via the List<LoadBalancingStrategy> constructor param.
 * Adding a new strategy = create a new @Component. Zero changes here.
 */
@Component
public class StrategyRegistry {

    private final Map<String, LoadBalancingStrategy> strategies;

    public StrategyRegistry(List<LoadBalancingStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(
                        LoadBalancingStrategy::name,
                        Function.identity()
                ));
    }

    public LoadBalancingStrategy get(String name) {
        LoadBalancingStrategy strategy = strategies.get(name.toUpperCase());
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "Unknown strategy: " + name +
                            ". Available: " + strategies.keySet());
        }
        return strategy;
    }

    public List<String> availableStrategies() {
        return List.copyOf(strategies.keySet());
    }
}
