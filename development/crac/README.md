# CRaC integration PoC

Proof-of-concept for the footprint/start track of
[docs/FLEET_RFC.md](../../docs/FLEET_RFC.md) § 5.1: use **CRaC** (Coordinated
Restore at Checkpoint) to skip graphden's expensive boot by restoring a warm
JVM image, instead of GraalVM native-image (shelved — the eval-at-boot package
model blocks it).

This PoC is **not wired into production**. It exercises the *real* graphden
package loader under a CRaC JDK, measures the win, and — importantly — surfaces
the concrete integration blockers a production CRaC path must solve.

## What it does

`graphden_crac_poc.clj` calls the real `packages.loader/load-packages`
(the expensive part: `eval` of 32 `impls.clj` with macroexpansion — the same
work that makes GraalVM infeasible), then idles so it can be checkpointed.
`run-crac-poc.sh` boots it under a CRaC JDK, checkpoints after load, and times a
restore.

## Results (dev sandbox, 2026-07)

Environment: kernel 5.15 (`CAP_CHECKPOINT_RESTORE` present), CRIU 3.16.1
(`criu check` → "Looks good"), Azul Zulu 21.0.11 CRaC JDK.

| Metric | Value |
|--------|-------|
| Package load (eval 32 impls → 251 base-fns) | **4484 ms** |
| Full cold boot, for context (§5.1) | ~35 s documented / ~113 s measured |
| Warm checkpoint image / RSS | 201 MB / ~358 MB |
| **Restore** | **~30–41 ms** (reproducible after the brotli fix) — >100× vs load, >800× vs cold boot |

The ~41 ms restore is the headline: CRaC can turn a tens-of-seconds boot into a
sub-100 ms restore, which is what makes scale-to-zero and frequent
placement/rebalance (FLEET_RFC §8) affordable.

## Blockers found (what a production CRaC path must solve)

1. **Native library temp-mmap — `brotli4j` — FIXED.** brotli4j extracted
   `libbrotli.so` to a *random* `${tmpdir}/com_aayushatharva_brotli4j_<nanoTime>/`
   dir (marked `deleteOnExit`) and mmapped it. CRIU snapshots that mapping; on
   restore the file must exist at the same path, but the random, self-deleting
   temp dir is gone → `Cannot open mapped file …/libbrotli.so`, so only the first
   restore worked (~41 ms). **Resolved** in `web/http/impls.clj`: extract the
   `.so` once to a deterministic path (`-Dgraphden.native-lib.dir`, default
   `${tmpdir}/graphden-native`) and point brotli4j at it via its supported
   `brotli4j.library.path` property (direct `System.load`, no temp, no
   `deleteOnExit`). Re-verified: **3/3 restores OK, ~30–38 ms**. General rule for
   any future extract-and-mmap native lib: pin it to a stable, image-shipped path.
2. **Live external connections.** This PoC deliberately avoids them
   (`load-packages` opens no sockets). A full-system checkpoint additionally needs
   CRaC `Resource` handlers to close **before** and reopen + **rewire after** every
   live resource: the Hikari→Postgres pool, the http-kit listener, the
   vault/openbao client, the notify-listener + advisory-lock connections, and SSE.
   Rewiring is the real work — the executor context holds a reference to the
   storage that wraps the pool, so reopening the pool means re-threading it.

## Assessment

CRaC is **feasible and high-value** (substrate confirmed, ~30 ms reproducible
restore demonstrated). Blocker 1 (native-mmap reproducibility) is **fixed**; the
remaining production integration is the resource close/reopen/rewire (blocker 2)
plus a `Dockerfile.crac` that checkpoints at build time. Recommended as the
FLEET_RFC §8 footprint track, after (or alongside) the Phase-0 placement work.

## Running the standalone PoC

```bash
export CRAC_JDK=/path/to/zulu-crac-jdk-21   # download from Azul (crac_supported=true)
sudo apt-get install -y criu                # or use $CRAC_JDK/lib/criu
criu check                                  # must say "Looks good"; needs privileged caps
./run-crac-poc.sh
```

## Production flow (as-built)

The full system integration lives in `src/graphden/crac.clj` (quiesce/resume the
pool + LISTEN + advisory-lock connections around a checkpoint) with
`Dockerfile.crac` (restore image) and `build-checkpoint.sh` (the checkpoint
phase). The quiesce→resume cycle is tested against real DB resources in
`test/graphden/crac_test.clj` (no checkpoint needed — it validates the risky
part: that closing and re-establishing the connections keeps the system
working).

```bash
# 1. checkpoint phase — needs a reachable Postgres (synced graph) + privileged CRIU
export CRAC_JDK=/path/to/zulu-crac-jdk-21
export JDBC_URL=jdbc:postgresql://postgres:5432/graphden   # SAME url the runtime uses
bb rebuild                       # build target/executor-server.jar
./build-checkpoint.sh            # → target/crac-checkpoint + target/native

# 2. bake the restore image and run it (privileged for CRIU)
docker build -f Dockerfile.crac -t graphden:crac .
docker run --cap-add=CHECKPOINT_RESTORE --cap-add=SYS_PTRACE --cap-add=SYS_ADMIN \
           --security-opt seccomp=unconfined -p 8080:8080 graphden:crac
```

Two hard constraints (standard CRaC same-topology rules):

- **Same JDBC_URL** at checkpoint and restore — the pool config is baked into the
  image; `graphden.crac` cycles the pool's *connections*, it does not reconfigure
  the URL. Point both at a DB reachable by the same address (e.g. a k8s Service
  name).
- **Privileged CRIU** at restore (the caps above), and the brotli native lib at a
  stable `-Dgraphden.native-lib.dir` present in the image (blocker 1).
