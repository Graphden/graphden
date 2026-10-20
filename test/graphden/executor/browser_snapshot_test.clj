(ns ^:serial graphden.executor.browser-snapshot-test
  "Serial because preview boundary regressions replace process-wide source vars."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.type-check :as type-check]
    [graphden.executor.browser-contracts :as contracts]
    [graphden.executor.browser-plan :as plan]
    [graphden.executor.browser-preview :as preview]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.source-contract :as source-contract]
    [graphden.packages.owned :as owned]
    [graphden.packages.records :as records]
    [graphden.packages.records.types :as record-types]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.remote.core :as remote]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check :as check]
    [graphden.types.check.provenance :as provenance]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]
    [graphden.util.ns-path :as ns-path]))


(defn- failure
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))


(def ^:private constant
  {:name :const :namespace "core.logic" :args {:value {:type 'a}} :return-type 'a})


(defn- fixture
  ([extra leaf] (fixture [constant] extra leaf))
  ([primitives extra leaf]
   (let [definitions (into (vec primitives) (map #(assoc % :namespace "snapshot"))
                           (conj (vec extra) (merge {:name :leaf :parent :const
                                                     :args {:value {:value "public"}}} leaf)))
         names (into {} (map (fn [d] [(:name d) (records/fn-id (:namespace d) (:name d))])) definitions)
         by-name (into {} (map (juxt :name identity)) definitions)
         namespaces (into {} (map (fn [n] [n (random-uuid)]))
                          (distinct (map :namespace definitions)))
         rows (concat (records/boot-primitive-records)
                      (mapcat record-types/inline-fn-type-rows-from-fn-def definitions)
                      (mapcat #(records/parse-fn-def % names by-name) definitions))
         entity-rows (reduce (fn [acc row]
                               (update acc (:kind row) (fnil conj [])
                                       (cond-> (dissoc row :kind)
                                         (:namespace-id row)
                                         (update :namespace-id namespaces))))
                             {} rows)
         namespace-rows (mapv (fn [[n id]] {:id id :name n}) namespaces)
         captured {:graph {:fns (:fn entity-rows) :slots (:slot entity-rows)
                           :fn-slots (:fn-slot entity-rows) :bindings (:binding entity-rows)
                           :list-items (:binding-list-item entity-rows)}
                   :namespaces namespace-rows}
         storage (remote/from-bundle (assoc entity-rows :ns namespace-rows))
         rich (atom {:by-id {} :by-name {}})
         captured-types (atom nil)]
     (binding [registry/*rich-types-override* rich
               registry/*per-org-rich-override* (atom {})
               types/*type-aliases-override* (atom {})
               shapes/*marker-registry-override* (atom {:secret {:monotone? true :hide-result? true}})
               runtime/*per-org-aliases-override* (atom {})]
       (runtime/register-type-aliases-from-db! (:graph captured) ::fixture
                                               (ns-path/path-map namespace-rows))
       (doseq [{:keys [name] :as primitive} primitives]
         (registry/record-rich-types! (get names name) name primitive))
       (doseq [{:keys [name parent]} definitions :when parent]
         (check/check-fn-def! (type-check/reconstruct-fn-def storage (get names name))))
       (reset! captured-types {:aliases (types/aliases-snapshot) :markers (shapes/markers-snapshot)}))
     (merge {:snapshot captured :rich @rich :id (:leaf names) :ids names} @captured-types))))


(defn- policy-of
  [{:keys [rich aliases markers]}]
  (binding [types/*type-aliases-override* (atom aliases)
            shapes/*marker-registry-override* (atom markers)]
    (snapshot/capture-policy rich)))


(defn- export
  [{:keys [snapshot id policy] :as f}]
  (snapshot/export-snapshot snapshot {} {:view id} (or policy (policy-of f))))


(defn- checked-marker-policy
  "Run a real checker with an authored marker-bearing return. The callback
   changes registries only after the checker has captured its input flags."
  [return flags during-check]
  (let [id (random-uuid)
        rich (atom {:by-id {} :by-name {}})
        markers (atom (assoc flags :secret {:monotone? true :hide-result? true}))
        authored {:fn-id id :name :checked-marker :namespace "snapshot"
                  :parent :const :args {} :return-type return}
        original-lookup registry/rich-type-of]
    (binding [registry/*rich-types-override* rich
              registry/*per-org-rich-override* (atom {})
              types/*type-aliases-override* (atom {})
              shapes/*marker-registry-override* markers]
      (registry/record-rich-types! :const constant)
      (with-redefs [registry/rich-type-of
                    (fn [& args]
                      (during-check markers)
                      (apply original-lookup args))]
        (check/check-fn-def! authored))
      {:id id :rich @rich :markers @markers :authored authored})))


(defn- captured-class
  [{:keys [id rich markers]}]
  (binding [shapes/*marker-registry-override* (atom markers)]
    (get-in (snapshot/capture-policy rich) [:classes id])))


(deftest custom-markers-require-an-unchanged-issued-signature
  (let [{:keys [id rich authored] :as f}
        (checked-marker-policy [:view-tag :text] {:view-tag {:hide-result? false}} identity)
        signature (get-in rich [:by-id id])]
    (is (= :plain (captured-class f)))
    (doseq [changed [(assoc signature :return :text :args {})
                     (with-meta (assoc signature :return :text :args {})
                       {::provenance/input (provenance/fingerprint authored)})
                     (assoc signature :return [:view-tag :int])
                     (assoc signature :args {:secret {:type [:secret :text]}})
                     (provenance/stamp signature (assoc authored :namespace "changed"))
                     (assoc signature :fn-id (random-uuid))
                     (with-meta signature {::provenance/input (provenance/fingerprint authored)
                                           ::provenance/marker {:flags (:markers f)}})
                     (edn/read-string (pr-str signature))
                     (json/parse-string (json/generate-string signature) true)]]
      (is (= :unknown (captured-class (assoc-in f [:rich :by-id id] changed)))))
    (testing "copied metadata cannot bless another registry identity"
      (let [other-id (random-uuid)
            copied (assoc signature :fn-id other-id)]
        (is (= :unknown (captured-class (assoc f :id other-id
                                               :rich {:by-id {other-id copied} :by-name {}}))))))
    (testing "registry enrichment and storage certification preserve the witness"
      (let [stored (provenance/stamp-storage-input signature authored "source")]
        (is (= :plain (captured-class (assoc-in f [:rich :by-id id] stored))))
        (is (provenance/matches-storage-input? stored "source"))))))


(deftest frozen-and-current-marker-policies-can-only-deny
  (doseq [[original current expected]
          [[true false :secret-output] [false true :secret-output]
           [false nil :unknown] [true nil :unknown]]]
    (let [f (checked-marker-policy [:view-tag :text]
                                   {:view-tag {:hide-result? original}} identity)
          flags (if (nil? current) {} {:view-tag {:hide-result? current}})]
      (is (= expected (captured-class (assoc f :markers flags))))))
  (testing "a mutation inside checker computation cannot relax captured flags"
    (let [f (checked-marker-policy [:view-tag :text] {:view-tag {:hide-result? true}}
                                   #(swap! % assoc :view-tag {:hide-result? false}))]
      (is (= :secret-output (captured-class f)))))
  (testing "a mutation inside checker computation can tighten captured flags"
    (let [f (checked-marker-policy [:view-tag :text] {:view-tag {:hide-result? false}}
                                   #(swap! % assoc :view-tag {:hide-result? true}))]
      (is (= :secret-output (captured-class f))))))


(deftest custom-marker-witness-never-hides-a-nested-secret
  (doseq [return [[:view-tag [:secret :text]]
                  [:view-tag {:nested [:list [:secret :text]]}]
                  [:view-tag [:map :text [:secret :text]]]
                  [:view-tag [:fn {:input [:secret :text]} :text]]]]
    (let [f (checked-marker-policy return {:view-tag {:hide-result? false}} identity)]
      (is (= :secret-output (captured-class (assoc-in f [:markers :secret :hide-result?] false)))))))


(deftest raw-record-argument-type-field-does-not-hide-secret-siblings
  (let [rich (atom {:by-id {} :by-name {}})
        id (random-uuid)
        payload {:type :text :password [:secret :text]}]
    (binding [registry/*rich-types-override* rich
              registry/*per-org-rich-override* (atom {})
              types/*type-aliases-override* (atom {})
              shapes/*marker-registry-override* (atom {:secret {:hide-result? true}})]
      (registry/record-rich-types! :record-input
                                   {:name :record-input :args {:payload {:type payload}}
                                    :return-type :text})
      (check/check-fn-def! {:fn-id id :name :record-child :parent :record-input :args {}})
      (is (= payload (get-in @rich [:by-id id :args :payload])))
      (is (= :secret-input (registry/trace-capture-class id nil)))
      (is (= :secret-input (get-in (snapshot/capture-policy @rich) [:classes id]))))))


(deftest snapshot-marker-declarations-are-an-independent-veto
  (let [{:keys [id rich markers snapshot] :as f}
        (fixture [{:name :view-tag :marker {:hide-result? false}}] {})
        authored {:fn-id id :name :leaf :namespace "snapshot" :parent :const
                  :args {:value {:value "public"}} :return-type [:view-tag :text]}
        rich-atom (atom rich)
        digest (get (source-contract/fingerprints (:graph snapshot)) id)]
    (binding [registry/*rich-types-override* rich-atom
              registry/*per-org-rich-override* (atom {})
              types/*type-aliases-override* (atom {})
              shapes/*marker-registry-override* (atom markers)]
      (check/check-fn-def! authored))
    (let [signature (provenance/stamp-storage-input (get-in @rich-atom [:by-id id]) authored digest)
          f (assoc-in f [:rich :by-id id] signature)
          policy (policy-of f)]
      (is (= :plain (get-in policy [:classes id])))
      (is (map? (export (assoc f :policy policy))))
      (testing "a forged storage digest cannot bind the checked marker to changed source"
        (let [changed (update-in f [:snapshot :graph :bindings]
                                 (fn [rows]
                                   (mapv (fn [row]
                                           (if (= id (:fn-id row))
                                             (assoc row :value "changed-source") row)) rows)))
              changed-digest (get (source-contract/fingerprints (get-in changed [:snapshot :graph])) id)
              forged (vary-meta signature assoc ::provenance/storage-input
                                {:authored-input (::provenance/input (meta signature))
                                 :source-fingerprint changed-digest})]
          (is (= :type-source-mismatch
                 (:reason (failure #(export (assoc-in changed [:rich :by-id id] forged))))))))
      (testing "storage reconstruction cannot bless a stripped signature or witness"
        (let [plain (assoc signature :return :text :args {})
              source-only (select-keys (meta signature)
                                       [::provenance/input ::provenance/storage-input])]
          (doseq [tampered [plain (with-meta plain source-only)]]
            (is (= :visibility-denied
                   (:reason (failure #(export (assoc-in f [:rich :by-id id] tampered)))))))))
      (doseq [remove? [false true]]
        (let [changed (update-in f [:snapshot :graph :fns]
                                 (fn [rows]
                                   (into [] (keep (fn [row]
                                                    (if (= "view-tag" (:name row))
                                                      (when-not remove?
                                                        (assoc row :constraint [:marker-def {:hide-result? true}]))
                                                      row))) rows)))]
          (is (= :visibility-denied
                 (:reason (failure #(export (assoc changed :policy policy))))))))
      (testing "serialized rich entries cannot recover the original marker witness"
        (let [rebuilt (edn/read-string (pr-str signature))]
          (is (= :type-source-mismatch
                 (:reason (failure #(export (assoc-in f [:rich :by-id id] rebuilt)))))))))))


(deftest canonical-writer-witness-preserves-fresh-check-and-original-policy-veto
  (let [{:keys [id snapshot] :as f} (fixture [] {})
        authored {:fn-id id :name :leaf :namespace "snapshot" :parent :const
                  :args {:value {:value "public"}}}
        rich (atom (:rich f))
        signature (binding [registry/*rich-types-override* rich
                            registry/*per-org-rich-override* (atom {})
                            shapes/*marker-registry-override* (atom (:markers f))]
                    (check/check-fn-def! authored)
                    (get-in @rich [:by-id id]))
        digest (get (source-contract/fingerprints (:graph snapshot)) id)
        witnessed (provenance/stamp-storage-input signature authored digest)
        certified (assoc-in f [:rich :by-id id] witnessed)]
    (is (map? (export certified)))
    (is (= :type-source-mismatch
           (:reason (failure #(export (update-in certified [:snapshot :graph :bindings]
                                                 (fn [rows]
                                                   (mapv (fn [row]
                                                           (if (= id (:fn-id row))
                                                             (assoc row :terminal true)
                                                             row)) rows))))))))
    (is (= :visibility-denied
           (:reason (failure #(export (assoc-in certified [:rich :by-id id :return]
                                                [:secret :text]))))))))


(deftest source-only-metadata-interface-is-checked-without-browser-callability
  (let [metadata-definition (->> (edn/read-string (slurp "resources/packages/web/graph-metadata/fns.edn")) :fns first)
        get-definition (->> (edn/read-string (slurp "resources/packages/core/collections/fns.edn"))
                            :fns (filter #(= :get (:name %))) first)
        f (fixture [(assoc metadata-definition :namespace "web.graph-metadata")
                    (assoc get-definition :namespace "core.collections")]
                   [{:name :lookup :parent :find-function
                     :args {:namespace-path "personal" :name "editor" :principal-id "operator"
                            :org "public" :branch-id {:value (random-uuid)}}}]
                   {:parent :get :args {:coll :lookup :key {:value :fn-id} :default {:value nil}}})
        operation (get-in f [:ids :find-function])]
    (is (nil? (snapshot/validate-source! (:snapshot f) (:id f) (policy-of f))))
    (is (not (contains? (plan/supported-primitive-ids) operation)))))


(deftest source-only-structural-interfaces-are-checked-without-browser-callability
  (let [definition (->> (edn/read-string (slurp "resources/packages/core/collections/fns.edn"))
                        :fns (filter #(= :into (:name %))) first)
        f (fixture [(assoc definition :namespace "core.collections")]
                   [] {:parent :into :args {:to {:value {}} :from {:value [[:view "public"]]}}})
        operation (get-in f [:ids :into])
        changed (update-in f [:snapshot :graph :fns]
                           #(mapv (fn [row]
                                    (if (= operation (:id row))
                                      (assoc row :return-type-fn-id (:leaf (:ids f))) row)) %))]
    (is (nil? (snapshot/validate-source! (:snapshot f) (:id f) (policy-of f))))
    (is (not (contains? (plan/supported-primitive-ids) operation)))
    (is (= :structural-operation-source-mismatch
           (:reason (failure #(snapshot/validate-source! (:snapshot changed) (:id f) (policy-of f))))))))


(deftest map-callback-policy-and-provenance-survive-the-hof-boundary
  (let [map-definition (-> (edn/read-string (slurp "resources/packages/core/hof/fns.edn"))
                           :fns first (assoc :namespace "core.hof"))
        {:keys [ids] :as f}
        (fixture [constant map-definition]
                 [{:name :callback :parent :const :lambda-params []
                   :args {:value {:value "public"}}}]
                 {:parent :map :args {:func :callback :coll [1 2]}})
        callback (:callback ids)]
    (testing "a freshly checked map includes the callback definition and closure"
      (let [functions (:functions (export f))]
        (is (some #(= (str callback) (:id %)) functions))
        (is (some #(= {:kind "closure" :fn (str callback) :lambdaParams [] :translation []}
                      (:expr %))
                  (mapcat :args functions)))))
    (testing "original secret output or captured argument cannot become plain"
      (doseq [signature [(assoc-in f [:rich :by-id callback :return] [:secret :text])
                         (assoc-in f [:rich :by-id callback :args] {:theme [:secret :text]})]]
        (is (= :visibility-denied (:reason (failure #(export signature)))))))
    (testing "changed callback rows cannot use a stale plain signature"
      (let [changed (update-in f [:snapshot :graph :bindings]
                               #(mapv (fn [row]
                                        (cond-> row (= callback (:fn-id row))
                                                (assoc :value "private-replacement"))) %))
            error (failure #(export changed))]
        (is (= :type-source-mismatch (:reason error)))
        (is (= callback (:fn-id error)))
        (is (not (str/includes? (pr-str error) "private-replacement")))))))


(deftest checked-ordinary-copy-exports-without-publishing-types
  (let [{:keys [id] :as f} (fixture [{:name :snapshot-private :marker {:hide-result? true}}] {})
        live-rich (atom {:by-id {} :by-name {}})
        live-aliases (atom {:outside :int})
        live-markers (atom {:secret {:monotone? true :hide-result? true}})]
    (binding [registry/*rich-types-override* live-rich
              types/*type-aliases-override* live-aliases
              shapes/*marker-registry-override* live-markers]
      (let [result (export f)
            leaf (first (filter #(= (str id) (:id %)) (:functions result)))]
        (is (= "public" (plan/decode-value (get-in leaf [:args 0 :expr :value])))))
      (is (= {:by-id {} :by-name {}} @live-rich))
      (is (= {:outside :int} @live-aliases))
      (is (= {:secret {:monotone? true :hide-result? true}} @live-markers)))))


(deftest changed-or-unproven-source-fails-closed
  (let [{:keys [id] :as f} (fixture [] {})]
    (testing "new stored literal with an old plain signature"
      (let [changed (update-in f [:snapshot :graph :bindings]
                               #(mapv (fn [b] (assoc b :value "private-new-value")) %))
            error (failure #(export changed))]
        (is (= :type-source-mismatch (:reason error)))
        (is (= id (:fn-id error)))
        (is (not (str/includes? (pr-str error) "private-new-value")))))
    (testing "old rows with a new plain signature"
      (let [new (fixture [] {:args {:value {:value "new-public-value"}}})]
        (is (= :type-source-mismatch
               (:reason (failure #(export (assoc f :rich (:rich new)))))))))
    (testing "ordinary maps reconstructed from JSON carry no internal proof"
      (is (= :type-source-mismatch
             (:reason (failure #(export (update-in f [:rich :by-id id] with-meta nil)))))))))


(deftest original-policy-remains-a-veto-when-storage-loses-marker-information
  (let [{:keys [id] :as f} (fixture [] {})
        hidden (assoc-in f [:rich :by-id id :return] [:secret :text])]
    ;; assoc preserves the original checker input metadata. The snapshot's
    ;; fresh const check is plain; it must not override this hidden policy.
    (is (= :visibility-denied (:reason (failure #(export hidden)))))))


(deftest stored-marker-alias-is-a-veto-even-with-a-plain-cached-policy
  (let [{:keys [id] :as f}
        (fixture [{:name :hidden-text :union [[:secret :text] :null]}]
                 {:return-type :hidden-text})
        plain (assoc-in f [:rich :by-id id :return] :text)]
    (is (= :visibility-denied (:reason (failure #(export plain)))))))


(deftest original-custom-marker-flags-cannot-be-reinterpreted-by-the-snapshot
  (let [{:keys [id] :as f} (fixture [{:name :private-tag :marker {:hide-result? true}}] {})
        f (assoc-in f [:rich :by-id id :return] [:private-tag :text])
        frozen (policy-of f)
        f (assoc f :policy frozen)]
    (is (= :unknown (get-in frozen [:classes id])))
    (doseq [remove? [false true]]
      (let [changed (update-in f [:snapshot :graph :fns]
                               (fn [rows]
                                 (into []
                                       (keep (fn [row]
                                               (if (= "private-tag" (:name row))
                                                 (when-not remove?
                                                   (assoc row :constraint [:marker-def {:hide-result? false}]))
                                                 row))) rows)))]
        (is (= :visibility-denied (:reason (failure #(export changed))))
            (if remove? "removed marker" "marker changed to plain"))))))


(deftest unresolved-original-alias-is-not-reinterpreted-by-the-snapshot
  (let [{:keys [id] :as f} (fixture [] {})
        original (-> f
                     (assoc-in [:rich :by-id id :return] :private-alias)
                     (assoc-in [:aliases :private-alias] [:secret :text]))
        frozen (policy-of original)]
    (is (= :unknown (get-in frozen [:classes id])))
    (binding [types/*type-aliases-override* (atom {:private-alias :text})]
      (is (= :visibility-denied
             (:reason (failure #(export (assoc f :policy frozen)))))))))


(deftest policy-does-not-reinterpret-late-registry-changes
  (let [{:keys [id] :as f} (fixture [] {})]
    (doseq [return [:private-alias [:private-tag :text]
                    {:nested [:list [:private-tag :text]]}
                    [:fn {:input :private-alias} :text]]]
      (let [old-rich (assoc-in (:rich f) [:by-id id :return] return)]
        ;; A writer changed these views AFTER rich was read, BEFORE policy
        ;; capture. There is no atomic snapshot spanning these registries.
        (binding [types/*type-aliases-override* (atom {:private-alias :text})
                  shapes/*marker-registry-override* (atom {:private-tag {:hide-result? false}})]
          (let [policy (snapshot/capture-policy old-rich)]
            (is (= :unknown (get-in policy [:classes id])))
            (is (= :visibility-denied
                   (:reason (failure #(export (assoc f :rich old-rich :policy policy))))))))))
    (testing "built-in secret semantics cannot be relaxed by a late marker write"
      (binding [shapes/*marker-registry-override* (atom {:secret {:hide-result? false}})]
        (let [policy (snapshot/capture-policy
                       (assoc-in (:rich f) [:by-id id :return] [:secret :text]))]
          (is (= :unknown (get-in policy [:classes id])))
          (is (= :visibility-denied
                 (:reason (failure #(export (assoc f :policy policy)))))))))))


(deftest checker-provenance-is-canonical-and-not-a-public-field
  (let [a {:id (random-uuid) :name :leaf :args {:value {:value {:a 1 "a" false}}}}
        b (assoc a :description "edited prose" :args (array-map :value {:value {"a" false :a 1}}))
        signature (provenance/stamp {:args {} :return :text} a)]
    (is (= (provenance/fingerprint a) (provenance/fingerprint b)))
    (is (= (provenance/fingerprint a)
           (binding [*print-length* 1 *print-level* 1 *print-meta* true]
             (provenance/fingerprint a)))
        "a diagnostic printer cannot truncate the certificate input")
    (is (not= (provenance/fingerprint a)
              (provenance/fingerprint (assoc-in a [:args :value :value :a] nil))))
    (is (= {:args {} :return :text} signature) "normal JVM map semantics are unchanged")
    (is (provenance/matches? signature a))
    (is (= (json/generate-string {:args {} :return :text})
           (json/generate-string signature)))
    (is (not (str/includes? (pr-str signature) (provenance/fingerprint a))))))


(deftest common-recheck-frontier-retries-without-certifying-an-unchecked-source
  (let [{:keys [id] :as f} (fixture [] {})
        policy (snapshot/capture-policy (update (:rich f) :by-id dissoc id))]
    (doseq [recovery [{:status :pending :pending-ids nil}
                      {:status :running :pending-ids #{id}}]]
      (is (= :policy-refresh-required
             (:reason (failure #(export (assoc f :policy (assoc policy :type-recheck recovery))))))))
    (doseq [recovery [{:status :ready :pending-ids #{}}
                      {:status :failed :pending-ids #{}}
                      {:status :running :pending-ids #{(random-uuid)}}]]
      (is (= :type-source-mismatch
             (:reason (failure #(export (assoc f :policy (assoc policy :type-recheck recovery))))))))
    (is (map? (export f)) "ordinary completed certificates still govern export")))


(deftest preview-collects-only-requested-source-with-the-original-policy
  (let [{:keys [snapshot rich id] :as f} (fixture [] {})
        namespace-id (:id (first (:namespaces snapshot)))
        unrelated (mapv (fn [n]
                          {:id (random-uuid) :name (str "unrelated-" n)
                           :namespace-id namespace-id :parent-ids [(random-uuid)]})
                        (range 500))
        expanded (-> snapshot
                     (update-in [:graph :fns] into unrelated)
                     (update-in [:graph :bindings] into
                                (mapv (fn [row]
                                        {:id (random-uuid) :fn-id (:id row)
                                         :slot-id (random-uuid) :value "unrelated private literal"}) unrelated)))
        calls (atom [])
        captured (atom nil)
        capture snapshot/capture-context-policy
        export-snapshot snapshot/export-snapshot
        run (fn [snapshot rich entries]
              (let [graph (:graph snapshot)
                    storage (remote/from-bundle {:fn (:fns graph) :slot (:slots graph)
                                                 :fn-slot (:fn-slots graph) :binding (:bindings graph)
                                                 :binding-list-item (:list-items graph) :ns (:namespaces snapshot)})]
                (preview/export-current {:storage storage :rich-types-atom (atom rich) :base-fns {}} entries)))]
    (with-redefs [tenancy/tenancy-addon-active? (constantly false)
                  source/with-snapshot (fn [storage f] (swap! calls conj :database) (f storage (constantly nil)))
                  snapshot/read-snapshot (fn [_] (throw (AssertionError. "Preview read the whole graph")))
                  snapshot/capture-context-policy (fn [ctx] (swap! calls conj :policy) (capture ctx))
                  snapshot/export-snapshot (fn [snapshot & args] (reset! captured snapshot) (apply export-snapshot snapshot args))]
      (is (= (export-snapshot expanded {} {:view id} (policy-of f)) (run expanded rich {:view id})))
      (is (= [:policy :database] @calls) "original visibility is frozen before database collection")
      (is (< (count (get-in @captured [:graph :fns])) 10))
      (is (not-any? (set (map :id unrelated)) (map :id (get-in @captured [:graph :fns]))))
      (is (not (str/includes? (pr-str @captured) "unrelated private literal")))
      (is (= :visibility-denied
             (:reason (failure #(run expanded (assoc-in rich [:by-id id :return] [:secret :text]) {:view id})))))
      (is (= :type-source-mismatch
             (:reason (failure #(run (update-in expanded [:graph :bindings]
                                                (fn [rows]
                                                  (mapv (fn [row]
                                                          (cond-> row (= id (:fn-id row))
                                                                  (assoc :value "changed"))) rows)))
                                     rich {:view id})))))
      (is (= :source-missing (:reason (failure #(run expanded rich {:view (random-uuid)}))))))))


(deftest checker-storage-restores-only-exact-owned-generic-type-contracts
  (let [definition (first (filter #(= :call (:name %))
                                  (:fns (edn/read-string (slurp "resources/packages/core/system/fns.edn")))))
        row (first (filter #(and (= :fn (:kind %)) (contracts/contract (:id %)))
                           (record-types/inline-fn-type-rows-from-form (get-in definition [:args :func :type]))))
        row (dissoc row :kind)
        specs {:constraint {:type :jsonb}}
        stored (codec/decode-row (codec/encode-row row specs) specs)
        captured {:graph {:fns [stored] :slots [] :fn-slots [] :bindings [] :list-items []}
                  :namespaces []}
        reconstruct #(sp/read-entity (#'snapshot/snapshot-storage %) :fn (:id row))
        constraint (:constraint stored)]
    (is (not= (:constraint row) constraint) "Real JSONB codec stores ordinary symbol spellings")
    (with-redefs [owned/owned-fn-id? #(= (:id row) %)]
      (is (= (:constraint row) (:constraint (reconstruct captured))))
      (is (= constraint (get-in captured [:graph :fns 0 :constraint])) "Source evidence is not rewritten")
      (doseq [forged [(assoc-in captured [:graph :fns 0 :constraint 2] :text)
                      (assoc-in captured [:graph :fns 0 :constraint 1 :arg] :a)
                      (assoc-in captured [:graph :fns 0 :constraint] (conj constraint #{:io}))
                      (assoc-in captured [:graph :fns 0 :constraint] (conj constraint :any #{:jvm-server}))
                      (assoc-in captured [:graph :fns 0 :constraint] [:secret constraint])
                      (assoc-in captured [:graph :fns 0 :parent-ids] [(random-uuid)])
                      (assoc-in captured [:graph :fns 0 :expects-effects] ["io"])
                      (assoc-in captured [:graph :fns 0 :execution] #{:jvm-server})
                      (assoc-in captured [:graph :fns 0 :effects] #{:io})
                      (assoc-in captured [:graph :fns 0 :impl] :forged)
                      (assoc-in captured [:graph :fns 0 :marker-flags] {:hide-result? true})
                      (assoc-in captured [:graph :fns 0 :lambda-params] ["forged"])
                      (assoc-in captured [:graph :fns 0 :namespace-id] (random-uuid))
                      (assoc-in captured [:graph :fns 0 :deleted-at] "tombstone")
                      (assoc-in captured [:graph :fns 0 :kind] :marker)
                      (assoc-in captured [:graph :bindings] [{:fn-id (:id row) :value "forged"}])
                      (assoc-in captured [:graph :fn-slots] [{:id (random-uuid) :fn-id (:id row) :slot-id (random-uuid)}])]]
        (is (= :type-contract-source-mismatch (:reason (failure #(reconstruct forged))))))
      (doseq [private [(assoc-in captured [:graph :fns 0 :org-id] "foreign")
                       (assoc-in captured [:graph :fns 0 :concealed?] true)]]
        (is (= constraint (:constraint (reconstruct private))) "Private rows receive no restoration")))
    (with-redefs [owned/owned-fn-id? (constantly false)]
      (is (= constraint (:constraint (reconstruct captured))) "Unowned rows receive no restoration"))))
