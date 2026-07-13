(ns graphden.util.backoff
  "Shared exponential-backoff POLICY for the reconnect loops — the PG NOTIFY
   listener (`storage.postgres.notify`) and the remote SSE source
   (`storage.remote.sse`).

   Only the numeric policy lives here, deliberately NOT the loop. The two
   loops have different control flow — one retries an action until it succeeds
   and then returns to its caller (NOTIFY reconnect), the other supervises a
   long-lived streaming task and restarts it forever on failure (SSE source) —
   so folding them into one function would need a behaviour flag and read
   worse than two clear loops. What they genuinely share is the 1s→30s
   doubling, so that is what is centralised.")


(def initial-ms
  "Backoff before the first retry after a failure."
  1000)


(def max-ms
  "Cap so a prolonged outage retries at a steady interval instead of growing
   unboundedly."
  30000)


(defn next-ms
  "The next backoff after `ms`: double it, capped at `max-ms`.
   `initial-ms` → 2000 → 4000 → … → 30000 (then steady)."
  [ms]
  (min max-ms (* 2 ms)))
