#!/usr/bin/env bash
# CRaC integration PoC for graphden (docs/FLEET_RFC.md § 5.1).
#
# Proves that a warm graphden JVM (packages eval'd — the expensive,
# native-image-blocking boot work) can be checkpointed and restored in a
# fraction of the boot time, and surfaces the integration blockers.
#
# Requirements (see the RFC): a CRaC-enabled JDK + CRIU with
# CAP_CHECKPOINT_RESTORE/SYS_ADMIN/SYS_PTRACE (run privileged).
#
#   criu check            # must say "Looks good"
#   ./run-crac-poc.sh
#
# Measured 2026-07 in the dev sandbox (kernel 5.15, criu 3.16.1,
# Zulu 21.0.11 CRaC):
#   - package load (eval 32 impls.clj → 251 base-fns):  4484 ms
#   - warm image / RSS:                                 201 MB / ~358 MB
#   - restore (mmaps intact):                           ~41 ms   (>100x)
#   - BLOCKER: brotli4j extracts libbrotli.so to a random /tmp dir and
#     mmaps it; CRIU restore fails once that temp file is gone
#     ("Cannot open mapped file .../libbrotli.so"). See README.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JDK="${CRAC_JDK:?set CRAC_JDK to a CRaC-enabled JDK home}"
CP="$(cd "$ROOT" && clojure -Spath):$HERE"
IMG="${IMG:-/tmp/gcp}"

command -v criu >/dev/null || { echo "criu not installed"; exit 1; }
criu check >/dev/null || { echo "criu check failed"; exit 1; }

rm -f /tmp/gpoc.ready; rm -rf "$IMG"; mkdir -p "$IMG"
echo "▶ boot (package load) under CRaC JDK…"
setsid "$JDK/bin/java" -cp "$CP" -XX:CRaCCheckpointTo="$IMG" -Xmx1g \
  clojure.main -m graphden-crac-poc </dev/null >/tmp/gpoc.out 2>&1 &
PID=$!
for _ in $(seq 1 300); do [ -f /tmp/gpoc.ready ] && break; sleep 0.5; done
[ -f /tmp/gpoc.ready ] || { echo "boot failed"; tail -20 /tmp/gpoc.out; exit 1; }
echo "  $(cat /tmp/gpoc.ready)"

echo "▶ checkpoint…"
"$JDK/bin/jcmd" "$PID" JDK.checkpoint
sleep 3
echo "  image: $(du -sh "$IMG" | cut -f1)"

echo "▶ restore…"
t0=$(date +%s%3N)
setsid "$JDK/bin/java" -XX:CRaCRestoreFrom="$IMG" </dev/null >/tmp/grestore.out 2>&1 &
for _ in $(seq 1 2000); do grep -q "successful\|error" /tmp/grestore.out && break; sleep 0.002; done
t1=$(date +%s%3N)
echo "  restore: $((t1 - t0)) ms"
grep -iE "restore|error" /tmp/grestore.out | tail -2 || true
# cleanup: the restored process resumes on the ORIGINAL (checkpoint-time) PID
for p in $(pgrep -f "CRaCRestoreFrom"); do kill -9 "$p" 2>/dev/null || true; done
