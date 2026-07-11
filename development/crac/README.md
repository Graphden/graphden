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
| **Restore (native mmaps intact)** | **~41 ms** — >100× vs load, >800× vs cold boot |

The ~41 ms restore is the headline: CRaC can turn a tens-of-seconds boot into a
sub-100 ms restore, which is what makes scale-to-zero and frequent
placement/rebalance (FLEET_RFC §8) affordable.

## Blockers found (what a production CRaC path must solve)

1. **Native library temp-mmap — `brotli4j` (`deps.edn`, the `:brotli-bytes`
   base-fn).** brotli4j extracts `libbrotli.so` to a *random* `/tmp/com_aayushatharva_brotli4j_*`
   dir and mmaps it. CRIU snapshots that mapping; on restore the file must exist
   at the same path. It doesn't (temp cleanup / a fresh container), so restore
   fails: `Cannot open mapped file …/libbrotli.so`. The first restore right after
   checkpoint succeeded (file still present) — hence the ~41 ms number — but it is
   not reproducible without handling this.
   *Fix directions:* pin brotli's extraction to a stable, image-baked path;
   re-extract in an `afterRestore` hook; or make brotli load lazily post-restore
   (it's only used by one base-fn). Any long-lived native mmap needs the same
   treatment.
2. **Live external connections.** This PoC deliberately avoids them
   (`load-packages` opens no sockets). A full-system checkpoint additionally needs
   CRaC `Resource` handlers to close **before** and reopen + **rewire after** every
   live resource: the Hikari→Postgres pool, the http-kit listener, the
   vault/openbao client, the notify-listener + advisory-lock connections, and SSE.
   Rewiring is the real work — the executor context holds a reference to the
   storage that wraps the pool, so reopening the pool means re-threading it.

## Assessment

CRaC is **feasible and high-value** (substrate confirmed, ~41 ms restore
demonstrated), but the production integration is a **real feature**, not a config
flip: solve the native-mmap reproducibility (blocker 1) and the resource
close/reopen/rewire (blocker 2). Recommended as the FLEET_RFC §8 footprint track,
after (or alongside) the Phase-0 placement work.

## Running it

```bash
export CRAC_JDK=/path/to/zulu-crac-jdk-21   # download from Azul (crac_supported=true)
sudo apt-get install -y criu                # or use $CRAC_JDK/lib/criu
criu check                                  # must say "Looks good"; needs privileged caps
./run-crac-poc.sh
```
