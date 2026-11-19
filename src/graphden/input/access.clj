(ns graphden.input.access
  "Fail closed while v1 remains the production authority. Offline rehearsal
   binds this operator-only dynamic capability; no request field enables it.")


(def ^:dynamic *offline?* false)


(def offline-entities #{:input-binding :input-binding-version})


(defn assert-write!
  [entity data]
  (when (and (not *offline?*)
             (or (contains? offline-entities entity)
                 (and (contains? #{:fn :fn-version} entity)
                      (some? (:input-type-fn-id data)))))
    (throw (ex-info "Structured input storage is available only to offline migration"
                    {:type :input/offline-only :entity entity}))))


(defn assert-public-access!
  "Generic API reads/writes remain disabled even in an offline process."
  [entity]
  (when (contains? offline-entities entity)
    (throw (ex-info "Structured input entities are not public yet"
                    {:type :authz/forbidden :entity entity}))))
