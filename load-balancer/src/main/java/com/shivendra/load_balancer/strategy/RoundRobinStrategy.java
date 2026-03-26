package com.shivendra.load_balancer.strategy;

import com.shivendra.load_balancer.model.BackendServer;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Primary
@Component
public class RoundRobinStrategy implements LoadBalancingStrategy{

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public BackendServer select(String clientIp, List<BackendServer> backends) {
        if(backends.isEmpty()){
            throw new IllegalStateException("No healthy backend servers available");
        }
        int index = Math.floorMod(counter.getAndIncrement(), backends.size());
        return backends.get(index);
    }

    @Override
    public String name() {
        return "ROUND_ROBIN";
    }
}
