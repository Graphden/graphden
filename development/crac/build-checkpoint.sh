#!/usr/bin/env bash
# Produce a CRaC checkpoint of a WARM graphden JVM for the restore image
# (Dockerfile.crac, docs/FLEET_RFC.md §5.1).
#
# This is the checkpoint PHASE — deliberately NOT a `docker build` RUN, because
# it needs a reachable Postgres (the compile reads the graph) and privileged
# CRIU. Run it in CI / on a build host that has both, then `docker build
# -f Dockerfile.crac` bakes the output (target/crac-checkpoint + target/native)
# into the restore image.
#
# Constraint: the Postgres reachable via $JDBC_URL here must be reachable at the
# SAME url on restore — the pool config is baked into the checkpoint.
#
# Requirements: a CRaC JDK ($CRAC_JDK), criu on PATH (or $CRAC_JDK/lib/criu),
# CAP_CHECKPOINT_RESTORE/SYS_PTRACE/SYS_ADMIN (run the build host / container
# privileged), a synced graph in $JDBC_URL, and target/executor-server.jar.
#
# uid: CRIU restores the process under its CHECKPOINT-time uid, and
# Dockerfile.crac's restore runs as uid 1001 (USER graphden). So this checkpoint
# MUST be taken as uid 1001 too, or the restore fails with a uid mismatch. Run
# the script in a container whose USER is 1001 (privileged for CRIU); the guard
# below enforces it. Override CHECKPOINT_UID here AND Dockerfile.crac's USER
# together if you need a different uid.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JDK="${CRAC_JDK:?set CRAC_JDK to a CRaC-enabled JDK home}"
JAR="$ROOT/target/executor-server.jar"
OUT="$ROOT/target/crac-checkpoint"
NATIVE="$ROOT/target/native"
CHECKPOINT_UID="${CHECKPOINT_UID:-1001}"  # MUST match Dockerfile.crac's USER
: "${JDBC_URL:?set JDBC_URL to the Postgres the restore image will also use}"

if [ "$(id -u)" != "$CHECKPOINT_UID" ]; then
  echo "checkpoint uid $(id -u) != restore-image uid $CHECKPOINT_UID." >&2
  echo "CRIU restores under the checkpoint-time uid, so the resulting image would" >&2
  echo "fail to restore. Run this as uid $CHECKPOINT_UID (e.g. in a container whose" >&2
  echo "USER is $CHECKPOINT_UID, privileged for CRIU), or set CHECKPOINT_UID +" >&2
  echo "Dockerfile.crac's USER to the same value. Aborting." >&2
  exit 1
fi

command -v criu >/dev/null 2>&1 || export PATH="$JDK/lib:$PATH"
criu check >/dev/null || { echo "criu check failed — need privileged caps"; exit 1; }
[ -f "$JAR" ] || { echo "missing $JAR — build the uberjar first (bb rebuild)"; exit 1; }

rm -rf "$OUT" "$NATIVE"; mkdir -p "$OUT" "$NATIVE"

echo "▶ booting warm graphden under the CRaC JDK (compile happens now)…"
"$JDK/bin/java" \
  -XX:CRaCCheckpointTo="$OUT" \
  -Dgraphden.native-lib.dir="$NATIVE" \
  -cp "$JAR" clojure.main -m graphden.crac &
PID=$!

# graphden.crac/-main writes this once packages are loaded, the graph is
# compiled, and the CRaC hooks are registered — i.e. the JVM is warm.
echo "▶ waiting for warm-ready…"
for _ in $(seq 1 600); do [ -f /tmp/graphden-crac.ready ] && break; sleep 0.5; done
[ -f /tmp/graphden-crac.ready ] || { echo "warm boot failed"; kill "$PID" 2>/dev/null; exit 1; }

echo "▶ checkpointing (graphden.crac quiesces the pool + LISTEN + advisory lock)…"
"$JDK/bin/jcmd" "$PID" JDK.checkpoint
wait "$PID" 2>/dev/null || true   # the JVM exits after a successful checkpoint

echo "✓ checkpoint at $OUT ($(du -sh "$OUT" | cut -f1)); native at $NATIVE"
echo "  now: docker build -f Dockerfile.crac -t graphden:crac ."
