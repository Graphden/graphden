(ns graphden.executor.browser-snapshot-test
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.type-check :as type-check]
    [graphden.executor.browser-plan :as plan]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records :as records]
    [graphden.storage.remote.core :as remote]
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
  [extra leaf]
  (let [definitions (into [constant] (map #(assoc % :namespace "snapshot"))
                          (conj (vec extra) (merge {:name :leaf :parent :const
                                                    :args {:value {:value "public"}}} leaf)))
        names (into {} (map (fn [d] [(:name d) (records/fn-id (:namespace d) (:name d))])) definitions)
        by-name (into {} (map (juxt :name identity)) definitions)
        namespaces {"core.logic" (random-uuid) "snapshot" (random-uuid)}
        rows (concat (records/boot-primitive-records)
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
      (registry/record-rich-types! (:const names) :const constant)
      (check/check-fn-def! (type-check/reconstruct-fn-def storage (:leaf names)))
      (reset! captured-types {:aliases (types/aliases-snapshot) :markers (shapes/markers-snapshot)}))
    (merge {:snapshot captured :rich @rich :id (:leaf names)} @captured-types)))


(defn- policy-of
  [{:keys [rich aliases markers]}]
  (binding [types/*type-aliases-override* (atom aliases)
            shapes/*marker-registry-override* (atom markers)]
    (snapshot/capture-policy rich)))


(defn- export
  [{:keys [snapshot id policy] :as f}]
  (snapshot/export-snapshot snapshot {} {:view id} (or policy (policy-of f))))


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
          (is (= :secret-output (get-in policy [:classes id])))
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
