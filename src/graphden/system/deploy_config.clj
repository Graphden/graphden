(ns graphden.system.deploy-config
  "Boot-time snapshot of the PUBLIC deployment settings the platform's own
   UI needs at request time — the hub URL, the feedback intake URL/flag,
   the asset-override rescue hatch.

   Why a snapshot and not `:env` on the request path: every tenant request
   on the cloud runs under the request-level effect gate
   (`cloud-request-allowed-effects`, docs/TENANCY_SEAM.md § Effect gate),
   and `:env` is outside it by design — the process environment holds
   secrets. A platform partial that reached `:env` therefore answered 500
   for every tenant (the 2026-09-02 branch-popover / feedback incident).
   The fix is structural: the operator DECLARES which settings are public
   by listing them under `:exec/deploy-config` in the system config (Aero
   `#env` resolves them once at boot, in the platform process, under no
   gate); the init-key stores that map here; the `:deploy-config` base-fn
   (`resources/packages/core/system/impls.clj`) is a pure atom read with
   `:effects #{}`. Nothing outside the declared map ever exists in the
   snapshot, so a tenant graph cannot enumerate or read any other
   variable — and a secret never belongs here (vault / Clojure config).

   Same shape as `graphden.system.api-routes-js`: producer = init-key,
   reader = one thin defbase."
  (:require
    [clojure.string :as str]))


(def ^:private !snapshot
  "Atom holding `{setting-key value-or-nil}`. nil until
   `:exec/deploy-config` fires."
  (atom nil))


(defn- normalize-value
  "A setting is text or absent: blank env values read as nil so callers
   see one 'unset' shape (docker-compose's `${VAR:-}` passthrough makes
   unset and empty indistinguishable anyway)."
  [v]
  (when (and (some? v) (not (str/blank? (str v))))
    (str v)))


(defn install!
  "Store `settings` (`{keyword text-or-nil}`) as THE snapshot. Rejects a
   non-keyword key loudly — the declared map is config, and a typo there
   must fail boot, not read as nil forever."
  [settings]
  (doseq [k (keys settings)]
    (when-not (keyword? k)
      (throw (ex-info (str ":exec/deploy-config :settings keys must be keywords, got " (pr-str k))
                      {:type :validation-error/deploy-config :key k}))))
  (reset! !snapshot (into {} (map (fn [[k v]] [k (normalize-value v)])) settings)))


(defn read-setting
  "The declared value for `k`, or nil when unset, undeclared, or the
   init-key never ran (test bootstraps). Anything but a keyword reads as
   nil — a tenant graph gets no other shape of access."
  [k]
  (when (keyword? k)
    (get @!snapshot k)))


(defn declared-keys
  "The declared setting keys (for diagnostics / tests)."
  []
  (set (keys @!snapshot)))


(defn clear!
  "Reset the snapshot to nil — halt-key / test teardown."
  []
  (reset! !snapshot nil))
