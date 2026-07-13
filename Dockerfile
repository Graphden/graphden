# Simple single-stage Dockerfile for local development
# Pre-built uberjar must be placed in target/executor-server.jar

FROM eclipse-temurin:21-jre-jammy

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
ENV STORAGE_TYPE=postgres
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
CMD ["java", \
     "-XX:+UseContainerSupport", \
     "-XX:MaxRAMPercentage=75.0", \
     "-XX:+ExitOnOutOfMemoryError", \
     "-XX:+HeapDumpOnOutOfMemoryError", \
     "-XX:HeapDumpPath=/tmp/heap-dump.hprof", \
     "-jar", "/app/executor-server.jar"]
