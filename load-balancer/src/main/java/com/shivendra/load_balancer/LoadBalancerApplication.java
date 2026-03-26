package com.shivendra.load_balancer;

import com.shivendra.load_balancer.config.LoadBalancerConfig;
import com.shivendra.load_balancer.proxy.ProxyHandler;
import com.shivendra.load_balancer.registry.BackendRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Slf4j
@SpringBootApplication
@EnableScheduling
@RequiredArgsConstructor
public class LoadBalancerApplication {

    private final BackendRegistry registry;
    private final LoadBalancerConfig config;
    private final ProxyHandler proxyHandler;

    public static void main(String[] args) {
        SpringApplication.run(LoadBalancerApplication.class, args);
    }

    /**
     * Fires after Spring context is fully ready — all beans initialized.
     * Safer than @PostConstruct which fires during bean construction,
     * before the full context is available.
     * Reads backends from application.yml and registers them into the registry.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Loading backends from config...");
        config.toBackendServers().forEach(registry::register);
        log.info("Load balancer ready — {} backends registered",
                registry.getAllBackends().size());
    }

    /**
     * Catch-all route — every incoming request regardless of method or path
     * goes to ProxyHandler. This is intentional — a load balancer is transparent,
     * it should never filter or block based on path. That's an API Gateway's job.
     */
    @Bean
    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route(
                request -> {
                    boolean isAdmin = request.path().startsWith("/admin");
                    log.info("Router predicate — path: {} isAdmin: {} willProxy: {}",
                            request.path(), isAdmin, !isAdmin);
                    return !isAdmin;
                },
                proxyHandler::handle
        );
    }

}
