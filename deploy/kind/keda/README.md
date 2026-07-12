# CRaC scale-from-zero on KEDA (kind)

Scale-to-zero where the cold start is a **CRIU restore**, not a full boot. On the
`kind-gfleet` cluster this serves `/health` **200 in ~3.1–3.5 s from 0 pods**, vs
**~115 s** for a Knative full-boot revision of the same image — ~33×.

## Why KEDA, not Knative

Knative was the obvious scale-to-zero substrate and its half is demonstrated
(`../knative/`, 115 s cold start). But **stock Knative cannot host a CRaC
restore**: a ksvc user container may not set `privileged: true` and cannot
`capabilities.add` — the ksvc PodSpec exposes only a restricted `securityContext`
subset. CRaC's `-XX:CRaCRestoreFrom` shells out to CRIU, which needs
`CAP_SYS_ADMIN` / `CAP_CHECKPOINT_RESTORE` / `CAP_SYS_PTRACE` **inside** the
container. So the fast-restore and the serverless autoscaler collide by design.

KEDA scales an ordinary **Deployment**, whose PodSpec is unrestricted — it CAN be
privileged. KEDA's **HTTP add-on** supplies the missing activator: an interceptor
proxy that holds the first request while the Deployment scales 0→1. That gives the
same request-driven scale-from-zero as Knative's activator, over a pod that is
allowed to CRIU-restore.

## Topology

- **`crac-pg`** — a Postgres kept SEPARATE from the fleet's, so the KEDA-scaled
  Deployment (0↔1) is the sole holder of the web-server singleton advisory lock.
- **`crac-checkpoint` Job** — boots graphden fully against `crac-pg` (syncs the
  graph + warms the compiled registry), then `JDK.checkpoint`. Privileged,
  `runAsUser: 0`. Writes checkpoint + brotli native to node hostPath `/crac-store`.
- **`crac-app` Deployment** — same image, command overridden to
  `-XX:CRaCRestoreFrom=/crac/checkpoint`. Privileged, `runAsUser: 0` (must match
  the checkpoint uid — CRaC uid-alignment). `replicas: 0`. Mounts the hostPath
  read-only.
- **`crac-app` HTTPScaledObject** — host `crac.local`, `min 0 / max 1`,
  `scaledownPeriod 30`.

Single-node kind, so the hostPath is shared between the Job and the Deployment.
The restore image is built exactly like `../../../Dockerfile.crac` (same azul CRaC
base) so every mmap'd path — JDK, jar, checkpoint dir, native — matches; see
`development/crac/build-checkpoint-in-container.sh` for why that alignment is load-
bearing.

## Run it

```bash
export KUBECONFIG=~/.kube/config          # kind-gfleet
# 0) image already loaded: docker tag graphden:crac kind.local/graphden-crac:latest
#    && kind load docker-image kind.local/graphden-crac:latest --name gfleet
# 1) KEDA core + HTTP add-on
kubectl apply --server-side -f https://github.com/kedacore/keda/releases/download/v2.16.1/keda-2.16.1.yaml
helm install http-add-on kedacore/keda-add-ons-http -n keda \
  --set interceptor.replicas.min=1 --set interceptor.replicas.max=1
# 2) dedicated postgres, checkpoint, scaled app
kubectl apply -f crac-pg.yaml
kubectl apply -f checkpoint-job.yaml          # ~120 s: boot → sync → checkpoint
kubectl wait --for=condition=complete job/crac-checkpoint --timeout=300s
kubectl apply -f crac-scaled.yaml             # Deployment(0) + Service + HTTPScaledObject
# 3) cold-start from 0, timed, in-cluster (no host curl needed):
kubectl run k --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- \
  curl -s -o /dev/null -w 'code=%{http_code} total=%{time_total}s\n' \
  -H 'Host: crac.local' http://keda-add-ons-http-interceptor-proxy.keda:8080/health
```

## Measured (2026-07-12)

| Path | Cold start (0 → HTTP 200) |
|------|---------------------------|
| Knative ksvc, full boot | ~115 s |
| **KEDA + CRaC restore** | **~3.1–3.5 s** |
| (raw CRIU restore in-pod, manual scale) | ~1.2 s |

The ~3 s includes KEDA request-detect → Deployment 0→1 → k8s schedule → CRIU
restore (~1.2 s) → readiness probe (1 s period) → interceptor forward. Tightening
the probe granularity closes most of the gap to the raw restore.

## Production: cloud PG — `crac-pg` is a demo artifact, not a prod component

`crac-pg` here plays two single-node-kind roles: a clean, uncontended DB so the
checkpoint boot fully starts (incl. the singleton-gated web-server, which
wouldn't bind against the running fleet's DB), and a stable Service-DNS name
reused at restore. Neither role is a production database.

The load-bearing constraint is in `graphden.crac/resume!`: it does **not** re-read
`JDBC_URL` or rebuild the pool — it resumes the SAME `HikariDataSource` frozen in
the checkpoint, re-opening sockets to the **same `jdbcUrl` + credentials**. So the
CRaC same-topology rule extends to the DB endpoint: you cannot bake against
`crac-pg` and then just set `JDBC_URL=…rds.amazonaws.com` at restore — the pool
still dials the frozen address.

Production shape with a provider-managed PG:

- **Checkpoint (CI, build-time)** runs against an EPHEMERAL throwaway Postgres —
  the `postgres` service container in `.github/workflows/crac-bake.yml`, exactly
  `crac-pg`'s role. The cloud PG is NOT involved (CI must not touch prod RDS).
- **Restore (prod, run-time)** pods talk to the cloud PG — but it must be
  reachable at the SAME `jdbcUrl` + creds the CI checkpoint used. Front it with a
  **stable logical endpoint** identical in both contexts:
  - a k8s `ExternalName` Service (`graphden-db` → `xxx.rds.amazonaws.com`), or
  - a **PgBouncer / connection proxy** at `graphden-db:5432` that holds the real
    cloud secret and upstreams to RDS. The proxy also resolves credentials: the
    pool freezes `username`/`password` too, so if the cloud creds differ from the
    CI ones, `resume!` fails on auth unless a proxy presents stable creds.

  Bake and restore both use e.g. `jdbc:postgresql://graphden-db:5432/graphden`;
  only the backend behind the name swaps (throwaway PG in CI, cloud PG in prod).

Alternative (not shipped): teach `resume!` to REBUILD `:db/postgres` from the
current env on restore instead of resuming the frozen pool — env-portable images,
no endpoint pinning. It's a real refactor, not a one-liner: the whole `system` map
holds direct references to the frozen `HikariDataSource`, so repointing needs a
`DataSource` indirection wrapper + re-init of every consumer. See FLEET_RFC §8.
