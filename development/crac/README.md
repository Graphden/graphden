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

### Standalone PoC (package-load only)

| Metric | Value |
|--------|-------|
| Package load (eval 32 impls → 251 base-fns) | **4484 ms** |
| Warm checkpoint image / RSS | 201 MB / ~358 MB |
| **Restore** | **~30–41 ms** (reproducible after the brotli fix) |

### Full system, end-to-end (the real image) — measured 2026-07-12

The `graphden.crac` entrypoint checkpointed with the WHOLE system warm
(3559 fn-entities loaded + type-check sweep + 4085 fns eagerly compiled + the
http-kit web-server started), then restored to serving.

| Metric | Value |
|--------|-------|
| Cold boot to serving | **~141 s** — decomposed: fn-entities + type-check sweep ~55 s, eager-compile 4085 fns ~81 s (the §11 re-measure: boot is **compute-bound**, not dependency-reconnect or class-loading — so AppCDS, which only speeds class-load, barely helps; CRaC captures the whole compiled registry) |
| Checkpoint image | **219 MB** |
| **Restore → serving** (`/health` 200, incl. pool resume + web-server rebind) | **~173–182 ms** (3 runs) |
| **Speedup** | **~780×** (178 ms vs 141 s) |

The ~178 ms restore-to-serving is the headline: CRaC turns graphden's two-minute
compute-bound boot into a sub-200 ms restore, which is what makes scale-to-zero
and frequent placement/rebalance (FLEET_RFC §8) affordable.

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
2. **Live external connections — FIXED (2026-07-12).** The full-system
   checkpoint failed on open sockets; three fixes in `graphden.crac`, each
   confirmed against a real checkpoint→restore:
   - **Lazy class-init on the checkpoint thread.** CRaC runs `beforeCheckpoint`
     on an internal thread; a class whose `<clinit>` first ran there threw
     `ExceptionInInitializerError` (`HikariConfigMXBean`, never touched in normal
     operation). Fix: a dry `quiesce!→resume!` warm-up in `-main` initialises
     every class in both handlers in a normal JVM state before the hook is armed.
   - **Hikari pool left ~minIdle sockets open.** `suspendPool` +
     `softEvictConnections` don't reach zero — the housekeeper reopens idle
     connections. Fix: `setMinimumIdle 0` first, softEvict, poll
     `getTotalConnections` to 0; `resume!` restores minIdle + `resumePool`.
   - **http-kit listener + selector on :8080.** An open `ServerSocketChannel` +
     EPoll selector CRIU can't snapshot. Fix: `quiesce!` stops managed services
     via the reconciler (closing them); `resume!` restarts them with a reconcile
     pass. The fleet-controller's own advisory-lock connection is closed too.

   General rule confirmed: any FD open at checkpoint (socket, selector, mmap)
   must be closed by a `Resource`/quiesce path AND its classes pre-initialised.

## Assessment

CRaC is **feasible, validated end-to-end, and high-value** — the real ~219 MB
image checkpoints and restores to serving in **~178 ms vs a ~141 s cold boot
(~780×)**. All blockers are fixed (native-mmap reproducibility; the three
resource-quiesce issues above). What remains before a production rollout is
operational, not a feasibility question: bake the checkpoint in CI via
`Dockerfile.crac` (privileged CRIU + a reachable Postgres at the same JDBC_URL),
and confirm the same-topology constraints on the target cluster. **Recommended
to adopt** as the FLEET_RFC §8 footprint track; it directly unblocks
scale-to-zero (T5.3).

## Running the standalone PoC

```bash
export CRAC_JDK=/path/to/zulu-crac-jdk-21   # download from Azul (crac_supported=true)
sudo apt-get install -y criu                # or use $CRAC_JDK/lib/criu
criu check                                  # must say "Looks good"; needs privileged caps
./run-crac-poc.sh
```

## Restore IMAGE — checkpoint IN the same-base container (2026-07-12)

`build-checkpoint.sh` checkpoints on the HOST (JDK at `$CRAC_JDK`, jar + native
under `./target`). But CRIU snapshots the ABSOLUTE path of every file mapped into
memory, and NONE of those host paths exist inside the `Dockerfile.crac` restore
container (`$JAVA_HOME=/opt/java/openjdk`, jar + checkpoint + native under
`/app`). A restore of a host checkpoint therefore dies immediately with
`warp: error: Cannot open mapped file …` — for the JDK libs, the jar, and the
brotli `.so` in turn. (Found while packaging the restore image.)

Fix: checkpoint IN a container from the **same base image** the restore uses, with
everything under `/app`. `build-checkpoint-in-container.sh` does this:

```bash
JDBC_URL=jdbc:postgresql://localhost:5435/graphden ./build-checkpoint-in-container.sh
docker build -f ../../Dockerfile.crac -t graphden:crac ../..
docker run -d --privileged --network host --security-opt seccomp=unconfined \
  --cap-add=CHECKPOINT_RESTORE --cap-add=SYS_PTRACE --cap-add=SYS_ADMIN graphden:crac
```

Verified: the restore image serves `/health` **200 in ~411 ms** (full: CRIU
restore + pool resume + web-server rebind), vs the ~141 s cold boot — ~340× in a
real container. The 411 ms includes docker start + a coarse poll; the raw restore
is ~30-40 ms (§ Results). This closes "the restore IMAGE works"; the host
`build-checkpoint.sh` stays for a bare-metal same-path deploy.

### The DB endpoint is frozen too (same-topology extends to Postgres)

`resume!` does NOT re-read `JDBC_URL` — it resumes the SAME `HikariDataSource`
captured in the checkpoint, re-opening sockets to the **same `jdbcUrl` +
credentials**. So the restore-time Postgres must be reachable at the exact URL +
creds the checkpoint used; you cannot bake against DB A and set `JDBC_URL=B` at
restore. In production (cloud/RDS PG) front it with a stable logical endpoint
(k8s `ExternalName` or a PgBouncer proxy holding the real secret), identical at
checkpoint (CI, throwaway PG) and restore (prod, cloud PG). Full write-up +
the env-portable `resume!`-rebuild alternative: `deploy/kind/keda/README.md`
§ "Production: cloud PG" and FLEET_RFC §8.

## Production flow (as-built)

The full system integration lives in `src/graphden/crac.clj` (quiesce/resume the
pool + LISTEN + advisory-lock connections + managed services around a checkpoint)
with `Dockerfile.crac` (restore image) and `build-checkpoint-in-container.sh`
(the checkpoint phase — path-aligned, the recommended one) or `build-checkpoint.sh`
(host, bare-metal same-path only). The quiesce→resume cycle is tested against real
DB resources in `test/graphden/crac_test.clj` (no checkpoint needed — it validates
the risky part: that closing and re-establishing the connections keeps the system
working).

```bash
# 1. checkpoint phase — needs a reachable Postgres (synced graph) + privileged CRIU
#    AND must run as uid 1001 (the restore image's USER) — CRIU restores under
#    the checkpoint-time uid. Run it in a container whose USER is 1001; the
#    script's guard aborts otherwise.
export CRAC_JDK=/path/to/zulu-crac-jdk-21
export JDBC_URL=jdbc:postgresql://postgres:5432/graphden   # SAME url the runtime uses
bb rebuild                       # build target/executor-server.jar
./build-checkpoint.sh            # → target/crac-checkpoint + target/native (as uid 1001)

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
- **Matching uid** — checkpoint and restore must run as the same uid (1001).
  `build-checkpoint.sh` guards on `CHECKPOINT_UID` (default 1001 = Dockerfile.crac's
  `USER`); run the checkpoint in a container whose USER is 1001.
