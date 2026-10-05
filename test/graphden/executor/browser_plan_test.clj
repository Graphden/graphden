(ns graphden.executor.browser-plan-test
  "Export real fn-def records without a database or a second source language."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.browser-plan :as browser]
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.surface :as surface]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.runtime :as rt]
    [graphden.packages.loader :as loader]
    [graphden.packages.records :as records]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.postgres.codec :as codec]
    [graphden.test-infra.account-menu-cases :as account-menu]))


(use-fixtures :each
  (fn [run]
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})]
      (run))))


(def ^:private primitive-defs
  [{:name :const :namespace "core.logic" :args {:value {:type :any}} :return-type :any}
   {:name :if :namespace "core.logic"
    :args {:test {:type :any} :then {:type :any} :else {:type :any :required false}} :return-type :any}
   {:name :list :namespace "core.collections" :args {:items {:type [:list :any]}} :return-type [:list :any]}])


(defn- graph-of
  ([definitions] (graph-of primitive-defs definitions))
  ([primitives definitions]
   (let [defs (into (vec primitives) (map #(assoc % :namespace "preview")) definitions)
         names (into {} (map (fn [d] [(:name d) (ids/fn-id (:namespace d) (:name d))])) defs)
         by-name (into {} (map (juxt :name identity)) defs)
         rows (concat (ids/boot-primitive-records)
                      (mapcat #(records/parse-fn-def % names by-name) defs))
         tables {:fn :fns :slot :slots :fn-slot :fn-slots
                 :binding :bindings :binding-list-item :list-items}]
     {:ids names
      :graph (reduce (fn [g row]
                       (if-let [table (get tables (:kind row))]
                         (update g table conj (dissoc row :kind))
                         g))
                     {:fns [] :slots [] :fn-slots [] :bindings [] :list-items []}
                     rows)})))


(defn- export-fixture
  [{:keys [graph ids]} n]
  ;; Every definition in these small fixtures is supplied by this test.
  ;; Production must use the branch's fail-closed visibility classification.
  (browser/export-plan graph {} {:view (get ids n)} {:allow-fn? (constantly true)}))


(defn- function-entry
  [plan id]
  (first (filter #(= (str id) (:id %)) (:functions plan))))


(defn- failure
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))


(deftest codec-distinguishes-keywords-strings-and-user-carriers-test
  (let [value {:phase "open" "phase" nil :ui/active false
               :vector ["keyword" nil "phase"]
               :carrier {"_kw" "not-a-keyword"}
               :tags [:button :ui/item "button" 0 -3]}
        wire (browser/encode-value value)
        transported (json/parse-string-strict (json/generate-string wire))]
    (is (= value (browser/decode-value transported)))
    (is (= ["keyword" "ui" "phase"] (browser/encode-value :ui/phase)))
    (is (= ["string" ":phase"] (browser/encode-value ":phase")))))


(deftest codec-rejects-lossy-and-malformed-values-test
  (doseq [value [1.5 1/3 9007199254740992N #{1} '(1 2) {1 "number-key"}]]
    (is (= :browser-plan/unsupported (:type (failure #(browser/encode-value value))))))
  (doseq [wire [["nil" 1] ["bool" "false"] ["int" 1.0] ["keyword" nil ""]
                ["map" [[["string" "x"] ["nil"]] [["string" "x"] ["bool" false]]]]
                ["vector" {}] ["unknown" nil]]]
    (is (= :browser-plan/unsupported (:type (failure #(browser/decode-value wire)))))))


(deftest result-sequences-materialize-with-a-bound-test
  (is (= ["vector" [["int" 0] ["int" 1] ["int" 2]]]
         (browser/encode-result (range 3))))
  (is (= :value-limit (:reason (failure #(browser/encode-result (range)))))))


(deftest exported-literal-preserves-the-postgres-value-test
  (let [{:keys [graph] :as fixture}
        (graph-of [{:name :state :parent :const
                    :args {:value {:value {:phase "closed" :selected nil :active false}}}}])
        roundtrip (fn [v]
                    (:value (codec/decode-row {:value (codec/encode-value v {:type :jsonb})}
                                              {:value {:type :jsonb}})))
        stored (update graph :bindings
                       #(mapv (fn [b] (update b :value roundtrip)) %))
        plan (export-fixture (assoc fixture :graph stored) :state)
        expr (get-in (last (:functions plan)) [:args 0 :expr])]
    (is (= "literal" (:kind expr)))
    (is (= {:phase "closed" :selected nil :active false}
           (browser/decode-value (:value expr))))
    (is (= [{:id (str (ids/fn-id "core.logic" :const)) :op "const"}]
           (:primitives plan)))))


(deftest literal-nil-is-not-a-free-argument-test
  (let [fixture (graph-of [{:name :nil-value :parent :const :args {:value {:value nil}}}
                           {:name :free-value :parent :const :args {:value {:as :state}}}])
        nil-plan (export-fixture fixture :nil-value)
        free-plan (export-fixture fixture :free-value)]
    (is (= {:kind "literal" :value ["nil"]}
           (get-in (last (:functions nil-plan)) [:args 0 :expr])))
    (is (= "read" (get-in (last (:functions free-plan)) [:args 0 :expr :kind])))
    (is (= ["state"] (get-in free-plan [:inputs (str (get-in fixture [:ids :free-value])) :accepted])))
    ;; A rename-view slot is optional in the existing public compiler surface.
    (is (= [] (get-in free-plan [:inputs (str (get-in fixture [:ids :free-value])) :required])))))


(deftest renamed-reader-and-public-argument-targets-use-slot-identities-test
  (let [{:keys [ids] :as fixture}
        (graph-of [{:name :reader :parent :const :args {:value {:as :state}}}
                   {:name :renamed :parent :reader :args {:state {:as :model}}}])
        plan (export-fixture fixture :renamed)
        f (function-entry plan (:renamed ids))
        reader (get-in f [:args 0 :expr])
        inputs (get-in plan [:inputs (str (:renamed ids))])]
    (is (= "model" (:name reader)))
    (is (not= (str (ids/slot-id (:const ids) :value)) (:slot reader)))
    (is (some #(and (= "model" (:name %)) (seq (:slots %))) (:destinations inputs)))
    (is (some #{"model"} (:accepted inputs)))))


(deftest inherited-sequence-positions-and-ref-items-survive-export-test
  (let [{:keys [ids] :as fixture}
        (graph-of [{:name :leaf :parent :const :args {:value 7}}
                   {:name :parent :parent :list :args {:items [2 0 1]}}
                   {:name :child :parent :parent :args {:items [3 :leaf]}}])
        plan (export-fixture fixture :child)
        expressions (get-in (function-entry plan (:child ids)) [:args 0 :expr :items])]
    (is (= [2 0 1 3] (mapv (comp browser/decode-value :value) (take 4 expressions))))
    (is (= {:kind "call" :fn (str (:leaf ids)) :renames []}
           (dissoc (last expressions) :item)))
    (is (= 1 (count (filter #(= (str (:leaf ids)) (:id %)) (:functions plan)))))))


(deftest positional-read-belongs-to-the-binding-owner-test
  (let [{:keys [ids] :as fixture}
        (graph-of [{:name :parent :parent :list :args {:items [{:as :state} 9]}}
                   {:name :child :parent :parent}])
        plan (export-fixture fixture :child)
        expr (get-in (function-entry plan (:child ids)) [:args 0 :expr :items 0])]
    (is (= "read" (:kind expr)))
    (is (= "state" (:name expr)))
    (is (= (str (ids/slot-id (:parent ids) :state)) (:slot expr)))))


(deftest deep-binding-is-exported-as-an-environment-entry-test
  (let [{:keys [graph ids] :as fixture}
        (graph-of [{:name :reader :parent :const :args {:value {:as :state}}}
                   {:name :outer :parent :const :args {:value :reader
                                                       :state {:value {:phase "open"}}}}])
        plan (export-fixture fixture :outer)
        f (function-entry plan (:outer ids))
        entry (first (filter #(= "state" (:name %)) (:env f)))
        impl (fn [args _ctx] (rt/resolve-arg args :value))
        lookup (assoc (lookups/build-lookups graph) :base-fns {:const impl})
        reader (eager/compile-fn (:reader ids) lookup)
        outer (eager/compile-fn (:outer ids) lookup {(:reader ids) reader})]
    (is (= {:phase "open"} (outer {} {})))
    (is (= {:phase "open"} (browser/decode-value (get-in entry [:expr :value]))))
    (is (= "call" (get-in f [:args 0 :expr :kind])))
    (is (= [] (get-in plan [:inputs (str (:outer ids)) :required])))))


(deftest cyclic-reference-is-rejected-with-a-path-test
  (let [{:keys [graph ids] :as fixture}
        (graph-of [{:name :left :parent :const :args {:value :right}}
                   {:name :right :parent :const :args {:value :left}}])
        data (failure #(export-fixture (assoc fixture :graph graph) :left))]
    (is (= :cycle (:reason data)))
    (is (= [(:left ids) (:right ids) (:left ids)] (:path data)))))


(deftest unsupported-reachable-function-fails-even-in-an-unselected-branch-test
  (let [{:keys [graph ids]} (graph-of [{:name :unsupported :args {} :return-type :any}
                                       {:name :choice :parent :if
                                        :args {:test true :then 1 :else :unsupported}}])
        data (failure #(browser/export-plan graph {} {:view (:choice ids)}
                                            {:allow-fn? (constantly true)}))]
    (is (= :unsupported-primitive (:reason data)))
    (is (= (:unsupported ids) (:fn-id data)))
    (is (= [(:choice ids) (:unsupported ids)] (:path data)))
    (is (not (contains? data :graph)))))


(deftest visibility-policy-is-required-and-applies-to-ancestors-test
  (let [{:keys [graph ids] :as fixture} (graph-of [{:name :state :parent :const :args {:value 1}}])]
    (is (= :missing-visibility-policy
           (:reason (failure #(browser/export-plan graph {} {:view (:state ids)})))))
    (is (= :visibility-denied
           (:reason (failure #(browser/export-plan graph {} {:view (:state ids)}
                                                   {:allow-fn? (fn [fid] (not= fid (:const ids)))})))))
    (testing "concealed composition is rejected even with an allowing policy"
      (let [hidden (update graph :fns
                           #(mapv (fn [f] (cond-> f (= (:id f) (:const ids)) (assoc :concealed? true))) %))]
        (is (= :concealed-function
               (:reason (failure #(export-fixture (assoc fixture :graph hidden) :state)))))))))


(deftest registry-policy-rejects-secret-and-unknown-types-test
  (let [{:keys [graph ids]} (graph-of [{:name :state :parent :const :args {:value "do-not-export"}}])
        policy #(= :plain (registry/trace-capture-class % nil))
        export #(browser/export-plan graph {} {:view (:state ids)} {:allow-fn? policy})]
    (registry/record-rich-types-raw! (:const ids) :const {:return :any :args {:value :any}})
    (is (= :visibility-denied (:reason (failure export))))
    (registry/record-rich-types-raw! (:state ids) :state {:return [:secret :text] :args {}})
    (let [data (failure export)]
      (is (= :visibility-denied (:reason data)))
      (is (not (str/includes? (pr-str data) "do-not-export"))))
    (registry/record-rich-types-raw! (:state ids) :state {:return :text :args {}})
    (is (= (str (:state ids)) (get-in (export) [:entries "view"])))))


(deftest jvm-oracle-shares-the-exported-public-rename-boundary-test
  (let [{:keys [graph ids] :as fixture}
        (graph-of [{:name :reader :parent :const :args {:value {:as :state}}}
                   {:name :renamed :parent :reader :args {:state {:as :model}}}])
        impl (fn [args _ctx] (rt/resolve-arg args :value))
        lookup (assoc (lookups/build-lookups graph) :base-fns {:const impl})
        f (eager/compile-fn (:renamed ids) lookup)
        plan (export-fixture fixture :renamed)
        input (get-in plan [:inputs (str (:renamed ids))])
        translated (runtime/translate-named-args (:renamed ids) {:model {:phase "open"}} lookup)]
    (is (= {:phase "open"} (f translated {})))
    (let [destinations (first (filter #(= "model" (:name %)) (:destinations input)))]
      (is (= #{:model} (set (filter keyword? (keys translated)))))
      (is (= (set (:slots destinations))
             (set (map str (filter uuid? (keys translated)))))))))


(def ^:private differential-definitions
  [{:name :leaf :parent :const :args {:value 7}}
   {:name :seq-parent :parent :list :args {:items [2 0 1]}}
   {:name :seq-child :parent :seq-parent :args {:items [3 :leaf]}}
   {:name :positional :parent :list :args {:items [{:as :state} 9]}}
   {:name :positional-child :parent :positional}
   {:name :positional-rename :parent :positional-child :args {:state {:as :model}}}
   {:name :read-x :parent :const :args {:value {:as :x}}}
   {:name :scalar-rename :parent :read-x :args {:x {:as :model}}}
   {:name :read-y :parent :const :args {:value {:as :y}}}
   {:name :pair :parent :list :args {:items [:read-x :read-y]}}
   {:name :forward :parent :pair :args {:x :read-y :y 12}}
   {:name :self-mask :parent :pair :args {:x :read-x :y 12}}
   {:name :boom :parent :mod :args {:dividend 1 :divisor 0}}
   {:name :choice :parent :if :args {:test {:as :condition} :then "then" :else :boom}}
   {:name :lookup :parent :get
    :args {:coll {:as :state} :key {:as :key} :default "fallback"}}
   {:name :update :parent :assoc
    :args {:map {:as :state} :key {:value :selected} :value {:as :selection}}}
   {:name :equal-seq :parent :equal?
    :args {:a :seq-parent :b {:value [2 0 1]}}}
   {:name :lazy-list :parent :list :args {:items [1 :boom]}}
   {:name :get-seq :parent :get :args {:coll :lazy-list :key 0 :default "fallback"}}
   {:name :count-seq :parent :count :args {:coll :lazy-list}}
   {:name :sum :parent :add :args {:nums [-3 4 5]}}
   {:name :negative-mod :parent :mod :args {:dividend 8 :divisor -3}}
   {:name :zip :parent :zipmap :args {:keys {:value [:phase "phase"]} :vals [nil false]}}])


(def ^:private differential-cases
  [{:entry :seq-child :inputs {} :expected [2 0 1 3 7]}
   {:entry :positional-child :inputs {:state false} :expected [false 9]}
   {:entry :positional-child :inputs {:state nil} :expected [nil 9]}
   ;; The existing named-argument boundary rejects this positional rename;
   ;; exporting a broader surface here would invent different semantics.
   {:entry :positional-rename :inputs {:model "open"} :error true}
   {:entry :scalar-rename :inputs {:model "open"} :expected "open"}
   {:entry :forward :inputs {} :expected [12 12]}
   {:entry :self-mask :inputs {} :expected [nil 12]}
   {:entry :choice :inputs {:condition 0} :expected "then"}
   {:entry :choice :inputs {:condition ""} :expected "then"}
   {:entry :choice :inputs {:condition false} :error true}
   {:entry :choice :inputs {:condition nil} :error true}
   {:entry :lookup :inputs {:state {:phase nil} :key :phase} :expected nil}
   {:entry :lookup :inputs {:state {:phase false} :key :phase} :expected false}
   {:entry :lookup :inputs {:state {} :key :phase} :expected "fallback"}
   {:entry :lookup :inputs {:state {:phase "keyword" "phase" "string"} :key :phase} :expected "keyword"}
   {:entry :lookup :inputs {:state {:phase "keyword" "phase" "string"} :key "phase"} :expected "string"}
   {:entry :update :inputs {:state {:selected 1 "selected" 2} :selection nil}
    :expected {:selected nil "selected" 2}}
   {:entry :equal-seq :inputs {} :expected true}
   {:entry :get-seq :inputs {} :expected "fallback"}
   {:entry :count-seq :inputs {} :error true}
   {:entry :sum :inputs {} :expected 6}
   {:entry :negative-mod :inputs {} :expected -1}
   {:entry :zip :inputs {} :expected {:phase nil "phase" false}}])


(defn- jvm-results
  [graph base-fns ids cases]
  (let [lookup (assoc (lookups/build-lookups graph) :base-fns base-fns)
        compiled (eager/compile-subset lookup {} (map :id (:fns graph)))]
    (mapv (fn [{:keys [entry inputs]}]
            (try
              ;; Same shared accepted surface that execute-with-named-args
              ;; validates before invoking translate-named-args + compiled fn.
              (when (some #(not (contains? (:accepted (surface/surface-names (get ids entry) lookup)) %))
                          (keys inputs))
                (throw (ex-info "Unknown argument" {})))
              {:value (browser/encode-result
                        ((get compiled (get ids entry))
                         (runtime/translate-named-args (get ids entry) inputs lookup) {}))}
              (catch Exception _ {:error true})))
          cases)))


(defn- assert-differential!
  [graph impls ids cases]
  (let [entries (select-keys ids (map :entry cases))
        plan (browser/export-plan graph impls entries {:allow-fn? (constantly true)})
        request {:plan plan
                 :cases (mapv (fn [{:keys [entry inputs]}]
                                {:entry (name entry)
                                 :inputs (into {} (map (fn [[k v]] [(name k) (browser/encode-value v)])) inputs)})
                              cases)}
        {:keys [exit out err]} (sh/sh "node" "tools/runtime-test/browser-plan-runner.js"
                                      :in (json/generate-string request))
        jvm (jvm-results graph impls ids cases)]
    (is (zero? exit) err)
    (when (zero? exit)
      (let [browser (json/parse-string-strict out true)]
        (is (= (count cases) (count browser)))
        (doseq [[test-case jvm-result browser-result] (map vector cases jvm browser)]
          (testing (str (:entry test-case) " " (:inputs test-case))
            (if (:error test-case)
              (do (is (:error jvm-result)) (is (string? (:error browser-result))))
              (do
                (is (nil? (:error jvm-result)))
                (is (nil? (:error browser-result)) (:error browser-result))
                (when (and (:value jvm-result) (:value browser-result))
                  (is (= (:expected test-case)
                         (browser/decode-value (:value jvm-result))
                         (browser/decode-value (:value browser-result)))))))))))))


(deftest browser-runtime-matches-jvm-for-stored-compositions-test
  (let [loaded (loader/load-packages ["core"])
        base-defs (select-keys (:base-fn-defs loaded) [:const :if :list :mod :get :assoc :equal? :count :zipmap :add])
        type-defs (remove #(or (:parent %) (:parents %)) (:fn-defs loaded))
        primitive-rows (into (vec type-defs) (map (fn [[n d]] (assoc d :name n))) base-defs)
        {:keys [graph ids]} (graph-of primitive-rows differential-definitions)
        impls (into {} (map (fn [[n d]] [n (:impl d)])) base-defs)]
    (assert-differential! graph impls ids differential-cases)))


(deftest real-account-menu-matches-jvm-and-json-input-boundary
  (let [loaded (loader/load-packages ["web"])
        base-defs (select-keys (:base-fn-defs loaded)
                               [:const :if :list :mod :get :assoc :equal? :count :zipmap :add :hiccup])
        type-defs (remove #(or (:parent %) (:parents %)) (:fn-defs loaded))
        primitive-rows (into (vec type-defs) (map (fn [[n d]] (assoc d :name n))) base-defs)
        definitions (:fns (edn/read-string (slurp "resources/packages/app/ui-account-menu/fns.edn")))
        impls (into {} (map (fn [[n d]] [n (:impl d)])) base-defs)
        cases (account-menu/cases)]
    (testing "real common rows, dynamic native item count, all phase/keyboard transitions"
      (let [{:keys [graph ids]} (graph-of primitive-rows definitions)]
        (assert-differential! graph impls ids cases)))
    (doseq [local? [false true]]
      (testing (if local? "local reference is explicitly replaced" "shared value reaches both real consumers")
        (let [edited (mapv (fn [definition]
                             (cond-> definition
                               (= :theme-canvas-background (:name definition))
                               (assoc-in [:args :value] "#224466")
                               (and local? (= :account-menu-hover (:name definition)))
                               (assoc-in [:args :value] "#6688aa"))) definitions)
              {:keys [graph ids]} (graph-of primitive-rows edited)
              view-case (first (filter #(= :account-menu-view (:entry %)) cases))
              expected (-> account-menu/view
                           (assoc-in [:theme-tokens "--bg"] "#224466")
                           (assoc-in [:menu-tokens "--gd-account-menu-hover"]
                                     (if local? "#6688aa" "#224466")))]
          (assert-differential! graph impls ids [(assoc view-case :expected expected)]))))))


(deftest policy-exception-cannot-expose-snapshot-or-cause-test
  (let [{:keys [graph ids]} (graph-of [{:name :state :parent :const :args {:value "private-value"}}])
        thrown (try
                 (browser/export-plan graph {} {:view (:state ids)}
                                      {:allow-fn? (fn [_]
                                                    (throw (ex-info "private-value" {:graph graph})))})
                 nil
                 (catch Exception e e))]
    (is (= :normalization-failed (:reason (ex-data thrown))))
    (is (= (:state ids) (:fn-id (ex-data thrown))))
    (is (nil? (ex-cause thrown)))
    (is (not (str/includes? (str thrown (ex-data thrown)) "private-value")))))
