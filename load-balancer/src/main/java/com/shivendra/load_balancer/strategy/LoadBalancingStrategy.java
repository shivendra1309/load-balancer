package com.shivendra.load_balancer.strategy;

import com.shivendra.load_balancer.model.BackendServer;

import java.util.List;

public interface LoadBalancingStrategy {

    BackendServer select(String clientIp, List<BackendServer> backends);

    String name();
}
