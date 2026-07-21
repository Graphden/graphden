# Persistent kind fleet — local integration environment

A standing local Kubernetes cluster (via [kind](https://kind.sigs.k8s.io/))
running the graphden **fleet** (2 executor pods, leader-locked placement
controller), publicly reachable at **https://example.com:9443**. It complements —
does not replace — the `docker-compose` dev instance (localhost:9002), which
stays the fast inner-loop for non-fleet work.

## Layout

| File | Role |
|------|------|
| `cluster.yaml` | kind cluster config — maps NodePort 30080 → host port 30080 |
| `postgres.yaml` | in-cluster Postgres (ephemeral; a managed DB in prod) |
| `redeploy.sh` | build-current-image → `kind load` → `helm upgrade` → roll pods |
| `Caddyfile` | TLS reverse proxy: `example.com:9443` → the fleet NodePort |
| `graphden-caddy.service` | systemd unit to keep Caddy up (install manually) |

## One-time setup

```bash
kind create cluster --config deploy/kind/cluster.yaml     # persistent cluster
bb rebuild                                                # build graphden-executor:latest
deploy/kind/redeploy.sh                                   # postgres + fleet (2 pods)
```

## Redeploy after a code change

```bash
bb rebuild && deploy/kind/redeploy.sh
```

`bb rebuild` also refreshes the docker-compose instance; `redeploy.sh` pushes the
same image into the fleet and rolls the pods.

## Public TLS front (example.com:9443)

Port **443 belongs to another service on the host — untouched.** graphden takes a
separate port. Caddy fronts the fleet's host NodePort (30080) with a Let's
Encrypt cert obtained via the HTTP-01 challenge (port 80; TLS-ALPN, which needs
443, is disabled in the Caddyfile).

Run it persistently (host-level — review before enabling):

```bash
sudo cp deploy/kind/graphden-caddy.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now graphden-caddy
```

Or ad-hoc: `caddy run --config deploy/kind/Caddyfile --adapter caddyfile`.

## Notes

- The cluster survives across sessions but not a host reboot unless docker
  restarts its node container; re-run the one-time setup if the cluster is gone
  (`kind get clusters`).
- Postgres is ephemeral (no PVC) — the fleet reseeds the graph on boot.
- Verified end-to-end 2026-07-12: SRV membership, leader election, the internal
  cell-command endpoint, cross-pod transport, and public TLS (see
  `../../docs/FLEET_DEPLOY.md § Local verification with kind`).
