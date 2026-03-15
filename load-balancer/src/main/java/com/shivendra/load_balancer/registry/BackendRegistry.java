package com.shivendra.load_balancer.registry;

import com.shivendra.load_balancer.model.BackendServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class BackendRegistry {

    /**
     * Thread-safe backend storage.
     * CopyOnWriteArrayList chosen because reads (every request) vastly outnumber writes
     * (health checks every 5s, admin registration almost never).
     * Reads are completely lock-free. Writes copy the entire array — acceptable cost for rare writes.
     */
    private final CopyOnWriteArrayList<BackendServer> backends = new CopyOnWriteArrayList<>();

    public void register(BackendServer server) {
        backends.add(server);
        log.info("Registered backend: {}", server.getId());
    }

    public void deregister(String id) {
        backends.removeIf(b -> b.getId().equals(id));
        log.info("Deregistered backend: {}", id);
    }

    public List<BackendServer> getHealthyBackends() {
        return backends.stream()
                .filter(BackendServer::isHealthy)
                .toList();
    }

    /**
     * Returns an unmodifiable snapshot of all backends at this moment in time.
     * Defensive copy — callers cannot mutate internal registry state.
     * Use this for admin/observability. Use getHealthyBackends() for routing.
     */
    public List<BackendServer> getAllBackends() {
        return List.copyOf(backends);
    }

    public Optional<BackendServer> findById(String id) {
        return backends.stream()
                .filter(b -> b.getId().equals(id))
                .findFirst();
    }
}
