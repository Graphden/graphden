;; Type-check sweep over the first-party package corpus — no DB.
;;
;; The sweep is what keeps the corpus at ZERO type-check failures
;; (`graphden.types.check/allowed-type-check-failures` is `#{}`;
;; docs/TYPE_SYSTEM_DECISIONS.md). Production runs it inside
;; `packages.sync/sync-fn-entities-from-packages!` at boot — which
;; means a corpus regression used to surface only when a real
;; instance booted: every test fixture passes `:skip-type-check? true`
;; (the golden bootstrap included), so `bb ci` was blind to it and the
;; first red was `bb wt up` / the gate's e2e stack, minutes in.
;;
;; This runs the SAME sweep over the same corpus with the same
;; allowlist gate, on package data alone — the storage passes the
;; sweep does not read. ~1 min, in `bb ci`'s lint group.
;;
;;   bb type-sweep            # the shipped first-party set
;;   bb type-sweep core web   # a subset
(ns type-sweep
  (:require
    [graphden.executor.registry.core :as registry-core]
    [graphden.packages.loader :as pkg]
    [graphden.packages.records.parse :as records-parse]
    [graphden.packages.sync :as sync]))


(def ^:private default-packages
  ;; The prod `:package-names` list (resources/system-prod.edn).
  ["core" "storage" "web" "app-base" "app" "registry" "mcp"])


(defn- seed-rich-types!
  "Mirror the sync's seed passes: base-fn declarations first (their
   `:args` carry the real `:type`s), then every fn-def. A mis-shaped
   entry is recoverable by the sweep itself, exactly as in production."
  [defs]
  (doseq [[fn-name fn-def] defs]
    (when fn-name
      (try (registry-core/record-rich-types! fn-name fn-def)
           (catch Exception _ nil)))))


(defn -main
  [& args]
  (let [package-names (if (seq args) (vec args) default-packages)
        packages (pkg/load-packages package-names)
        base-fn-defs (:base-fn-defs packages)
        fn-defs (:fn-defs packages)
        extra-defs (into {}
                         (keep (fn [[fn-name fn-def]]
                                 (when fn-name [fn-name (assoc fn-def :name fn-name)])))
                         base-fn-defs)]
    (println (str "type-sweep: " (count package-names) " packages, "
                  (count base-fn-defs) " base-fns, " (count fn-defs) " fn-defs"))
    ;; Aliases BEFORE rich-types, so `:type :port` records its
    ;; structural `[:refine :int …]` form (sync does the same).
    (sync/register-type-aliases! fn-defs)
    (seed-rich-types! extra-defs)
    (seed-rich-types! (into {} (keep (fn [fd] (when (:name fd) [(:name fd) fd]))) fn-defs))
    (let [expanded (records-parse/expand-inline-anons-in-module fn-defs)]
      (seed-rich-types! (into {} (keep (fn [fd] (when (:name fd) [(:name fd) fd]))) expanded))
      (try
        (#'sync/run-type-check-sweep! expanded extra-defs false nil)
        (println "type-sweep: 0 failures — the corpus type-checks clean")
        (System/exit 0)
        (catch Exception e
          (println (ex-message e))
          (println (str "\ntype-sweep: FAILED — "
                        (count (:unexpected (ex-data e) (:stale (ex-data e))))
                        " fn-def(s). Fix the fn-defs; the allowlist is for"
                        " architectural known-debt only (docs/TYPE_SYSTEM_DECISIONS.md)."))
          (System/exit 1))))))


(apply -main *command-line-args*)
