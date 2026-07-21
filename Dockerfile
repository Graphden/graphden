# Simple single-stage Dockerfile for local development
# Pre-built uberjar must be placed in target/executor-server.jar

FROM eclipse-temurin:25-jre-jammy

# Install curl for healthcheck. Pinned via base-image's apt repo
# (jammy = Ubuntu 22.04 LTS) — a security update REMOVES the superseded
# version from the archive, so a stale pin does not warn, it fails the
# build outright ("Version ... was not found"). Re-pin then; check the
# current one with:
#   docker run --rm eclipse-temurin:21-jre-jammy \
#     bash -c 'apt-get update -qq && apt-cache policy curl'
# This is the hadolint DL3008 contract: deliberate awareness of every
# dependency patch instead of latent "whatever apt ships today" drift.
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl=7.81.0-1ubuntu1.25 \
    && rm -rf /var/lib/apt/lists/*

# Marks every executor image we build — canonical and per-agent alike — so
# `wt gc` can reclaim OUR stale/dangling layers by label without ever
# considering an unrelated image on the same host. Kept BELOW the apt layer
# so editing it never invalidates that (expensive) cache.
LABEL graphden.image="executor"

WORKDIR /app

# Copy pre-built uberjar
COPY target/executor-server.jar /app/executor-server.jar

# Run as a non-root user — trivy AVD-DS-0002 best-practice. The
# uberjar lives in /app, owned by `graphden`; nothing in the
# runtime needs root.
RUN useradd --system --uid 1001 --user-group --shell /sbin/nologin \
        --home-dir /nonexistent graphden \
    && chown -R graphden:graphden /app
USER graphden

# Set default environment variables
ENV PORT=8080
ENV DB_POOL_SIZE=10

# Expose port
EXPOSE 8080

# Health check — start-period covers the full cold boot before any
# failure counts. Boot is ~35s today (package load + type-check
# sweep + compile-eager); the prior 30s let healthchecks fire before
# /health was bound, killing the container after 3 retries (~120s
# into life) — visible as a docker restart loop under any test load.
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# Run the application with optimized JVM settings.
#
# `ExitOnOutOfMemoryError` makes the JVM exit on heap-OOM rather than
# trying to continue in a degraded state. Without it, a request
# handler that runs out of heap catches the OOM, returns a 500, and
# the JVM keeps serving — every subsequent request also OOMs because
# the leak is still there. With it, the container exits cleanly →
# Docker's restart policy (compose `restart: unless-stopped` in prod,
# `--restart=on-failure:3` in the e2e stack) brings up a fresh JVM
# within ~10s. Found because the e2e isolated stack cascaded on a
# memory leak around row->entity / cheshire-encode of the
# `/api/graph/entities` payload (see 2026-06-20 logs).
# MEMORY: the ceiling is the container's, and that is the whole mechanism.
#
# `UseContainerSupport` measures `MaxRAMPercentage` against the CONTAINER's
# memory limit. With no limit set, that is the whole HOST — this JVM sized
# itself for an 8.8 GB heap on an 11.7 GB box, and so would every other executor
# on it (an agent instance, the e2e stack). G1 then grows the committed heap to
# absorb each allocation burst (a graph compile, a demo seed, a test sweep) and
# never gives it back: measured on the demo, RSS reached 4.19 GB over three days
# while the LIVE SET was 125 MB. Not a leak — collectable the whole time, and
# nothing ever asked. `docker-compose.yml` now sets the limit; that bounds it.
#
# WHAT DOES NOT WORK — do not re-add it: `-XX:G1PeriodicGCInterval`. It is the
# documented answer (JEP 346, "promptly return unused memory"), and on this
# service it fires exactly never. The periodic GC is skipped if ANY collection
# happened during the interval, and the executor is never that quiet: the
# service reconciler ticks every 15 s and the NOTIFY listener runs, so young GCs
# tick over continuously. Measured over 7 idle minutes with the flag set (both
# with and without `-G1PeriodicGCInvokesConcurrent`): 423 young GCs, ZERO
# periodic collections, committed heap pinned at 826 MB. A hand-rolled
# `System.gc()` in the same JVM dropped committed 826 MB -> 230 MB and RSS
# 1.05 GB -> 488 MB, so the JVM CAN uncommit — it simply never decides to.
# ALSO DOES NOT WORK — measured, not assumed: ZGC (`-XX:+UseZGC
# -XX:ZUncommitDelay=60`). It does uncommit on its own schedule, but it commits
# almost the whole ceiling up front: on the same 2 GB-capped instance, committed
# heap 1474 MB of a 1536 MB max — 96% — for a 158 MB live set, and 1.76 GB RSS
# before serving a request, against 0.5-0.7 GB under G1. ZGC is built for large
# heaps and low pause times; on a 125 MB live set its overhead IS the problem it
# would be solving. Rejected.
CMD ["java", \
     "-XX:+UseContainerSupport", \
     "-XX:MaxRAMPercentage=75.0", \
     "-XX:+ExitOnOutOfMemoryError", \
     "-XX:+HeapDumpOnOutOfMemoryError", \
     "-XX:HeapDumpPath=/tmp/heap-dump.hprof", \
     "-XX:G1HeapRegionSize=16m", \
     "-Xlog:gc:stdout:time,level,tags", \
     "-jar", "/app/executor-server.jar"]
# These last two flags come from root-causing the systemic e2e flake (a
# different innocent test each gate run, always green on retry, ~8 of 14 runs),
# untraceable for weeks for one reason: no GC observability. Measured 2026-07-17.
#
#   - The flake is a G1 GC pause: a COMPACTION Full GC at the 2 GB heap, a
#     humongous-allocation concurrent-start pause at the gate's 3 GB heap. Either
#     way stop-the-world — a SIGQUIT thread dump during a "slow" op shows NO
#     thread executing graphden code, all parked in GC. A wait that outlives the
#     pause times out; the retry, outside a pause, passes. Hence "random file,
#     green on retry".
#   - What fills the heap: the live set is only ~250 MB, but the heap runs at
#     ~1.2 GB median because large API responses (`/api/types` 2.4 MB, an
#     `?scope=subtree` up to 4.5 MB, `?scope=index` 1.6 MB) are G1 HUMONGOUS
#     objects (> half a 1 MB region). 756 humongous allocations at idle alone,
#     from the reconciler's periodic graph reads.
#
# `-XX:G1HeapRegionSize=16m` is a PARTIAL MITIGATION, not a fix — say this out
# loud so it is never mistaken for one. It makes objects up to 8 MB non-humongous
# (idle humongous 756 -> 0) and measured the flake ~11 -> ~5 on a loaded 2 GB
# box; but 5-7 still flaked at the gate's 3 GB config, because the allocation
# RATE still fills the heap. Shipped only to reduce the flake enough to keep the
# suite landable. The COMPLETE fix is to stop returning multi-MB responses — the
# SAME over-fetch as `/api/types` in docs/PERF_BUDGETS.md (finding K), tracked as
# separate work. Until then, `run-edit-tests.sh`'s retry masks the residual.
#
#   - Measured NOT to help, do not re-try: more heap (2 GB->3 GB — flake
#     unchanged, so it is allocation-rate, not heap-size);
#     `-XX:InitiatingHeapOccupancyPercent=30` (no heap-median drop). And in the
#     GC-footprint comment above: `G1PeriodicGCInterval` and ZGC, both rejected.
