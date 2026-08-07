(ns graphden.test-infra.impls
  "Shared scaffolding for unit tests that exercise a package module's
   base-fn impls DIRECTLY (no DB): slurp+eval the module through the
   loader's private `load-module-impls` — the same path the runtime
   takes — and read individual impls out of the resulting map. Four
   NSes carried identical copies of the var + fixture + reader.")


(def ^:dynamic *impls*
  nil)


(defn impls-fixture
  "`:once` fixture: bind `*impls*` to `pkg`/`module`'s loaded impls map."
  [pkg module]
  (fn [f]
    (binding [*impls* ((requiring-resolve 'graphden.packages.loader/load-module-impls)
                       pkg module)]
      (f))))


(defn impl-of
  "The impl fn for `kw` — impls-map values are either bare fns OR
   `{:impl … :*-rule …}` maps (the registry merges the rule shape)."
  [kw]
  (let [entry (get *impls* kw)]
    (or (and (map? entry) (:impl entry))
        (and (fn? entry) entry)
        (throw (ex-info (str "No impl for " kw) {:available (keys *impls*)})))))
