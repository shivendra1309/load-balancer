# Load Balancer

A production-grade HTTP load balancer built from scratch in Java Spring Boot WebFlux. Supports multiple load balancing algorithms, real-time health checking, runtime configuration via Admin API, and handles distributed system failure scenarios gracefully.

---

## Architecture

```
                        ┌─────────────────────────────────────┐
                        │           Load Balancer              │
  Clients ────────────▶ │         Spring WebFlux :8080         │
                        │                                      │
                        │  ┌─────────────┐  ┌──────────────┐  │
                        │  │ ProxyHandler│  │HealthChecker │  │
                        │  └──────┬──────┘  └──────┬───────┘  │
                        │         │                │           │
                        │  ┌──────▼────────────────▼───────┐  │
                        │  │        BackendRegistry         │  │
                        │  │    CopyOnWriteArrayList        │  │
                        │  └──────────────┬────────────────┘  │
                        │                 │                    │
                        │  ┌──────────────▼────────────────┐  │
                        │  │     Algorithm Strategy Layer   │  │
                        │  │  RR | WRR | LC | IP Hash       │  │
                        │  └───────────────────────────────┘  │
                        │                                      │
                        │  ┌──────────────────────────────┐   │
                        │  │   Admin API (functional)      │   │
                        │  │   /admin/** routes            │   │
                        │  └──────────────────────────────┘   │
                        └──────────┬───────────────────────────┘
                                   │
                          ┌────────┼────────┐
                          ▼        ▼        ▼
                       Backend  Backend  Backend
                       :9001    :9002    :9003
```

### Why WebFlux?

A load balancer spends 99% of its time waiting for backend responses — pure I/O, zero CPU work. Spring MVC's thread-per-request model would require one thread per concurrent request (~1MB stack each). At 10k concurrent requests that's 10GB RAM just for waiting threads.

WebFlux uses Netty's event loop — a small fixed thread pool (~16 threads) handles thousands of concurrent requests via non-blocking I/O. While one request waits for a backend, that thread serves other requests. Identity lives in the reactive pipeline (closure on the heap), not in the thread.

---

## Tech Stack

| Component | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.3 |
| HTTP Engine | Spring WebFlux + Reactor Netty |
| Build | Maven |

---

## Load Balancing Algorithms

### Round Robin
Distributes requests evenly across all healthy backends in rotation. Uses `AtomicInteger` with `Math.floorMod` — safe against integer overflow, no locks needed.

```
Request 1 → Backend A
Request 2 → Backend B
Request 3 → Backend C
Request 4 → Backend A
```

### Weighted Round Robin
Nginx's smooth weighted round robin algorithm. Distributes requests proportionally to configured weights without clustering.

```
Weights: A=3, B=2, C=1
Naive:  A A A B B C   ← clustered, bad for latency
Smooth: A B A C A B   ← evenly spread, good
```

### Least Connections
Always routes to the backend with the fewest active connections. Adapts to real load — if backend A is handling slow requests, backend B gets more traffic automatically.

```
Backend A: 5 active connections
Backend B: 1 active connection  ← selected
Backend C: 3 active connections
```

### IP Hash
Same client IP always routes to the same backend. Useful for stateful backends where session affinity matters.

```
Client 192.168.1.5 → always Backend B
Client 192.168.1.6 → always Backend C
```

---

## Project Structure

```
src/main/java/com/shivendra/load_balancer/
├── model/
│   └── BackendServer.java          # Thread-safe server state (Atomic*)
├── registry/
│   └── BackendRegistry.java        # CopyOnWriteArrayList, defensive copies
├── strategy/
│   ├── LoadBalancingStrategy.java  # Interface
│   ├── RoundRobinStrategy.java
│   ├── WeightedRoundRobinStrategy.java
│   ├── LeastConnectionsStrategy.java
│   ├── IPHashStrategy.java
│   └── StrategyRegistry.java       # All strategies, keyed by name
├── health/
│   └── HealthChecker.java          # Scheduled, parallel, failure threshold
├── proxy/
│   └── ProxyHandler.java           # Reactive pipeline, doFinally, error handling
├── handler/
│   └── AdminHandler.java           # WebFlux functional admin handlers
└── config/
    ├── LoadBalancerConfig.java     # YAML binding
    └── WebConfig.java              # Codec + ObjectMapper config
```

---

## Thread Safety Design

| Field | Type | Why |
|---|---|---|
| RR counter | `AtomicInteger` | CAS increment — no lock needed |
| activeConnections | `AtomicInteger` | Inc before forward, dec in `doFinally` |
| healthy flag | `AtomicBoolean` | Health checker writes, router reads |
| Backend list | `CopyOnWriteArrayList` | Lock-free reads (dominant), copy-on-write |
| Active strategy | `volatile` reference | Swap atomically at runtime |

---

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+

### Run the Load Balancer

```bash
./mvnw spring-boot:run
```

Default port: `8080`

### Run Dummy Backends (for testing)

Start 3 instances of the dummy backend on different ports:

```bash
# Terminal 1
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=9001"

# Terminal 2
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=9002"

# Terminal 3
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=9003"
```

### Configuration (`application.yml`)

```yaml
server:
  port: 8080

loadbalancer:
  strategy: ROUND_ROBIN
  backends:
    - id: backend-1
      host: localhost
      port: 9001
      weight: 3
    - id: backend-2
      host: localhost
      port: 9002
      weight: 2
    - id: backend-3
      host: localhost
      port: 9003
      weight: 1

healthcheck:
  interval-ms: 5000
```

---

## Admin API

All admin endpoints are on port `8080` under `/admin`.

### List all backends with live stats
```bash
GET /admin/backends
```
```json
[
  {
    "id": "backend-1",
    "host": "localhost",
    "port": 9001,
    "weight": 3,
    "healthy": true,
    "activeConnections": 2,
    "totalRequestsServed": 1500,
    "totalFailures": 0
  }
]
```

### Register a new backend at runtime
```bash
POST /admin/backends
Content-Type: application/json

{"id": "backend-4", "host": "localhost", "port": 9004, "weight": 1}
```

### Deregister a backend
```bash
DELETE /admin/backends/backend-1
```

### Switch algorithm at runtime (zero downtime)
```bash
PUT /admin/strategy
Content-Type: application/json

{"strategy": "LEAST_CONNECTIONS"}
```

Available strategies: `ROUND_ROBIN`, `WEIGHTED_ROUND_ROBIN`, `LEAST_CONNECTIONS`, `IP_HASH`

### List available strategies
```bash
GET /admin/strategies
```

---

## Health Checking

The health checker runs every 5 seconds and pings `GET /health` on each backend.

- **3 consecutive failures** → backend marked `DOWN`, removed from rotation
- **Successful health check** → backend marked `UP`, re-added to rotation
- **5xx responses on real traffic** also count toward failure threshold
- Health checks run in **parallel** via `Flux.flatMap` — one slow backend doesn't delay others

---

## Failure Scenarios Handled

| Scenario | Behaviour |
|---|---|
| Backend dies mid-request | `doFinally` cleans up connections, returns `502` |
| All backends down | Immediate `503` — no connection attempt, no hanging |
| Backend timeout | Returns `504` after 30s, connections released |
| Backend returns 5xx consistently | Marked `DOWN` after 3 consecutive failures |
| Backend recovers | Auto re-added within one health check cycle (5s) |
| Client disconnects mid-request | `doFinally` fires on cancel signal, backend connection released |
| Strategy switched mid-traffic | In-flight requests complete with old strategy, new requests use new strategy |

---

## Key Design Decisions

**`doFinally` over `doOnSuccess`/`doOnError` for connection cleanup**

`doFinally` fires on three signals: `onComplete`, `onError`, and `onCancel`. Using `doOnSuccess` would miss client disconnections (cancel signal), causing `activeConnections` to drift upward over time — making Least Connections unreliable under real traffic.

**`CopyOnWriteArrayList` for backend registry**

Reads (every request) vastly outnumber writes (health checks every 5s, admin registration almost never). COWAL gives lock-free reads at the cost of array copy on write — exactly right for this access pattern.

**`volatile` for strategy reference**

Strategy swap via Admin API needs to be visible to all event loop threads immediately. `volatile` guarantees visibility without a lock. Reference assignment is atomic on JVM — no torn reads possible.

**Smooth Weighted Round Robin over naive WRR**

Naive WRR sends 3 requests to A, then 2 to B, then 1 to C — clustered. Nginx's smooth algorithm spreads them evenly (A B A C A B) — better latency distribution and no thundering herd on the highest-weight backend.

---

## What I Learned

- How Netty's event loop and NIO selector work under WebFlux
- Why `doFinally` is the correct hook for resource cleanup in reactive pipelines
- Thread safety without locks — `AtomicInteger`, `CopyOnWriteArrayList`, `volatile`
- The difference between a load balancer (scale) and an API gateway (control)
- How `epoll`/`kqueue` enables one thread to handle thousands of concurrent I/O operations
- Smooth weighted round robin algorithm (Nginx implementation)
- WebFlux functional routing vs annotated controllers — and why mixing them causes body consumption bugs
