(ns graphden.packages.write-invalidation-parity-test
  "Parity guard for the service-restart-on-mutation drift class.

   A fn-graph WRITE must invalidate through `:invalidate-after-write` (=
   `crud.entities/invalidate!`), which — beyond dropping the ctx cache — ALSO
   restarts cron/loop services holding the pre-edit compiled closure AND emits
   the cross-pod NOTIFY. The bare `:invalidate-graph-cache` base-fn does ONLY
   the cache drop. Sequence-append once parented on the bare one and left
   services firing the stale graph (fixed 2026-08-25); the fix routed it through
   `:invalidate-after-write`.

   This pins that NO graph-write fn-def reintroduces the bare invalidator, so a
   new write path can't silently skip the service-restart hook (the recurring
   parallel-path-drift class). If a genuine non-mutation cache-refresh ever needs
   the bare primitive, whitelist it here WITH a reason."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]))


(def ^:private packages-root "resources/packages")


(def ^:private allowed-bare-invalidator-users
  "fn-def names permitted to parent on the BARE `:invalidate-graph-cache`. Empty
   today — every graph WRITE uses `:invalidate-after-write`. Adding a name means
   'this use does NOT mutate the graph, so it needs no service-restart hook'."
  #{})


(defn- fns-edn-files
  []
  (->> (file-seq (io/file packages-root))
       (filter #(and (java.io.File/.isFile %)
                     (= "fns.edn" (java.io.File/.getName %))))))


(defn- bare-invalidator-users
  "Names of fn-defs in `edn-map` whose definition tree parents (anywhere —
   top-level or an inline arg) on `:invalidate-graph-cache`."
  [edn-map]
  (keep (fn [fdef]
          (when (some (fn [node]
                        (and (map? node) (= :invalidate-graph-cache (:parent node))))
                      (tree-seq coll? seq fdef))
            (:name fdef)))
        (:fns edn-map)))


(deftest no-graph-write-uses-the-bare-invalidator
  (let [offenders (->> (fns-edn-files)
                       (mapcat (fn [f] (bare-invalidator-users (edn/read-string (slurp f)))))
                       (remove allowed-bare-invalidator-users)
                       sort)]
    (is (empty? offenders)
        (str "fn-def(s) parent on the bare :invalidate-graph-cache, which skips "
             "the service-restart hook + cross-pod NOTIFY — route the write "
             "through :invalidate-after-write (or whitelist a non-mutation use "
             "with a reason): " (pr-str offenders)))))
