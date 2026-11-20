(ns graphden.input.source-snapshot-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.registry.core :as registry]
    [graphden.input.analysis :as analysis]
    [graphden.input.list-builder :as list-builder]
    [graphden.input.list-execution :as list-execution]
    [graphden.input.list-signature :as list-signature]
    [graphden.input.source :as source]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.types.core.shapes :as shapes]))


(defn- read-package-edn
  [path]
  (edn/read-string (slurp (io/resource (str "packages/core/" path)))))


(defn- sources
  [module]
  (let [{module-ns :namespace :keys [fns]} (read-package-edn (str module "/fns.edn"))]
    (mapv #(assoc % :namespace module-ns) fns)))


(def ^:private strings
  (filterv #(contains? #{:str-upper :_csv-escape-cell} (:name %)) (sources "strings")))


(def ^:private loaded-bases (delay (:base-fn-defs (loader/load-packages ["core"]))))


(def ^:private roots
  (delay
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})]
      (let [loaded @loaded-bases]
        (doseq [{ns-id :namespace :keys [name]} (concat strings (filter #(= :equal? (:name %)) (sources "logic"))
                                                        (filter #(= :get (:name %)) (sources "collections"))
                                                        (filter #(#{:str :str-trim :re-find?} (:name %)) (sources "strings"))
                                                        (filter #(= :shipped-asset (:name %)) (sources "system"))
                                                        (filter #(contains? #{:deref :cell :sleep} (:name %)) (sources "concurrency")))]
          (registry/record-rich-types! (ids/fn-id ns-id name) name (get loaded name)))
        (:by-id @registry/*rich-types-override*)))))


(def ^:private host-id (ids/fn-id "fixture" :format-cell))


(defn- fixture
  [value]
  (let [dep (parse/prepare-module strings {} {})
        host {:namespace "fixture" :name :format-cell :parent :core.strings/_csv-escape-cell
              :args {:value :core.strings/str-upper :string value}}
        prepared (parse/prepare-module [host] (:name->id dep) (:defs-by-name dep))]
    (snapshot/package-index prepared (conj strings host))))


(defn- failure
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(deftest unique-direct-reference-preserves-package-field-and-binding-test
  (let [package (fixture "checked")
        certificate (snapshot/verify-binding package host-id @roots)
        field (ids/slot-id (ids/fn-id "core.strings" :str-upper) :string)
        edge (ids/binding-id host-id (ids/slot-id (ids/fn-id "core.strings" :_csv-escape-cell) :value))]
    (is (= [:value :string] (:public-path certificate)))
    (is (= [[host-id [edge]] [field]] (:address certificate)))
    (is (= (ids/binding-id host-id field) (get-in certificate [:row :id])))
    (is (= "checked" (get-in certificate [:row :value])))))


(deftest certified-reference-input-is-written-and-read-by-its-public-path
  (let [package (fixture "checked")
        entry (snapshot/migrate-binding package host-id @roots)
        field (ids/slot-id (ids/fn-id "core.strings" :str-upper) :string)
        link (ids/binding-id host-id (ids/slot-id (ids/fn-id "core.strings" :_csv-escape-cell) :value))
        path [:value :string]
        row (some #(when (= [link] (:scope-path %)) %) (get-in entry [:v2 :bindings]))
        reread (fn [change] (first (:definitions (source/verify-module {:definitions [(change entry)]} (:prepared package)))))]
    (is (= :authored-source-equivalent (:verification entry)) (pr-str (:blockers entry)))
    (is (= [:value "checked"] (get-in entry [:proposed :bind path])))
    (is (= (ids/binding-id host-id field) (:id row)))
    (is (= [field] (:target-path row)))
    (is (= :blocked (:status (reread #(assoc-in % [:proposed :bind path] [:value nil])))))
    (is (= :blocked (:status (reread #(assoc-in % [:proposed :bind :value] [:value "other"])))))))


(deftest scoped-path-crosses-two-references-and-an-inherited-declaration
  (let [wrapper {:namespace "fixture" :name :upper :parent :str-upper}
        inner {:namespace "fixture" :name :inner :parent :_csv-escape-cell :args {:value :upper}}
        outer {:namespace "fixture" :name :outer :parent :_csv-escape-cell :args {:value :inner}}
        host {:namespace "fixture" :name :host :parent :outer :args {:string "  checked "}}
        ds (into strings [wrapper inner outer host])
        pkg (snapshot/package-index (parse/prepare-module ds {} {}) ds)
        owner (ids/fn-id "fixture" :host)
        slot (ids/slot-id (ids/fn-id "core.strings" :str-upper) :string)
        source-slot (ids/slot-id (ids/fn-id "core.strings" :_csv-escape-cell) :value)
        uses (mapv #(ids/binding-id (ids/fn-id "fixture" %) source-slot) [:outer :inner])
        rewritten (snapshot/migrate-binding pkg owner @roots)
        [row] (get-in rewritten [:v2 :bindings])
        path [:value :value :string]]
    (is (= :authored-source-equivalent (:verification rewritten)) (pr-str (:blockers rewritten)))
    (is (= [:value "  checked "] (get-in rewritten [:proposed :bind path])))
    (is (= uses (:scope-path row)))
    (let [stale (assoc-in rewritten [:legacy :arguments 0 :scope-certificate :edges 1 :from] [[owner []] [source-slot]])]
      (is (= :blocked (get-in (source/verify-module {:definitions [stale]} (:prepared pkg))
                              [:definitions 0 :status]))))
    (let [other (assoc-in inner [:args :value] :str-upper)
          changed (parse/prepare-module (into strings [wrapper other outer host]) {} {})
          blocked (get-in (source/verify-module {:definitions [rewritten]} changed) [:definitions 0 :status])]
      (is (= :blocked blocked)))
    (is (= [slot] (:target-path row)))
    (is (= (ids/binding-id owner slot) (:id row)))
    (is (= :blocked
           (get-in (source/verify-module {:definitions [(assoc-in rewritten [:proposed :parent] :inner)]}
                                         (:prepared pkg)) [:definitions 0 :status])))))


(defn- execute-list-comparison
  [package originals owner target]
  (let [prepared (:prepared package)
        plan (source/verify-module (source/plan-module originals prepared) prepared)
        entry (some #(when (= owner (:identity %)) %) (:definitions plan))
        trace (get-in entry [:v2 :list-builders [target]])
        grouped (group-by :kind (parse/parse-prepared-module prepared))
        env (lookups/build-lookups {:fns (:fn grouped) :slots (:slot grouped) :fn-slots (:fn-slot grouped)
                                    :bindings (:binding grouped) :list-items (:binding-list-item grouped)})
        callables (eager/compile-all (assoc env :base-fns (registry/compute-base-fns-map @loaded-bases)))
        missing-slots (update trace :records (partial remove #(= :slot (:kind %))))
        builder (list-execution/compile-rows [owner] target (:field trace) (:name->id prepared) (:records trace)
                                             {:child-callables callables :lookups env :lazy-seq? false})]
    (is (= :list-declaration-mismatch
           (:code (failure #(list-signature/entry owner (:name->id prepared) missing-slots)))))
    [(str/join (builder {} {})) ((callables owner) {} {})]))


(deftest verified-auth-page-builder-uses-the-stored-operand-identities
  (let [auth (edn/read-string (slurp (io/resource "packages/app/auth-pages/fns.edn")))
        originals (concat (filter #(= :str (:name %)) (sources "strings"))
                          (filter #(= :shipped-asset (:name %)) (sources "system"))
                          (for [d (:fns auth) :when (#{:_auth-reset-body :_auth-reset-js} (:name d))]
                            (assoc d :namespace (:namespace auth))))
        package (snapshot/package-index (parse/prepare-module originals {} {}) originals)
        owner (ids/fn-id "app.auth-pages" :_auth-reset-body)
        target (ids/slot-id (ids/fn-id "core.strings" :str) :parts)
        {:keys [binding-id builder-id tail-id]} (list-builder/origin owner target)
        {:keys [context options]} (snapshot/freeze-closure package [owner] @roots)
        checked (analysis/analyze-effective-input context owner options)
        signature (get-in checked [:scopes [binding-id] :signature])
        item (ids/slot-id builder-id :item-1)
        js (ids/fn-id "app.auth-pages" :_auth-reset-js)]
    (is (= :checked (:status checked)) (pr-str (:diagnostics checked)))
    (is (= [:list :any] (:return signature)))
    (is (= :text (get-in checked [:scopes [] :signature :return])))
    (is (= js (:ref-fn-id (some #(when (= [item] (:target-path %)) %) (vals (:bindings context))))))
    (is (= [tail-id] (get-in options [:implementations builder-id :argument-paths :tail])))
    (let [[migrated legacy] (execute-list-comparison package originals owner target)]
      (is (= legacy migrated))
      (is (str/includes? migrated (slurp (io/resource "packages/app/auth-pages/reset.js")))))))


(deftest absent-or-incompatible-package-evidence-is-rejected-test
  (let [package (fixture "checked")
        reader (ids/fn-id "core.strings" :str-upper)]
    (is (= :missing-original-declaration
           (:code (failure #(snapshot/verify-binding (update package :originals dissoc reader) host-id @roots)))))
    (is (= :implementation-signature-required
           (:code (failure #(snapshot/verify-binding package host-id (dissoc @roots reader))))))
    (is (= :ambiguous-original-declaration
           (:code (failure #(snapshot/verify-binding (update-in package [:originals reader] into strings) host-id @roots)))))
    (is (= :rule-signature-view-required
           (:code (failure #(snapshot/verify-binding package host-id
                                                     (assoc-in @roots [reader :return-type-rule] identity))))))
    (is (= :types/check-failed
           (:type (failure #(snapshot/verify-binding (fixture 5) host-id @roots)))))))


(deftest core-prometheus-formatting-source-test
  (testing "The proposed scoped binding cannot supply its own reference occurrence"
    (let [originals (into [] (mapcat sources) (:modules (read-package-edn "package.edn")))
          prepared (binding [shapes/*marker-registry-override* (atom (shapes/markers-snapshot))]
                     (parse/prepare-module originals {} {}))
          id (ids/fn-id "core.system" :_prom-seg)
          rejected (failure #(snapshot/verify-binding (snapshot/package-index prepared originals) id @roots))]
      (is (= :missing-reference-use (:code rejected)))
      (is (= id (:fn-id rejected))))))


(deftest existing-web-reference-keeps-its-core-field-identity-test
  (let [core (filterv #(contains? #{:cell :deref} (:name %)) (sources "concurrency"))
        module (edn/read-string (slurp (io/resource "packages/web/http/fns.edn")))
        web (into [] (comp (filter #(contains? #{:_response-cache-current :response-cache-cell} (:name %)))
                           (map #(assoc % :namespace (:namespace module)))) (:fns module))
        dep (parse/prepare-module core {} {})
        prepared (parse/prepare-module web (:name->id dep) (:defs-by-name dep))
        owner (ids/fn-id "web.http" :_response-cache-current)
        target (ids/fn-id "web.http" :response-cache-cell)
        field (ids/slot-id (ids/fn-id "core.concurrency" :deref) :a)
        binding-id (ids/binding-id owner field)
        {:keys [context options]} (snapshot/freeze-closure (snapshot/package-index prepared (into core web)) [owner] @roots)
        graph (analysis/analyze-effective-input context owner options)
        [edge] (:dependencies graph)]
    (is (= target (:target-fn-id edge)))
    (is (= [[owner []] [field]] (:from edge)))
    (is (= [owner [binding-id]] (:to edge)))
    (is (= #uuid "bd46df2a-1484-5826-887c-c5657179f5df" binding-id (:binding-id edge)))
    (is (true? (get-in graph [:coverage :signatures])))))


(deftest package-renames-keep-projection-identity-and-required-input-test
  (let [core (concat (sources "concurrency") (sources "strings"))
        web (edn/read-string (slurp (io/resource "packages/web/crud-parse/fns.edn")))
        declarations (into (vec core) (map #(assoc % :namespace (:namespace web))) (:fns web))
        ;; Pinning the SAME primitive is authored policy and must survive
        ;; as a configuration on the projection destination.
        pinned {:namespace "fixture" :name :pinned-sleep :parent :sleep
                :args {:ms {:as :wait :type :int}}}
        selected (filterv #(contains? #{:sleep :str-trim :_retry-pause :_interval-sleep :_parse-fn-form-pid-trim-item}
                                      (:name %)) declarations)
        selected (conj selected pinned)
        package (snapshot/package-index (parse/prepare-module selected {} {}) selected)]
    (doseq [[owner-name owner-ns base-name base-ns field-name alias-name]
            [[:_retry-pause "core.concurrency" :sleep "core.concurrency" :ms :delay-ms]
             [:_interval-sleep "core.concurrency" :sleep "core.concurrency" :ms :every-ms]
             [:_parse-fn-form-pid-trim-item "web.crud" :str-trim "core.strings" :string :item]
             [:pinned-sleep "fixture" :sleep "core.concurrency" :ms :wait]]]
      (let [owner (ids/fn-id owner-ns owner-name)
            field (ids/slot-id (ids/fn-id base-ns base-name) field-name)
            alias-id (ids/slot-id owner alias-name)
            {:keys [context options]} (snapshot/freeze-closure package [owner] @roots)
            graph (analysis/analyze-effective-input context owner options)
            [route] (:projections graph)
            address [[owner []] [alias-id]]]
        (is (= :checked (:status graph)))
        (is (empty? (:obligations graph)))
        (is (= [address] (:required-addresses graph)))
        (is (= [[owner []] [field]] (:from route)))
        (is (= address (:source route)))
        (is (= (ids/binding-id owner field) (get-in graph [:public-input :bindings (:from route) :binding-id])))))))


(deftest checked-primitive-rename-reports-signature-coverage
  (let [defs (filterv #(#{:sleep :_retry-pause} (:name %)) (sources "concurrency"))
        id (ids/fn-id "core.concurrency" :_retry-pause)
        result (snapshot/projection-readiness (snapshot/package-index (parse/prepare-module defs {} {}) defs) id @roots)]
    (is (= :checked (:status result)))
    (is (true? (get-in result [:coverage :signatures])))
    (is (= :checked (get-in result [:projections 0 :proof :status])))
    (is (empty? (:obligations result)))))


(deftest actual-generic-get-rename-has-a-typed-route-and-explicit-rule-coverage
  (let [selected (into (filterv #(= :get (:name %)) (sources "collections"))
                       (filter #(= :_prom-stack (:name %))) (sources "system"))
        id (ids/fn-id "core.system" :_prom-stack)
        field (ids/slot-id (ids/fn-id "core.collections" :get) :coll)
        alias-id (ids/slot-id id :input)
        package (snapshot/package-index (parse/prepare-module selected {} {}) selected)
        result (snapshot/projection-readiness package id @roots)
        [route] (:projections result)]
    (is (= :incomplete (:status result)))
    (is (= :rule-signature-view-required (-> result :diagnostics last :code)))
    (is (false? (get-in result [:coverage :signatures])))
    (is (= (ids/binding-id id field) (:binding-id route)))
    (is (= [[id []] [field]] (:from route)))
    (is (= [[id []] [alias-id]] (:source route)))
    (is (= :union (first (:actual-type route))))
    (is (= :deferred (get-in route [:proof :status])))
    (is (seq (:obligations result)))
    (is (some #{[[id []] [alias-id]]} (:required-addresses result)))))


(deftest named-dictionary-rename-reaches-the-scoped-checker
  (let [web (edn/read-string (slurp (io/resource "packages/web/crud/fns.edn")))
        child (assoc (some #(when (= :_apply-error-code (:name %)) %) (:fns web)) :namespace (:namespace web))
        ds (conj (filterv #(= :get (:name %)) (sources "collections"))
                 (some #(when (= :keyword-map (:name %)) %) (sources "refinements")) child)
        owner (ids/fn-id (:namespace child) (:name child))
        dictionary (ids/fn-id (:namespace (second ds)) :keyword-map)
        package (snapshot/package-index (parse/prepare-module ds {} {}) ds)
        report (snapshot/projection-readiness package owner @roots)
        [route] (:projections report)
        field (ids/slot-id (ids/fn-id "core.collections" :get) :coll)]
    (is (= :incomplete (:status report)))
    (is (some #(= :rule-signature-view-required (:code %)) (:diagnostics report)))
    (is (= (ids/binding-id owner field) (:binding-id route)))
    (is (= [[owner []] [(ids/slot-id owner :outcome)]] (:source route)))
    (is (= [:map :keyword :any] (:actual-type route)))
    (let [duplicates (conj ds (assoc-in (second ds) [:map :value] :bool))
          ambiguous (snapshot/package-index (parse/prepare-module duplicates {} {}) duplicates)]
      (is (= #{:non-value-binding-required :binding-metadata-required :rename-projection-required}
             (set (get-in (snapshot/projection-readiness ambiguous owner @roots) [:diagnostics 0 :blockers])))))
    (is (= :missing-original-declaration
           (-> (snapshot/projection-readiness (update package :originals dissoc dictionary) owner @roots)
               :diagnostics first :code)))))


(deftest nested-uses-retain-two-scopes-and-one-original-rename
  (let [core (into (filterv #(#{:str-trim :re-find?} (:name %)) (sources "strings"))
                   (filter #(= :equal? (:name %))) (sources "logic"))
        web (edn/read-string (slurp (io/resource "packages/web/crud-parse/fns.edn")))
        renamed (-> (some #(when (= :_parse-fn-form-pid-trim-item (:name %)) %) (:fns web))
                    (assoc :namespace (:namespace web)))
        target (keyword (:namespace web) (name (:name renamed)))
        host {:namespace "fixture" :name :matches :parent :equal? :args {:a target :b target}}
        ds (conj core renamed host)
        owner (ids/fn-id "fixture" :matches)
        origin (ids/fn-id (:namespace web) (:name renamed))
        field (ids/slot-id (ids/fn-id "core.strings" :str-trim) :string)
        alias-id (ids/slot-id origin :item)
        uses (mapv #(ids/binding-id owner (ids/slot-id (ids/fn-id "core.logic" :equal?) %)) [:a :b])
        result (snapshot/projection-readiness (snapshot/package-index (parse/prepare-module ds {} {}) ds) owner @roots)
        routes (:projections result)
        colliding (assoc host :parent :re-find? :args {:string target :pattern target})
        bad (conj core renamed colliding)
        refused (snapshot/projection-readiness (snapshot/package-index (parse/prepare-module bad {} {}) bad) owner @roots)]
    (is (= :blocked (:status refused)))
    (is (= [:ref-scope-required] (get-in refused [:diagnostics 0 :blockers])))
    (is (= :checked (:status result)) (pr-str (:diagnostics result)))
    (is (= 2 (count routes)))
    (is (= #{origin} (set (map :origin-fn-id routes))))
    (is (= #{(ids/binding-id origin field)} (set (map :binding-id routes))))
    (is (= (set (for [use-id uses] [[owner [use-id]] [field]])) (set (map :from routes))))
    (is (= (set (for [use-id uses] [[owner [use-id]] [alias-id]])) (set (map :source routes))))
    (is (every? #(= :checked (get-in % [:proof :status])) routes))))


(deftest projected-field-bindings-preserve-their-origin-across-diamond-and-reference-uses
  (let [core (into (filterv #(= :str-trim (:name %)) (sources "strings"))
                   (filter #(= :equal? (:name %)) (sources "logic")))
        web (edn/read-string (slurp (io/resource "packages/web/crud-parse/fns.edn")))
        renamed (assoc (some #(when (= :_parse-fn-form-pid-trim-item (:name %)) %) (:fns web)) :namespace (:namespace web))
        left {:namespace "test" :name :left :parent (:name renamed) :args {:item {:value "  text  "}}}
        right {:namespace "test" :name :right :parent (:name renamed)}
        merged {:namespace "test" :name :merged :parents [:left :right]}
        caller {:namespace "test" :name :compare :parent :equal? :args {:a :merged :b :merged}}
        ds (into core [renamed left right merged caller])
        replay (fn [xs]
                 (snapshot/projection-readiness (snapshot/package-index (parse/prepare-module xs {} {}) xs)
                                                (ids/fn-id "test" :compare) @roots))
        graph (replay ds)
        trim-id (ids/fn-id "core.strings" :str-trim)
        original (ids/fn-id (:namespace web) (:name renamed))
        alias-id (ids/slot-id original :item)
        owner (ids/fn-id "test" :compare)
        left-id (ids/fn-id "test" :left)
        use-ids (mapv #(ids/binding-id owner (ids/slot-id (ids/fn-id "core.logic" :equal?) %)) [:a :b])
        routes (:projections graph)
        conflicting (mapv #(if (= :right (:name %)) (assoc % :args {:item "different"}) %) ds)]
    (is (= :checked (:status graph)) (pr-str (:diagnostics graph)))
    (is (= 2 (count routes)))
    (is (= #{(ids/binding-id original (ids/slot-id trim-id :string))} (set (map :binding-id routes))))
    (is (= (set (map (fn [id]
                       {:address [[owner [id]] [alias-id]]
                        :binding-id (ids/binding-id left-id alias-id)
                        :origin-fn-id left-id :expression-kind "value"}) use-ids))
           (set (map :source-binding routes))))
    (is (= :blocked (:status (replay conflicting))))
    (is (some #{:inherited-projection-required} (get-in (replay conflicting) [:diagnostics 0 :blockers])))))
