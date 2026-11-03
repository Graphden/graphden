(ns ^:integration graphden.crud.inheritance-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.inheritance :as inheritance]
    [graphden.crud.validation :as validation]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.decorated-stack-test :as decorated]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/create-container-fixture))


;; This real JDBC decorator's installed authorization seam mirrors its actual
;; write guard. The callback injects a refusal after earlier real SQL writes.
(extend-type graphden.versioning.storage.decorated_stack_test.GuardedStorage
  writer/GraphWriterScope
  (writer-scope [_] nil)
  writer/GraphWriteAuthorization
  (authorize-graph-write! [storage entity _data _id]
    ((:authorize-write storage) :preview entity))
  writer/GraphCreationAuthorization
  (authorize-graph-creation! [storage _data _branch]
    ((:authorize-write storage) :preview-create :fn)))


(defn- new-storage
  ([] (let [storage (setup/create-versioned-test-storage 6)]
        (sp/upsert-entities storage :fn (sp/query-entities (vs/unwrap storage) :fn {}))
        storage))
  ([guard]
   (let [versioned (new-storage)]
     (assoc versioned :base-storage
            (decorated/->GuardedStorage (vs/unwrap versioned) (atom {}) guard)))))


(defn- row!
  [storage name parents]
  (sp/create-entity storage :fn {:name name :parent-ids parents}))


(defn- variation-command
  [target source]
  {:action "variation" :kind "parent-edge" :target-fn-id (:id target)
   :source-fn-id (:id source) :expected-parent-id (:id source)})


(defn- accepted
  [preview]
  (assoc (:request preview) :expected-state (:expected-state preview)
         :accepted-orphan-binding-ids (:orphan-binding-ids preview)))


(defn- attach!
  [storage fn-id name position]
  (let [slot (sp/create-entity storage :slot {:name name :type-fn-id (get setup/primitive-fn-ids :int)})]
    (sp/create-entity storage :fn-slot {:fn-id fn-id :slot-id (:id slot) :position position})
    (:id slot)))


(deftest batch-command-bounds-and-requires-distinct-explicit-targets
  (let [item (fn [id] {:action "reparent" :target-fn-id id :parent-ids []})
        ids (repeatedly 17 random-uuid)]
    (is (thrown? clojure.lang.ExceptionInfo
          (inheritance/parse-command {:action "batch" :commands (mapv item ids)})))
    (is (thrown? clojure.lang.ExceptionInfo
          (inheritance/parse-command {:action "batch" :commands [(item (first ids))
                                                                 (item (first ids))]})))
    (is (thrown? clojure.lang.ExceptionInfo
          (inheritance/parse-command {:action "batch" :commands [{:action "batch"
                                                                  :commands []}]})))))


(deftest reparent-preserves-own-slots-and-tombstones-only-exact-orphans
  (let [s (new-storage) root (row! s "root" []) p (row! s "old" [(:id root)])
        q (row! s "new" [(:id root)]) f (row! s "target" [(:id p)])
        inherited (attach! s (:id p) "old-input" 0)
        own (attach! s (:id f) "own-input" 0)
        orphan (sp/create-entity s :binding {:fn-id (:id f) :slot-id inherited})
        item (sp/create-entity s :binding-list-item {:binding-id (:id orphan) :position 0 :value "old"})
        retained (sp/create-entity s :binding {:fn-id (:id f) :slot-id own :value "keep"})
        preview (inheritance/preview {:storage s} {:action "reparent" :kind "parent-edge"
                                                   :target-fn-id (:id f) :parent-ids [(:id q)]})
        seen (atom []) ctx {:storage s :notify-emitter #(swap! seen conj %)}]
    (is (:allowed preview) (pr-str preview))
    (is (= [(:id orphan)] (:orphan-binding-ids preview)))
    (is (= [(:id item)] (:orphan-item-ids preview)))
    (is (:ok (inheritance/apply! ctx (accepted preview))))
    (is (= [(:id q)] (:parent-ids (sp/read-entity s :fn (:id f)))))
    (is (= "keep" (:value (sp/read-entity s :binding (:id retained)))))
    (is (nil? (sp/read-entity s :binding (:id orphan))))
    (is (nil? (sp/read-entity s :binding-list-item (:id item))))
    (is (some :deleted-at (sp/query-entities (vs/unwrap s) :binding-version {:binding-id (:id orphan)})))
    (is (seq @seen))))


(deftest a-late-decorated-refusal-rolls-back-orphan-deletes-and-publishes-nothing
  (let [armed (atom false)
        s (new-storage (fn [op entity]
                         (when (and @armed (= op :update) (= entity :fn))
                           (throw (ex-info "Late actual authorization refusal" {:type :authz/forbidden})))))
        root (row! s "rollback-root" []) p (row! s "rollback-p" [(:id root)])
        q (row! s "rollback-q" [(:id root)]) f (row! s "rollback-f" [(:id p)])
        slot (attach! s (:id p) "orphan" 0)
        b (sp/create-entity s :binding {:fn-id (:id f) :slot-id slot :value "before"})
        item (sp/create-entity s :binding-list-item {:binding-id (:id b) :position 0 :value "item"})
        preview (inheritance/preview {:storage s} {:action "reparent" :kind "parent-edge"
                                                   :target-fn-id (:id f) :parent-ids [(:id q)]})
        emitted (atom [])]
    (is (:allowed preview) (pr-str preview))
    (reset! armed true)
    (is (= :authz/forbidden (:type (inheritance/apply! {:storage s :notify-emitter #(swap! emitted conj %)}
                                                       (accepted preview)))))
    (is (= [(:id p)] (:parent-ids (sp/read-entity s :fn (:id f)))))
    (is (= "before" (:value (sp/read-entity s :binding (:id b)))))
    (is (= "item" (:value (sp/read-entity s :binding-list-item (:id item)))))
    (is (empty? @emitted))))


(deftest stale-preview-and-unaccepted-orphans-never-delete-current-rows
  (let [s (new-storage) root (row! s "stale-root" []) p (row! s "stale-p" [(:id root)])
        q (row! s "stale-q" [(:id root)]) f (row! s "stale-f" [(:id p)])
        sid (attach! s (:id p) "input" 0)
        b (sp/create-entity s :binding {:fn-id (:id f) :slot-id sid :value "old"})
        command {:action "reparent" :kind "parent-edge" :target-fn-id (:id f) :parent-ids [(:id q)]}
        preview (inheritance/preview {:storage s} command)]
    (is (= :inheritance/orphans-not-accepted
           (:type (inheritance/apply! {:storage s} (assoc (accepted preview) :accepted-orphan-binding-ids [])))))
    (sp/update-entity s :binding (:id b) {:value "new"})
    (is (= :inheritance/stale-preview (:type (inheritance/apply! {:storage s} (accepted preview)))))
    (is (= "new" (:value (sp/read-entity s :binding (:id b)))))
    (is (= [(:id p)] (:parent-ids (sp/read-entity s :fn (:id f)))))))


(deftest batch-reparents-independent-targets-with-one-preview-and-publication
  (let [s (new-storage)
        root (row! s "batch-root" [])
        old (row! s "batch-old" [(:id root)])
        fresh (row! s "batch-new" [(:id root)])
        left (row! s "batch-left" [(:id old)])
        right (row! s "batch-right" [(:id old)])
        slot-id (attach! s (:id old) "old-input" 0)
        left-binding (sp/create-entity s :binding {:fn-id (:id left) :slot-id slot-id :value "left"})
        right-binding (sp/create-entity s :binding {:fn-id (:id right) :slot-id slot-id :value "right"})
        left-item (sp/create-entity s :binding-list-item {:binding-id (:id left-binding)
                                                          :position 0 :value "item"})
        command (inheritance/parse-command
                  {:action "batch" :commands [{:action "reparent" :target-fn-id (:id left)
                                               :parent-ids [(:id fresh)]}
                                              {:action "reparent" :target-fn-id (:id right)
                                               :parent-ids [(:id fresh)]}]})
        preview (inheritance/preview {:storage s} command)
        events (atom [])
        result (inheritance/apply! {:storage s :notify-emitter #(swap! events conj %)}
                                   (accepted preview))]
    (is (:allowed preview) (pr-str preview))
    (is (= #{(:id left) (:id right)} (set (:write-fn-ids preview))))
    (is (= #{(:id left) (:id right)} (set (get-in preview [:write-set :fn]))))
    (is (every? #(contains? (set (:read-fn-ids preview)) %)
                [(:id root) (:id old) (:id fresh) (:id left) (:id right)]))
    (is (= #{(:id left-binding) (:id right-binding)}
           (set (:orphan-binding-ids preview))))
    (is (= [(:id left-item)] (:orphan-item-ids preview)))
    (is (:ok result) (pr-str result))
    (is (:committed result))
    (is (= [(:id fresh)] (:parent-ids (sp/read-entity s :fn (:id left)))))
    (is (= [(:id fresh)] (:parent-ids (sp/read-entity s :fn (:id right)))))
    (is (nil? (sp/read-entity s :binding (:id left-binding))))
    (is (nil? (sp/read-entity s :binding (:id right-binding))))
    (is (nil? (sp/read-entity s :binding-list-item (:id left-item))))
    (is (seq @events))))


(deftest batch-refusal-after-first-write-rolls-back-every-target
  (let [armed (atom false)
        updates (atom 0)
        s (new-storage (fn [op entity]
                         (when (and @armed (= op :update) (= entity :fn)
                                    (= 2 (swap! updates inc)))
                           (throw (ex-info "Refuse second target" {:type :authz/forbidden})))))
        root (row! s "batch-rollback-root" [])
        old (row! s "batch-rollback-old" [(:id root)])
        fresh (row! s "batch-rollback-new" [(:id root)])
        left (row! s "batch-rollback-left" [(:id old)])
        right (row! s "batch-rollback-right" [(:id old)])
        command (inheritance/parse-command
                  {:action "batch" :commands [{:action "reparent" :target-fn-id (:id left)
                                               :parent-ids [(:id fresh)]}
                                              {:action "reparent" :target-fn-id (:id right)
                                               :parent-ids [(:id fresh)]}]})
        preview (inheritance/preview {:storage s} command)
        events (atom [])]
    (is (:allowed preview) (pr-str preview))
    (reset! armed true)
    (is (= :authz/forbidden
           (:type (inheritance/apply! {:storage s :notify-emitter #(swap! events conj %)}
                                      (accepted preview)))))
    (is (= [(:id old)] (:parent-ids (sp/read-entity s :fn (:id left)))))
    (is (= [(:id old)] (:parent-ids (sp/read-entity s :fn (:id right)))))
    (is (empty? @events))))


(deftest batch-rejects-dependent-targets-and-stale-preview
  (let [s (new-storage)
        root (row! s "batch-cas-root" [])
        old (row! s "batch-cas-old" [(:id root)])
        fresh (row! s "batch-cas-new" [(:id root)])
        left (row! s "batch-cas-left" [(:id old)])
        right (row! s "batch-cas-right" [(:id old)])
        dependent (row! s "batch-cas-dependent" [(:id left)])
        command (inheritance/parse-command
                  {:action "batch" :commands [{:action "reparent" :target-fn-id (:id left)
                                               :parent-ids [(:id fresh)]}
                                              {:action "reparent" :target-fn-id (:id right)
                                               :parent-ids [(:id fresh)]}]})
        preview (inheritance/preview {:storage s} command)
        related (inheritance/preview {:storage s}
                                     (inheritance/parse-command
                                       {:action "batch" :commands
                                        [{:action "reparent" :target-fn-id (:id left)
                                          :parent-ids [(:id fresh)]}
                                         {:action "reparent" :target-fn-id (:id dependent)
                                          :parent-ids [(:id fresh)]}]}))]
    (is (:allowed preview) (pr-str preview))
    (is (= :inheritance/batch-dependent-targets (:type related)))
    (sp/update-entity s :fn (:id right) {:description "new edit"})
    (is (= :inheritance/stale-preview (:type (inheritance/apply! {:storage s} (accepted preview)))))
    (is (= [(:id old)] (:parent-ids (sp/read-entity s :fn (:id left)))))
    (is (= [(:id old)] (:parent-ids (sp/read-entity s :fn (:id right)))))))


(deftest batch-variations-use-previewed-identities-and-preserve-siblings
  (let [s (new-storage)
        root (row! s "batch-variation-root" [])
        source (row! s "batch-variation-source" [(:id root)])
        left (row! s "batch-variation-left" [(:id source)])
        right (row! s "batch-variation-right" [(:id source)])
        untouched (row! s "batch-variation-untouched" [(:id source)])
        command (inheritance/parse-command
                  {:action "batch" :commands [(variation-command left source)
                                              (variation-command right source)]})
        preview (inheritance/preview {:storage s} command)
        proposed (mapv #(get-in % [:proposed :id]) (:targets preview))
        result (inheritance/apply! {:storage s} (accepted preview))]
    (is (:allowed preview) (pr-str preview))
    (is (= proposed (mapv :proposed-fn-id (get-in preview [:request :commands]))))
    (is (= 2 (count (set proposed))))
    (is (= (set (concat proposed [(:id left) (:id right)]))
           (set (get-in preview [:write-set :fn]))))
    (is (:ok result) (pr-str result))
    (is (= proposed (mapv :created-fn-id (:targets result))))
    (is (= [(first proposed)] (:parent-ids (sp/read-entity s :fn (:id left)))))
    (is (= [(second proposed)] (:parent-ids (sp/read-entity s :fn (:id right)))))
    (is (= [(:id source)] (:parent-ids (sp/read-entity s :fn (:id untouched)))))
    (is (every? #(= [(:id root)] (:parent-ids (sp/read-entity s :fn %))) proposed))))


(deftest batch-refuses-overlapping-variation-identities
  (let [s (new-storage)
        root (row! s "batch-collision-root" [])
        source (row! s "batch-collision-source" [(:id root)])
        left (row! s "batch-collision-left" [(:id source)])
        right (row! s "batch-collision-right" [(:id source)])
        proposed (random-uuid)
        command (inheritance/parse-command
                  {:action "batch" :commands [(assoc (variation-command left source)
                                                     :proposed-fn-id proposed)
                                              (assoc (variation-command right source)
                                                     :proposed-fn-id proposed)]})
        preview (inheritance/preview {:storage s} command)]
    (is (false? (:allowed preview)))
    (is (= :inheritance/batch-identity-collision (:type preview)))
    (is (= [(:id source)] (:parent-ids (sp/read-entity s :fn (:id left)))))
    (is (= [(:id source)] (:parent-ids (sp/read-entity s :fn (:id right)))))))


(deftest parent-edge-variation-copies-semantic-data-and-preserves-target-composition
  (let [s (new-storage) root (row! s "copy-root" []) p (row! s "copy-source" [(:id root)])
        _ (sp/update-entity s :fn (:id p) {:branch-local? true :lambda-params ["request"]
                                           :expects-effects ["db"]})
        f (row! s "copy-target" [(:id p)]) second-child (row! s "other-child" [(:id p)])
        sid (attach! s (:id p) "source-input" 0)
        own-sid (attach! s (:id f) "target-input" 0)
        source-binding (sp/create-entity s :binding {:fn-id (:id p) :slot-id sid :value "fixed"
                                                     :terminal true :list-closed true})
        target-binding (sp/create-entity s :binding {:fn-id (:id f) :slot-id own-sid :value "target"})
        preview (inheritance/preview {:storage s} (variation-command f p))
        result (inheritance/apply! {:storage s} (accepted preview))
        clone-id (:created-fn-id result)
        clone (sp/read-entity s :fn clone-id)
        copied (first (sp/query-entities s :binding {:fn-id clone-id}))]
    (is (:allowed preview) (pr-str preview))
    (is (:ok result) (pr-str result))
    (is (not= (:id p) clone-id))
    (is (= [(:id root)] (:parent-ids clone)))
    (is (true? (:branch-local? clone)))
    (is (= ["request"] (:lambda-params clone)))
    (is (= ["db"] (:expects-effects clone)))
    (is (= [sid] (mapv :slot-id (sp/query-entities s :fn-slot {:fn-id clone-id}))))
    (is (not= (:id source-binding) (:id copied)))
    (is (= (select-keys source-binding [:slot-id :value :terminal :list-closed])
           (select-keys copied [:slot-id :value :terminal :list-closed])))
    (is (= [clone-id] (:parent-ids (sp/read-entity s :fn (:id f)))))
    (is (= "target" (:value (sp/read-entity s :binding (:id target-binding)))))
    (is (= [(:id p)] (:parent-ids (sp/read-entity s :fn (:id second-child)))))))


(deftest parent-edge-variation-replaces-only-the-selected-mi-edge
  (let [s (new-storage)
        root (row! s "mi-root" [])
        left (row! s "mi-left" [(:id root)])
        right (row! s "mi-right" [(:id root)])
        source (row! s "mi-source" [(:id left) (:id right)])
        other (row! s "mi-other" [(:id root)])
        target (row! s "mi-target" [(:id source) (:id other)])
        command (assoc (variation-command target source) :parent-edge-index 0)
        wrong (assoc command :parent-edge-index 1)
        preview (inheritance/preview {:storage s} command)
        result (inheritance/apply! {:storage s} (accepted preview))
        clone-id (:created-fn-id result)]
    (is (zero? (:parent-edge-index (inheritance/parse-command command))))
    (is (false? (:allowed (inheritance/preview {:storage s} wrong))))
    (is (:allowed preview) (pr-str preview))
    (is (:ok result) (pr-str result))
    (is (= [(:id left) (:id right)]
           (:parent-ids (sp/read-entity s :fn clone-id))))
    (is (= [clone-id (:id other)]
           (:parent-ids (sp/read-entity s :fn (:id target)))))
    (is (= [(:id left) (:id right)]
           (:parent-ids (sp/read-entity s :fn (:id source)))))))


(deftest variation-copies-only-the-selected-long-occurrence-path
  (let [s (new-storage)
        root (row! s "path-root" [])
        side (row! s "path-side" [(:id root)])
        source (row! s "path-source" [(:id root)])
        middle (row! s "path-middle" [(:id source) (:id side)])
        target (row! s "path-target" [(:id middle) (:id side)])
        other (row! s "path-other" [(:id middle)])
        path [(:id target) (:id middle) (:id source)]
        command (assoc (variation-command target source)
                       :expected-parent-id (:id middle)
                       :parent-edge-index 0 :occurrence-path path)
        preview (inheritance/preview {:storage s} command)
        result (inheritance/apply! {:storage s} (accepted preview))
        clone-id (:created-fn-id result)
        target-parents (:parent-ids (sp/read-entity s :fn (:id target)))
        middle-clone (sp/read-entity s :fn (first target-parents))]
    (is (:allowed preview) (pr-str preview))
    (is (= path (:occurrence-path (inheritance/parse-command command))))
    (is (:ok result) (pr-str result))
    (is (= 2 (count target-parents)))
    (is (= (:id side) (second target-parents)))
    (is (= [clone-id (:id side)] (:parent-ids middle-clone)))
    (is (= [(:id source) (:id side)] (:parent-ids (sp/read-entity s :fn (:id middle)))))
    (is (= [(:id middle)] (:parent-ids (sp/read-entity s :fn (:id other)))))
    (is (= [(:id root)] (:parent-ids (sp/read-entity s :fn clone-id))))
    (is (false? (:allowed (inheritance/preview {:storage s} command))))))


(deftest variation-rejects-a-path-through-the-wrong-parent
  (let [s (new-storage)
        source (row! s "wrong-source" [])
        middle (row! s "wrong-middle" [])
        target (row! s "wrong-target" [(:id middle)])
        command (assoc (variation-command target source)
                       :expected-parent-id (:id middle)
                       :occurrence-path [(:id target) (:id middle) (:id source)])
        preview (inheritance/preview {:storage s} command)]
    (is (false? (:allowed preview)))
    (is (= :inheritance/use-site-changed (:type preview)))))


(deftest long-path-variation-rolls-back-all-clones-on-final-refusal
  (let [armed (atom false)
        s (new-storage (fn [op entity]
                         (when (and @armed (= op :update) (= entity :fn))
                           (throw (ex-info "Refuse target write" {:type :authz/forbidden})))))
        source (row! s "rollback-path-source" [])
        middle (row! s "rollback-path-middle" [(:id source)])
        target (row! s "rollback-path-target" [(:id middle)])
        command (assoc (variation-command target source)
                       :expected-parent-id (:id middle)
                       :occurrence-path [(:id target) (:id middle) (:id source)])
        before-ids (set (map :id (sp/query-entities s :fn {})))
        preview (inheritance/preview {:storage s} command)
        source-clone-id (get-in preview [:proposed :id])
        emitted (atom [])]
    (is (:allowed preview) (pr-str preview))
    (reset! armed true)
    (is (= :authz/forbidden
           (:type (inheritance/apply! {:storage s :notify-emitter #(swap! emitted conj %)}
                                      (accepted preview)))))
    (is (nil? (sp/read-entity s :fn source-clone-id)))
    (is (= [(:id middle)] (:parent-ids (sp/read-entity s :fn (:id target)))))
    (is (= before-ids (set (map :id (sp/query-entities s :fn {})))))
    (is (empty? @emitted))))


(deftest mi-ancestor-candidates-preserve-the-unselected-parent
  (let [s (new-storage)
        root (row! s "candidate-root" [])
        top (row! s "candidate-top" [(:id root)])
        left (row! s "candidate-left" [(:id top)])
        right (row! s "candidate-right" [(:id top)])
        source (row! s "candidate-source" [(:id left) (:id right)])
        other (row! s "candidate-other" [(:id root)])
        target (row! s "candidate-target" [(:id source) (:id other)])
        command {:action "candidates" :kind "parent-edge"
                 :target-fn-id (:id target) :source-fn-id (:id source)
                 :parent-edge-index 0}
        candidates (:candidates (inheritance/preview {:storage s} command))]
    (is (= (mapv :id [source left right top root]) (mapv :id candidates)))
    (is (= [true false false false false] (mapv :current? candidates)))
    (is (every? (fn [candidate]
                  (= [(:id candidate) (:id other)]
                     (get-in candidate [:request :parent-ids])))
                candidates))))


(deftest mi-preview-reports-effective-slot-provenance-through-both-diamond-arms
  (let [s (new-storage)
        root (row! s "provenance-root" [])
        slot-id (attach! s (:id root) "shared-input" 0)
        left (row! s "provenance-left" [(:id root)])
        right (row! s "provenance-right" [(:id root)])
        target (row! s "provenance-target" [(:id left)])
        preview (inheritance/preview
                  {:storage s}
                  (inheritance/parse-command {:action "reparent" :target-fn-id (:id target)
                                              :parent-ids [(:id left) (:id right)]}))
        slot (some #(when (= slot-id (:slot-id %)) %)
                   (:effective-slots preview))]
    (is (:allowed preview) (pr-str preview))
    (is (= :shared-input (:name slot)))
    (is (= [(:id root)] (:declared-by-fn-ids slot)))
    (is (= [(:id left) (:id right)] (:via-parent-ids slot)))))


(deftest mi-preview-marks-competing-parent-overlays-without-exposing-values
  (let [s (new-storage)
        root (row! s "overlay-root" [])
        slot-id (attach! s (:id root) "shared-value" 0)
        left (row! s "overlay-left" [(:id root)])
        right (row! s "overlay-right" [(:id root)])
        target (row! s "overlay-target" [(:id left)])
        _ (sp/create-entity s :binding {:fn-id (:id left) :slot-id slot-id :value 11})
        _ (sp/create-entity s :binding {:fn-id (:id right) :slot-id slot-id :value 22})
        command (inheritance/parse-command {:action "reparent" :target-fn-id (:id target)
                                            :parent-ids [(:id left) (:id right)]})
        preview (inheritance/preview {:storage s} command)
        slot (some #(when (= slot-id (:slot-id %)) %) (:effective-slots preview))]
    (is (:allowed preview) (pr-str preview))
    (is (true? (:competing-overlay? slot)))
    (is (= [(:id left) (:id right)] (:overlay-source-fn-ids slot)))
    (is (every? #(not (contains? % :value)) (:effective-slots preview)))))


(deftest preview-keeps-renamed-slot-canonical-identity
  (let [s (new-storage)
        root (row! s "rename-root" [])
        source-id (attach! s (:id root) "original" 0)
        rename (row! s "rename-view" [(:id root)])
        view (sp/create-entity s :slot {:name "renamed" :source-slot-id source-id
                                        :type-fn-id (get setup/primitive-fn-ids :int)})
        _ (sp/create-entity s :fn-slot {:fn-id (:id rename) :slot-id (:id view) :position 0})
        target (row! s "rename-target" [(:id root)])
        preview (inheritance/preview
                  {:storage s}
                  (inheritance/parse-command {:action "reparent" :target-fn-id (:id target)
                                              :parent-ids [(:id rename)]}))
        slot (some #(when (= source-id (:slot-id %)) %) (:effective-slots preview))]
    (is (:allowed preview) (pr-str preview))
    (is (= :renamed (:name slot)))
    (is (= #{(:id root) (:id rename)} (set (:declared-by-fn-ids slot))))
    (is (= [(:id rename)] (:via-parent-ids slot)))
    (is (= [source-id] (get-in preview [:slot-impact :renamed-slot-ids])))
    (is (empty? (get-in preview [:slot-impact :added-slot-ids])))
    (is (empty? (get-in preview [:slot-impact :removed-slot-ids])))))


(deftest mi-variation-keeps-a-shared-ancestor-seal-enforced
  (let [s (new-storage)
        root (row! s "sealed-mi-root" [])
        slot-id (attach! s (:id root) "sealed-input" 0)
        _ (sp/create-entity s :binding {:fn-id (:id root) :slot-id slot-id :terminal true})
        left (row! s "sealed-mi-left" [(:id root)])
        right (row! s "sealed-mi-right" [(:id root)])
        target (row! s "sealed-mi-target" [(:id left) (:id right)])
        preview (inheritance/preview {:storage s}
                                     (assoc (variation-command target left) :parent-edge-index 0))
        result (inheritance/apply! {:storage s} (accepted preview))
        slot (some #(when (= slot-id (:slot-id %)) %) (:effective-slots preview))]
    (is (:allowed preview) (pr-str preview))
    (is (:ok result) (pr-str result))
    (is (= [(:created-fn-id result) (:id right)]
           (:parent-ids (sp/read-entity s :fn (:id target)))))
    (is (= [(:created-fn-id result) (:id right)] (:via-parent-ids slot)))
    (is (some? (validation/write-rej s :binding
                                     {:fn-id (:id target) :slot-id slot-id :value 1})))
    (is (true? (:terminal (first (sp/query-entities s :binding {:fn-id (:id root)})))))))


(deftest variation-final-refusal-removes-the-new-sibling-in-the-same-rollback
  (let [armed (atom false)
        s (new-storage (fn [op entity]
                         (when (and @armed (= op :update) (= entity :fn))
                           (throw (ex-info "Refuse final target write" {:type :authz/forbidden})))))
        root (row! s "clone-rollback-root" []) p (row! s "clone-rollback-source" [(:id root)])
        f (row! s "clone-rollback-target" [(:id p)])
        sid (attach! s (:id p) "owned" 0)
        _ (sp/create-entity s :binding {:fn-id (:id p) :slot-id sid :value "copy"})
        preview (inheritance/preview {:storage s} (variation-command f p))
        clone-id (get-in preview [:proposed :id])]
    (is (:allowed preview) (pr-str preview))
    (reset! armed true)
    (is (false? (:ok (inheritance/apply! {:storage s} (accepted preview)))))
    (is (nil? (sp/read-entity s :fn clone-id)))
    (is (empty? (sp/query-entities s :fn-slot {:fn-id clone-id})))
    (is (empty? (sp/query-entities s :binding {:fn-id clone-id})))
    (is (= [(:id p)] (:parent-ids (sp/read-entity s :fn (:id f)))))))


(deftest own-ref-variation-replaces-only-the-exact-own-reference
  (let [s (new-storage) root (row! s "ref-root" []) source (row! s "ref-source" [(:id root)])
        owner (row! s "ref-owner" []) sid (attach! s (:id owner) "reference" 0)
        binding (sp/create-entity s :binding {:fn-id (:id owner) :slot-id sid :ref-fn-id (:id source)})
        command {:action "variation" :kind "own-ref" :owner-fn-id (:id owner) :source-fn-id (:id source)
                 :binding-id (:id binding) :slot-id sid :expected-old-ref-id (:id source)}
        preview (inheritance/preview {:storage s} command)
        result (inheritance/apply! {:storage s} (accepted preview))]
    (is (:allowed preview) (pr-str preview))
    (is (:ok result) (pr-str result))
    (is (= (:created-fn-id result) (:ref-fn-id (sp/read-entity s :binding (:id binding)))))
    (is (= [] (:parent-ids (sp/read-entity s :fn (:id owner)))))
    (is (false? (:allowed (inheritance/preview {:storage s} command))))))


(deftest readable-refusals-keep-source-navigation-and-cross-branch-guard
  (let [s (new-storage) root (row! s "foreign-root" []) source (row! s "foreign-source" [(:id root)])
        target (row! s "foreign-target" [(:id source)])
        branch (vs/create-branch! s "other") other (vs/switch-branch s (:id branch))
        _ (sp/update-entity other :fn (:id target) {:description "foreign version"})
        preview (inheritance/preview {:storage s} (variation-command target source))]
    (is (false? (:allowed preview)))
    (is (= :constraint-violation/reparent-cross-branch (:type preview)))
    (is (= (:id source) (get-in preview [:source :id])))
    (is (string? (:reason preview)))))


(deftest an-existing-outer-jdbc-transaction-cannot-apply-or-publish
  (let [s (new-storage) root (row! s "outer-root" []) source (row! s "outer-source" [(:id root)])
        target (row! s "outer-target" [(:id source)])
        preview (inheritance/preview {:storage s} (variation-command target source))
        events (atom [])]
    (jdbc/with-transaction [connection (tx/datasource s)]
                           (let [ctx {:storage (tx/with-connection s connection)
                                      :notify-emitter #(swap! events conj %)}
                                 result (inheritance/apply! ctx (accepted preview))]
                             (is (= :graph-write/commit-boundary-required (:type result)))
                             (is (nil? (sp/read-entity s :fn (get-in preview [:proposed :id]))))))
    (is (empty? @events))))


(deftest postcommit-delete-and-notification-failure-preserve-the-committed-result
  (let [s (new-storage) root (row! s "publication-root" []) source (row! s "publication-source" [(:id root)])
        target (row! s "publication-target" [(:id source)])
        preview (inheritance/preview {:storage s} (variation-command target source))
        invoked (atom 0)
        ctx {:storage s
             :notify-emitter (fn [_event]
                               (when (= 1 (swap! invoked inc))
                                 (binding [vs/*tombstone-delete?* true]
                                   (sp/delete-entity s :fn (:id target))))
                               (throw (ex-info "Notification transport failed" {:type :test/notify-failed})))}
        result (inheritance/apply! ctx (accepted preview))]
    (is (:ok result) (pr-str result))
    (is (true? (:committed result)))
    (is (= (get-in preview [:proposed :id]) (:created-fn-id result)))
    (is (some? (sp/read-entity s :fn (:created-fn-id result))))
    (is (nil? (sp/read-entity s :fn (:id target))))
    (is (seq (:publication-warnings result)))))


(defrecord NoopTargetStorage
  [base target-id]

  writer/GraphWriteAuthorization

  (authorize-graph-write!
    [_ entity data id]
    (writer/assert-write-authorized! base entity data id))


  writer/GraphCreationAuthorization

  (authorize-graph-creation!
    [_ data branch]
    (writer/assert-creation-authorized! base data branch))


  sp/StorageIntrospection

  (current-entities [_] (sp/current-entities base))


  (current-fields [_ entity] (sp/current-fields base entity))


  (current-enums [_] (sp/current-enums base))


  (current-enum-values [_ enum] (sp/current-enum-values base enum))


  (schema-metadata [_] (sp/schema-metadata base))


  sp/StorageCRUD

  (create-entity [_ entity data] (sp/create-entity base entity data))


  (read-entity [_ entity id] (sp/read-entity base entity id))


  (update-entity
    [_ entity id data]
    (when-not (and (= entity :fn) (= id target-id))
      (sp/update-entity base entity id data)))


  (delete-entity [_ entity id] (sp/delete-entity base entity id))


  (query-entities [_ entity where] (sp/query-entities base entity where))


  (query-entities [_ entity where opts] (sp/query-entities base entity where opts))


  (query-latest-per-group [_ entity where columns] (sp/query-latest-per-group base entity where columns))


  sp/StorageBatchCRUD

  (create-entities [_ entity rows] (sp/create-entities base entity rows))


  (read-entities [_ entity ids] (sp/read-entities base entity ids))


  (update-entities [_ entity rows] (sp/update-entities base entity rows))


  (upsert-entities [_ entity rows] (sp/upsert-entities base entity rows))


  (delete-entities [_ entity ids] (sp/delete-entities base entity ids))


  (query-ref-many-owners [_ entity field target] (sp/query-ref-many-owners base entity field target)))


(deftest a-decorated-noop-on-the-last-replacement-rolls-back-the-clone
  (let [s (new-storage) root (row! s "noop-root" []) source (row! s "noop-source" [(:id root)])
        target (row! s "noop-target" [(:id source)])
        decorated (assoc s :base-storage (->NoopTargetStorage (vs/unwrap s) (:id target)))
        preview (inheritance/preview {:storage decorated} (variation-command target source))
        result (inheritance/apply! {:storage decorated} (accepted preview))]
    (is (:allowed preview) (pr-str preview))
    (is (= :inheritance/write-refused (:type result)))
    (is (false? (:committed result)))
    (is (nil? (sp/read-entity s :fn (get-in preview [:proposed :id]))))
    (is (= [(:id source)] (:parent-ids (sp/read-entity s :fn (:id target)))))))


(defn- await-waiting-writer!
  [storage]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (let [row (jdbc/execute-one! (tx/datasource storage)
                                   ["SELECT count(*) AS n FROM pg_locks l
                                    JOIN pg_database d ON d.oid=l.database
                                    WHERE l.locktype='advisory' AND NOT l.granted
                                      AND d.datname=current_database()"])
            waiting (long (first (vals row)))]
        (cond (pos? waiting) true
              (> (System/currentTimeMillis) deadline) false
              :else (do (Thread/sleep 10) (recur)))))))


(deftest apply-waits-for-the-writer-then-rechecks-a-concurrently-edited-orphan
  (let [s (new-storage) root (row! s "concurrent-root" []) source (row! s "concurrent-source" [(:id root)])
        parent (row! s "concurrent-parent" [(:id root)]) target (row! s "concurrent-target" [(:id source)])
        sid (attach! s (:id source) "input" 0)
        binding (sp/create-entity s :binding {:fn-id (:id target) :slot-id sid :value "before"})
        preview (inheritance/preview {:storage s} {:action "reparent" :kind "parent-edge"
                                                   :target-fn-id (:id target) :parent-ids [(:id parent)]})
        held (promise) release (promise)
        holder (future (writer/with-write [s :graph]
                                          (sp/update-entity s :binding (:id binding) {:value "concurrent"})
                                          (deliver held true)
                                          (deref release 10000 false)))
        _ (is (true? (deref held 10000 false)))
        applying (future (inheritance/apply! {:storage s} (accepted preview)))]
    (try
      (is (await-waiting-writer! s) "The apply is waiting on the real PG semantic writer lock")
      (is (not (realized? applying)))
      (finally (deliver release true)))
    (is (true? (deref holder 10000 false)))
    (is (= :inheritance/stale-preview (:type (deref applying 10000 {}))))
    (is (= "concurrent" (:value (sp/read-entity s :binding (:id binding)))))
    (is (= [(:id source)] (:parent-ids (sp/read-entity s :fn (:id target)))))))


(deftest publication-observes-the-real-commit-from-an-independent-connection
  (let [s (new-storage) root (row! s "commit-root" []) source (row! s "commit-source" [(:id root)])
        target (row! s "commit-target" [(:id source)])
        preview (inheritance/preview {:storage s} (variation-command target source))
        observations (atom [])
        ctx {:storage s
             :notify-emitter (fn [_event]
                               (with-open [connection (jdbc/get-connection (tx/datasource s))]
                                 (swap! observations conj
                                        {:context tx/*transaction-context*
                                         :parents (:parent-ids (sp/read-entity (tx/with-connection s connection)
                                                                               :fn (:id target)))
                                         :clone (sp/read-entity (tx/with-connection s connection)
                                                                :fn (get-in preview [:proposed :id]))})))}
        result (inheritance/apply! ctx (accepted preview))]
    (is (:ok result))
    (is (seq @observations))
    (is (every? #(nil? (:context %)) @observations))
    (is (every? #(= [(get-in preview [:proposed :id])] (:parents %)) @observations))
    (is (every? :clone @observations))))
