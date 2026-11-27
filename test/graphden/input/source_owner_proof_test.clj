(ns ^:serial graphden.input.source-owner-proof-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.registry.core :as registry]
    [graphden.input.source :as source]
    [graphden.input.source-certification :as certification]
    [graphden.input.source-joint :as joint]
    [graphden.input.source-scope :as scope]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(use-fixtures :each
  (fn [run]
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})]
      (run))))


(defn- declarations
  [module names]
  (let [{ns-id :namespace :keys [fns]} (edn/read-string (slurp (io/resource (str "packages/core/" module "/fns.edn"))))]
    (mapv #(assoc % :namespace ns-id) (filter #(contains? names (:name %)) fns))))


(def ^:private implementations (delay (:base-fn-defs (loader/load-packages ["core"]))))


(defn- fixture
  []
  (let [base (into (declarations "strings" #{:_csv-escape-cell})
                   (concat (declarations "strings" #{:str-trim})
                           (declarations "system" #{:to-json-string})))
        a {:namespace "owner-proof" :name :a :parent :_csv-escape-cell
           :args {:value :to-json-string :data {:type :jsonb}}}
        b {:namespace "owner-proof" :name :b :parent :a
           :args {:data {:value nil} :extra {:type :text}}}
        c {:namespace "owner-proof" :name :c :parent :b
           :args {:extra :str-trim :string " null "}}
        originals (into base [a b c])
        prepared (parse/prepare-module originals {} {})]
    (doseq [{ns-id :namespace :keys [name]} base]
      (registry/record-rich-types! (ids/fn-id ns-id name) name (get @implementations name)))
    {:package (snapshot/package-index prepared originals) :originals originals
     :roots (:by-id @registry/*rich-types-override*) :owner (ids/fn-id "owner-proof" :c)}))


(defn- failure-code
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (or (:code (ex-data error)) (:type (ex-data error))))))


(deftest three-owner-proof-transaction-replans-inherited-and-scoped-inputs
  (let [{:keys [package originals roots owner]} (fixture)
        initial (source/plan-module originals (:prepared package))
        result (snapshot/with-certified-owner package owner roots)
        c (get-in result [:closure-baselines owner])
        all (certification/verify-plan (:prepared package) originals {} roots)]
    (is (= [[:field-projection-required] [:inherited-projection-required]
            [:ref-scope-required :inherited-projection-required]]
           (mapv #(mapv :code (:blockers %)) (take-last 3 (:definitions initial)))))
    (is (= :authored-source-equivalent (:verification c)) (:blockers c))
    (is (= [:value " null "] (get-in c [:proposed :bind [:extra :string]])))
    (is (= [:value nil] (get-in result [:closure-baselines (ids/fn-id "owner-proof" :b) :proposed :bind :data])))
    (is (every? #(= :authored-source-equivalent (:verification %)) (get-in all [:plan :definitions])))
    (is (not (contains? package :closure-baselines)))
    (let [reordered (parse/prepare-module (vec (reverse originals)) {} {})
          other (snapshot/with-certified-owner (snapshot/package-index reordered (reverse originals)) owner roots)]
      (is (= (select-keys (:v2 c) [:fn-id :input-type-id :declaration-records :bindings])
             (select-keys (get-in other [:closure-baselines owner :v2])
                          [:fn-id :input-type-id :declaration-records :bindings]))))))


(deftest inherited-occupancy-is-not-cleared-by-a-successful-parent-proof
  (let [{:keys [originals roots owner]} (fixture)
        changed (update originals (dec (count originals)) assoc-in [:args :data] {:value false})
        package (snapshot/package-index (parse/prepare-module changed {} {}) changed)]
    (is (= :unsupported-owner-certificate
           (failure-code #(snapshot/with-certified-owner package owner roots))))
    (is (not (contains? package :closure-baselines)))))


(deftest failed-leaf-check-does-not-publish-successful-ancestor-certificates
  (let [{:keys [originals roots owner]} (fixture)
        changed (update originals (dec (count originals)) assoc-in [:args :string] 42)
        prepared (parse/prepare-module changed {} {})
        package (snapshot/package-index prepared changed)
        before (source/plan-module changed (:prepared package))]
    (is (= :types/check-failed
           (failure-code #(snapshot/with-certified-owner package owner roots))))
    (is (not (contains? package :closure-baselines)))
    (is (= before (source/plan-module changed (:prepared package))))))


(deftest inline-scoped-binding-keeps-the-child-owner-and-complete-proof
  (let [{:keys [originals roots]} (fixture)
        child (dissoc (peek originals) :namespace :name)
        owner (ids/fn-id "owner-proof" :host)
        host {:namespace "owner-proof" :name :host :parent :_csv-escape-cell :args {:value child}}
        certify (fn [definition]
                  (let [definitions (conj (pop originals) definition)
                        prepared (parse/prepare-module definitions {} {})]
                    (certification/verify-plan prepared definitions {} roots)))
        result (certify host)
        entry (get-in result [:package :closure-baselines owner])
        body (get-in entry [:proposed :bind :value 1])
        [inline] (vals (get-in entry [:v2 :inline-definitions]))
        invalid (assoc-in host [:args :value :args :string] 42)
        invalid-definitions (conj (pop originals) invalid)
        invalid-package (assoc (snapshot/package-index (parse/prepare-module invalid-definitions {} {}) invalid-definitions)
                               :closure-baselines {})]
    (is (= :authored-source-equivalent (:verification entry))
        (pr-str (:blocked result)))
    (is (= {[:extra :string] :string} (:legacy-scope-names body)))
    (is (= [:value " null "] (get-in body [:bind [:extra :string]])))
    (is (= (get-in inline [:scoped-inputs [:extra :string] :row])
           (get-in result [:package :closure-baselines (:fn-id inline) :v2 :scoped-inputs [:extra :string] :row])))
    (is (= :types/check-failed (failure-code #(snapshot/with-certified-owner invalid-package owner roots))))
    (is (empty? (:closure-baselines invalid-package)))))


(deftest inline-list-item-can-certify-its-own-scoped-binding
  (let [{:keys [originals roots]} (fixture)
        [vec-base] (declarations "collections" #{:vec})
        child (dissoc (peek originals) :namespace :name)
        later-child (assoc-in child [:args :string] " last ")
        host {:namespace "owner-proof" :name :host :parent :vec
              :args {:coll [{:value nil} child later-child]}}
        owner (ids/fn-id "owner-proof" :host)
        slot (ids/slot-id (ids/fn-id "core.collections" :vec) :coll)
        verify (fn [owner-form]
                 (let [forms (conj (pop originals) vec-base owner-form)
                       prepared (parse/prepare-module forms {} {})]
                   (certification/verify-plan prepared forms {} roots)))
        {:keys [package blocked]} (verify host)
        entry (get-in package [:closure-baselines owner])
        inline (get-in entry [:v2 :inline-definitions [slot 1]])
        scoped-row (get-in inline [:scoped-inputs [:extra :string] :row])
        later (get-in entry [:v2 :inline-definitions [slot 2]])
        invalid (verify (assoc-in host [:args :coll 1 :args :string] 42))]
    (is (= :authored-source-equivalent (:verification entry)) (pr-str (get blocked owner)))
    (is (= (ids/fn-id "owner-proof" (:name (parse/inline-declaration child [:host :coll 1] "owner-proof")))
           (:fn-id inline)))
    (is (uuid? (:id scoped-row)))
    (is (= (ids/fn-id "owner-proof" (:name (parse/inline-declaration later-child [:host :coll 2] "owner-proof")))
           (:fn-id later)))
    (is (uuid? (get-in later [:scoped-inputs [:extra :string] :row :id])))
    (is (= scoped-row
           (get-in package [:closure-baselines (:fn-id inline) :v2 :scoped-inputs [:extra :string] :row])))
    (is (= :types/check-failed (get-in invalid [:blocked owner :code])))
    (is (nil? (get-in invalid [:package :closure-baselines owner])))))


(deftest joint-proof-completion-is-independent-of-owner-uuid-queue-order
  (let [{:keys [originals roots]} (fixture)
        changed (update originals (dec (count originals)) assoc :name :early)
        prepared (parse/prepare-module changed {} {})
        owner (ids/fn-id "owner-proof" :early)
        parent (ids/fn-id "owner-proof" :a)
        result (certification/verify-plan prepared changed {} roots)]
    (is (neg? (compare owner parent)))
    (is (empty? (:blocked result)))
    (is (= #{owner parent (ids/fn-id "owner-proof" :b)} (:certified result)))
    (is (every? #(= :authored-source-equivalent (:verification %)) (get-in result [:plan :definitions])))))


(defn- joint-fixture
  [field-type scoped-value]
  (let [{:keys [originals roots]} (fixture)
        parent {:namespace "owner-proof" :name :with-extra :parent :_csv-escape-cell
                :args {:extra {:type :text :required false}}}
        owner {:namespace "owner-proof" :name :joint :parent :with-extra
               :args {:value :to-json-string :extra :str-trim
                      :data {:type field-type} :string scoped-value}}
        originals (into (vec (take 3 originals)) [parent owner])
        prepared (parse/prepare-module originals {} {})]
    {:package (snapshot/package-index prepared originals) :originals originals :roots roots
     :owner (ids/fn-id "owner-proof" :joint)}))


(deftest one-owner-joins-forwarding-and-scoped-values-without-changing-declarations
  (let [{:keys [package originals roots owner]} (joint-fixture :jsonb "unread")
        original (last (:definitions (source/plan-module originals (:prepared package))))
        evidence (atom nil)
        verify scope/verify-bindings-after-forwarding
        result (with-redefs [scope/verify-bindings-after-forwarding
                             (fn [old projected ctx analyze]
                               (reset! evidence [old projected ctx])
                               (verify old projected ctx analyze))]
                 (snapshot/with-certified-owner package owner roots))
        entry (get-in result [:closure-baselines owner])
        field (ids/slot-id owner :data)
        old (some #(when (= field (:id %)) %) (get-in original [:legacy :records]))
        current (some #(when (= field (:id %)) %) (get-in entry [:v2 :declaration-records]))]
    (is (= {:fields [:data] :paths [[:args :string]]} (joint/groups original)))
    (is (= :authored-source-equivalent (:verification entry)))
    (is (= [:project [:data]] (get-in entry [:proposed :bind [:value :data]])))
    (is (= [:value "unread"] (get-in entry [:proposed :bind [:extra :string]])))
    (is (= (dissoc old :source-slot-id) current))
    (doseq [row (filter #(= :fn-slot (:kind %)) (get-in original [:legacy :records]))]
      (is (some #{row} (get-in entry [:v2 :declaration-records]))))
    (let [[_ projected] @evidence
          projection (get-in projected [:v2 :scoped-inputs [:value :data] :row :id])]
      (is (uuid? projection))
      (doseq [edited [(assoc-in @evidence [0 :source :args :data :required] false)
                      (update-in @evidence [1 :legacy :records] conj {:kind :slot :id (random-uuid)})
                      (assoc-in @evidence [2 :bindings projection :projection-path] [(random-uuid)])]]
        (is (= :joint-source-mismatch (failure-code #(apply joint/scope-view edited))))))
    (is (not (contains? package :closure-baselines)))))


(deftest either-joint-phase-failure-publishes-no-owner-or-parent-proof
  (doseq [[type value expected-steps expected-code]
          [[:any "unread" [] :unproved-forwarded-target]
           [:jsonb 42 [:projection :scope] :types/check-failed]]]
    (let [{:keys [package originals roots owner]} (joint-fixture type value)
          before (source/plan-module originals (:prepared package))
          calls (atom [])
          forward scope/verify-forwarded-projections
          scoped scope/verify-bindings-after-forwarding
          parents source/with-forwarded-parent
          publications (atom 0)]
      (with-redefs [scope/verify-forwarded-projections
                    (fn [& args] (swap! calls conj :projection) (apply forward args))
                    scope/verify-bindings-after-forwarding
                    (fn [& args] (swap! calls conj :scope) (apply scoped args))
                    source/with-forwarded-parent
                    (fn [& args] (swap! publications inc) (apply parents args))]
        (is (= expected-code (failure-code #(snapshot/with-certified-owner package owner roots))))
        (is (= expected-steps @calls))
        (is (zero? @publications)))
      (is (not (contains? package :closure-baselines)))
      (is (= before (source/plan-module originals (:prepared package)))))))


(deftest joint-source-replay-preserves-both-checkable-reader-addresses
  (let [base (into (declarations "logic" #{:if :zero?})
                   (declarations "strings" #{:str-trim}))
        aliases (declarations "refinements" #{:positive-int :non-negative-int})
        left {:namespace "owner-proof" :name :left :parent :str-trim}
        right {:namespace "owner-proof" :name :right :parent :str-trim}
        host {:namespace "owner-proof" :name :host :parent :if
              :args {:test :zero? :then :left :else :right
                     :string {:type :text} :number {:value 4}}}
        definitions (into (into base aliases) [left right host])
        prepared (parse/prepare-module definitions {} {})
        owner (ids/fn-id "owner-proof" :host)]
    (doseq [{ns-id :namespace :keys [name]} base]
      (registry/record-rich-types! (ids/fn-id ns-id name) name (get @implementations name)))
    (let [roots (:by-id @registry/*rich-types-override*)
          result (certification/verify-plan prepared definitions {} roots)
          migrated (some #(when (= owner (:identity %)) %) (get-in result [:plan :definitions]))]
      (is (= :authored-source-equivalent (:verification migrated)) (get-in result [:blocked owner]))
      (is (= {[:then :string] [:project [:string]]
              [:else :string] [:project [:string]]
              [:test :number] [:value 4]}
             (select-keys (get-in migrated [:proposed :bind])
                          [[:then :string] [:else :string] [:test :number]]))))))
