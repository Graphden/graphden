#!/usr/bin/env bash
# Produce a CRaC checkpoint IN A CONTAINER from the SAME base image the restore
# image uses (Dockerfile.crac → azul/zulu-openjdk:21-jdk-crac), so every mmap'd
# path — the JDK libs, the uberjar, the brotli native lib, the checkpoint dir —
# matches on restore.
#
# WHY NOT the host-based build-checkpoint.sh: CRIU snapshots ABSOLUTE paths of
# every file mapped into memory. A host checkpoint captures the JDK at
# $CRAC_JDK, the jar + native under ./target — none of which exist at those
# paths inside the restore container ($JAVA_HOME=/opt/java/openjdk, jar at /app).
# Restore then dies with "Cannot open mapped file …". Checkpointing in the same-
# base container keeps all four paths identical (everything under /app + the
# base's /opt/java). This is the CI-bake recipe.
#
# Requires: docker with privileged + CAP_CHECKPOINT_RESTORE/SYS_PTRACE/SYS_ADMIN
# (the azul crac image bundles criu), a reachable Postgres at $JDBC_URL holding a
# synced graph, and target/executor-server.jar (bb rebuild). Postgres MUST be
# reachable at the SAME $JDBC_URL on restore (standard CRaC same-topology rule).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BASE_IMAGE="${CRAC_BASE_IMAGE:-azul/zulu-openjdk:21-jdk-crac}"
: "${JDBC_URL:?set JDBC_URL to the Postgres the restore image will also use}"
DB_USERNAME="${DB_USERNAME:-graphden}"
DB_PASSWORD="${DB_PASSWORD:-graphden}"
NET="${DOCKER_NETWORK:-host}"

[ -f "$ROOT/target/executor-server.jar" ] || { echo "missing target/executor-server.jar — bb rebuild first"; exit 1; }
rm -rf "$ROOT/target/crac-checkpoint" "$ROOT/target/native"
mkdir -p "$ROOT/target/crac-checkpoint" "$ROOT/target/native"

echo "▶ checkpointing in a $BASE_IMAGE container (all paths under /app)…"
docker run --rm --privileged --network "$NET" \
  --security-opt seccomp=unconfined \
  -e JDBC_URL="$JDBC_URL" -e DB_USERNAME="$DB_USERNAME" -e DB_PASSWORD="$DB_PASSWORD" \
  -v "$ROOT/target:/app" \
  "$BASE_IMAGE" \
  bash -c 'set -e
    chmod 777 /app/crac-checkpoint /app/native
    java -XX:CRaCCheckpointTo=/app/crac-checkpoint -Dgraphden.native-lib.dir=/app/native \
      -cp /app/executor-server.jar clojure.main -m graphden.crac &
    PID=$!
    echo "  waiting for warm-ready…"
    for _ in $(seq 1 600); do [ -f /tmp/graphden-crac.ready ] && break; sleep 0.5; done
    [ -f /tmp/graphden-crac.ready ] || { echo "  warm boot failed"; exit 1; }
    echo "  checkpointing (graphden.crac quiesces pool + LISTEN + advisory-lock + services)…"
    jcmd "$PID" JDK.checkpoint
    wait "$PID" 2>/dev/null || true'

echo "✓ checkpoint at target/crac-checkpoint ($(du -sh "$ROOT/target/crac-checkpoint" | cut -f1)); native at target/native"
echo "  now: docker build -f Dockerfile.crac -t graphden:crac ."
echo "  run: docker run --privileged --network host --security-opt seccomp=unconfined \\"
echo "         --cap-add=CHECKPOINT_RESTORE --cap-add=SYS_PTRACE --cap-add=SYS_ADMIN graphden:crac"
