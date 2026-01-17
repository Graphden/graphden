# Executor Runtime Architecture

> **Status**: Design document for upcoming implementation
> **Last updated**: 2026-01-17

This document describes the runtime architecture for executing functions as web services.

---

## Overview

The executor is a long-running HTTP server that:
1. Loads all functions into memory at startup
2. Builds routing table from route-functions
3. Handles incoming HTTP requests by executing corresponding handler functions
4. Supports horizontal scaling via Kubernetes

---

## Core Concepts

### Executor as HTTP Server

Executor is not a function-per-container model. It's a single long-running process that:
- Holds all execution graphs in memory (preloaded at startup)
- Exposes HTTP endpoints based on route-functions
- Executes handlers with near-zero overhead (no DB lookup per request)

```
┌─────────────────────────────────────────────────────────────────┐
│                         Executor                                 │
│                                                                  │
│  At startup:                                                    │
│  1. Connect to storage (PostgreSQL/Datomic)                     │
│  2. Load ALL execution graphs into memory                       │
│  3. Find all route-functions (inheritors of http-route schema)  │
│  4. Build routing table                                         │
│  5. Start HTTP server                                           │
│                                                                  │
│  On request:                                                    │
│  1. Match route (O(log n) with trie)                           │
│  2. Execute handler function (graphs already in memory)         │
│  3. Return response                                             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Route Functions

Routes are defined as regular functions, not special entities. A route-function connects a path to a handler.

**Path as reusable function:**

```
fn-schema: identity
  args: {value: any}
  returned-type: any
  base-fn-name: "identity"  ; returns value as-is

fn: login-path (inherits identity)
  args: {value: "/api/login"}

;; Now login-path can be reused:
;; - In route definition
;; - In HTML templates (form action)
;; - In redirects
```

**Route definition:**

```
fn-schema: http-route
  args:
    - method: text          ; "GET", "POST", etc.
    - path: fn              ; function returning path string
    - handler: fn           ; function handling request
  returned-type: null       ; cannot be called as regular function

fn: login-route
  fn-schema-id: http-route
  args:
    method: "POST"
    path: ref<login-path>       ; reusable path function
    handler: ref<login-handler>
```

**Handler function:**

```
fn-schema: http-handler
  args:
    - request: jsonb        ; {method, path, headers, body, query-params, path-params}
  returned-type: jsonb      ; {status, headers, body}

fn: login-handler
  fn-schema-id: http-handler
  args: ...                 ; handler implementation
```

### Path Reuse Example

```
;; Define path once
fn: login-path
  args: {value: "/api/login"}

;; Use in route
fn: login-route
  args: {path: ref<login-path>, handler: ref<login-handler>, method: "POST"}

;; Use in template
fn: login-page
  args: {
    template: "<form action='{{action}}' method='POST'>..."
    action: ref<login-path>   ; same path, guaranteed consistency
  }
```

---

## Kubernetes Deployment

### MVP Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Kubernetes                                │
│                                                                  │
│  Ingress                                                        │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ *.graphden.io → executor-service                            ││
│  └─────────────────────────────────────────────────────────────┘│
│                              │                                   │
│                              ▼                                   │
│  Service: executor-service                                      │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ selector: app=executor                                      ││
│  │ Automatic load balancing between pods                       ││
│  │ Automatic removal of unhealthy pods                         ││
│  └─────────────────────────────────────────────────────────────┘│
│                              │                                   │
│              ┌───────────────┼───────────────┐                  │
│              ▼               ▼               ▼                  │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │   Executor   │ │   Executor   │ │   Executor   │            │
│  │   (all fns)  │ │   (all fns)  │ │   (all fns)  │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
│                                                                  │
│  Deployment: executor                                           │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ replicas: 3 (HPA can adjust based on load)                  ││
│  │ Pod crashes → K8s automatically restarts                    ││
│  │ Rolling updates with zero downtime                          ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Key points:**
- All executors are identical, hold all functions
- K8s Service handles load balancing automatically
- K8s Deployment handles restarts and scaling
- No custom service discovery needed

### Future: Dedicated Executors (Premium Feature)

For performance-critical functions, dedicated executors with custom domains:

```
┌─────────────────────────────────────────────────────────────────┐
│  Ingress (dynamically updated)                                  │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ *.graphden.io      → executor-service (default pool)        ││
│  │ api.customer1.com  → customer1-executor-service (dedicated) ││
│  │ fast.customer2.io  → customer2-executor-service (dedicated) ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
│  Executor Controller (our service)                              │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ Watches DB for functions marked as "dedicated"              ││
│  │ Creates/deletes K8s Deployments, Services, Ingress rules    ││
│  │ Manages function groups and executor assignments            ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Function Groups (Future)

For large-scale deployments, functions can be grouped:

1. **Automatic grouping** based on usage patterns and dependencies
2. **Executor specialization** — each executor loads only assigned groups
3. **Smart routing** — requests routed to executors that have the function loaded

```
┌─────────────────────────────────────────────────────────────────┐
│  Smart Router / Gateway                                         │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ 1. Extract fn-id from request                               ││
│  │ 2. Lookup function's groups                                 ││
│  │ 3. Find executors supporting those groups                   ││
│  │ 4. Route to least-loaded executor                           ││
│  └─────────────────────────────────────────────────────────────┘│
│                              │                                   │
│         ┌────────────────────┼────────────────────┐             │
│         ▼                    ▼                    ▼             │
│  Executor (G1,G2)     Executor (G2,G3)     Executor (G1,G3)    │
└─────────────────────────────────────────────────────────────────┘
```

For MVP: single group containing all functions.

---

## Cache Invalidation

When functions change in DB, executors need to update their in-memory cache.

**Options (storage-agnostic):**
1. **Polling** — periodically check for changes
2. **Push notifications** — PostgreSQL NOTIFY / Datomic tx-report-queue
3. **Versioned cache** — compare version numbers

For MVP: polling with reasonable interval (e.g., 10 seconds).

Future: Add `StorageChangeNotifier` protocol to storage-protocol for push-based invalidation.

---

## WebSocket Support

Same model as HTTP routes:

```
fn-schema: ws-route
  args:
    - path: fn
    - on-connect: fn      ; (connection) → any
    - on-message: fn      ; (connection, message) → response
    - on-close: fn        ; (connection) → any
  returned-type: null

fn: chat-ws-route
  fn-schema-id: ws-route
  args:
    path: ref<chat-ws-path>
    on-message: ref<chat-message-handler>
```

Executor at startup finds ws-route inheritors and configures WebSocket endpoints.

**State management**: For MVP, connection state is in-memory (single executor). For scaling, external state (Redis pub/sub) can be added later.

---

## Streaming Responses

Handlers can return streams for large responses:

```clojure
;; Handler returns
{:status 200
 :headers {"Content-Type" "application/octet-stream"}
 :body <stream>}  ; InputStream, Channel, or lazy seq
```

Executor passes stream directly to client without buffering. Requires `:stream` type in field-types and base-functions for stream operations.

---

## Request/Response Flow

```
Client Request
      │
      ▼
┌─────────────────┐
│    Ingress      │  TLS termination, basic routing
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ K8s Service     │  Load balancing between executor pods
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────┐
│                         Executor                                 │
│                                                                  │
│  1. Parse request (method, path, headers, body)                 │
│  2. Match route in routing table                                │
│  3. Extract path-params if any (/users/:id → {:id "123"})      │
│  4. Build request map:                                          │
│     {:method "GET"                                              │
│      :path "/users/123"                                         │
│      :path-params {:id "123"}                                   │
│      :query-params {:page "1"}                                  │
│      :headers {...}                                             │
│      :body {...}}                                               │
│  5. Execute handler function with request as argument           │
│  6. Handler returns: {:status 200 :headers {...} :body ...}    │
│  7. Send response to client                                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Open Questions

1. **Route function returned-type**: `null` (cannot be called) or returns handler?
2. **Path conflict detection**: How to validate unique paths within organization?
3. **Middleware pattern**: Composition of handler functions or separate mechanism?
4. **Graceful shutdown**: How handler functions signal they need time to complete?

---

## Implementation Plan

### Phase 1: Basic Executor HTTP Server
- [ ] Executor starts HTTP server (http-kit)
- [ ] Loads all execution graphs at startup
- [ ] Endpoint: POST /execute with {fn-id, args}
- [ ] Health check endpoint

### Phase 2: Route Functions
- [ ] Define http-route fn-schema
- [ ] Executor discovers route-functions at startup
- [ ] Builds routing table
- [ ] Matches requests to handlers

### Phase 3: Kubernetes Deployment
- [ ] Dockerfile for executor
- [ ] K8s Deployment, Service, Ingress manifests
- [ ] HPA for autoscaling

### Phase 4: Cache Invalidation
- [ ] Polling-based cache refresh
- [ ] StorageChangeNotifier protocol (future)

### Phase 5: WebSocket Support
- [ ] Define ws-route fn-schema
- [ ] WebSocket handling in executor

### Phase 6: Dedicated Executors (Premium)
- [ ] Executor Controller service
- [ ] Dynamic Ingress rule management
- [ ] Function group support
