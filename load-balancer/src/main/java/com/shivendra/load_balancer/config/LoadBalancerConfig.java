package com.shivendra.load_balancer.config;

import com.shivendra.load_balancer.model.BackendServer;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "loadbalancer")
public class LoadBalancerConfig {
    private String strategy = "ROUND_ROBIN";
    private List<BackendProperties> backends;

    @Data
    public static class BackendProperties {
        private String id;
        private String host;
        private int port;
        private int weight = 1;
    }

    public List<BackendServer> toBackendServers() {
        return backends.stream()
                .map(b -> new BackendServer(
                        b.getId(),
                        b.getHost(),
                        b.getPort(),
                        b.getWeight()))
                .toList();
    }


}
