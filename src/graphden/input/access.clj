(ns graphden.input.access
  "Storage admission for verified package input types, named binding commands
   and offline migration. Capabilities are bound by their server-side writer.")


(def ^:dynamic *offline?* false)


(def ^:dynamic *package-input-types*
  "Exact owner → input descriptor pairs certified by the package sync or child writer."
  {})


(def offline-entities #{:input-binding :input-binding-version})


(def ^:dynamic ^:private *command* nil)


(def ^:dynamic ^:private *merge-edge* nil)


(defn with-merge-snapshot
  "Scope framework provenance to the single merge record being published."
  [edge f]
  (let [active (volatile! true)]
    (binding [*merge-edge* {:edge edge :thread (Thread/currentThread) :active active}]
      (try (f) (finally (vreset! active false))))))


(defn- current-edge
  []
  (when (and *merge-edge* (identical? (Thread/currentThread) (:thread *merge-edge*)) @(:active *merge-edge*))
    (:edge *merge-edge*)))


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
  (when (or (and (= entity :branch-merge) (:input-versions-materialized? data)
                 (not= (dissoc data :input-versions-materialized?) (current-edge)))
            (and (= entity :input-binding-version) (some #(some? (get data %)) [:merge-edge-id :merge-source-version-id :merge-selected-version-id])
                 (not (and (current-edge) (= (:id (current-edge)) (:merge-edge-id data))
                           (= (:target-branch-id (current-edge)) (:branch-id data)) (uuid? (:merge-source-version-id data)) (uuid? (:merge-selected-version-id data))))))
    (throw (ex-info "Structured merge provenance is owned by the branch writer" {:type :authz/forbidden})))
  (when (and (not *offline?*)
             (or (contains? offline-entities entity)
                 (and (contains? #{:fn :fn-version} entity)
                      (some? (:input-type-fn-id data))
                      (not= (:input-type-fn-id data)
                            (get *package-input-types* (if (= entity :fn) (:id data) (:fn-id data))))))
             (not (command-write? entity data)))
    (throw (ex-info "Structured input write requires a server-authorized writer"
                    {:type :input/offline-only :entity entity}))))


(defn assert-public-access!
  "Generic API reads/writes remain disabled even in an offline process."
  [entity]
  (when (contains? offline-entities entity)
    (throw (ex-info "Structured input entities are not public yet"
                    {:type :authz/forbidden :entity entity}))))
