(ns graphden.input.access
  "Storage admission for verified package input types, named binding commands
   and offline migration. Capabilities are bound by their server-side writer.")


(def ^:dynamic *offline?* false)


(def ^:dynamic *package-input-types*
  "Exact owner → input descriptor pairs certified by the package sync or child writer."
  {})


(def offline-entities #{:input-binding :input-binding-version})


(def ^:dynamic ^:private *command* nil)


(def ^:dynamic ^:private *branch-deletes* nil)


(defn with-branch-binding-deletes
  "Admit the exact binding rows collected by the locked branch cascade."
  [binding-ids version-ids operation]
  (binding [*branch-deletes* {:input-binding (set binding-ids)
                              :input-binding-version (set version-ids)}]
    (operation)))


(defn with-binding-write
  [owner id operation]
  (binding [*command* {:owner owner :binding id}] (operation)))


(defn- command-write?
  [entity data]
  (case entity
    :input-binding (and (:binding *command*)
                        (or (empty? data)
                            (and (= (:binding *command*) (:id data))
                                 (or (nil? (:fn-id data)) (= (:owner *command*) (:fn-id data))))))
    :input-binding-version (and (:binding *command*) (= (:binding *command*) (:input-binding-id data))
                                (= (:owner *command*) (:fn-id data)))
    false))


(defn assert-write!
  [entity data]
  (when (and (not *offline?*)
             (not (command-write? entity data))
             (or (contains? offline-entities entity)
                 (and (contains? #{:fn :fn-version} entity)
                      (some? (:input-type-fn-id data))
                      (not= (:input-type-fn-id data)
                            (get *package-input-types* (if (= entity :fn) (:id data) (:fn-id data)))))))
    (throw (ex-info "Structured input write requires a server-authorized writer"
                    {:type :input/offline-only :entity entity}))))


(defn assert-public-access!
  "Generic API reads/writes remain disabled even in an offline process."
  [entity]
  (when (contains? offline-entities entity)
    (throw (ex-info "Structured input entities are not public yet"
                    {:type :authz/forbidden :entity entity}))))


(defn assert-delete!
  "Admit named input deletion only for a server command or a locked cascade."
  [entity id]
  (when-not (contains? (get *branch-deletes* entity) id)
    (assert-write! entity {:id id})))
