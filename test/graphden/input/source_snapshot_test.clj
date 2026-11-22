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
    [graphden.input.source-named-types :as named-types]
    [graphden.input.source-package-fixtures :as fixtures]
    [graphden.input.source-scope :as scope]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.sync :as sync]
    [graphden.types.check :as check]
    [graphden.types.check.literals :as literals]
    [graphden.types.core :as types]
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
        (doseq [{ns-id :namespace :keys [name]} (concat strings (filter #(#{:equal? :const :coalesce} (:name %)) (sources "logic"))
                                                        (filter #(#{:get :zipmap} (:name %)) (sources "collections"))
                                                        (filter #(#{:str :str-trim :re-find?} (:name %)) (sources "strings"))
                                                        (filter #(#{:shipped-asset :to-json-string :throw} (:name %)) (sources "system"))
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


(deftest unchanged-hiccup-field-annotation-freezes-with-its-rich-collection-type
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            types/*type-aliases-override* (atom {})
            shapes/*marker-registry-override* (atom {:secret {:monotone? true :hide-result? true}})]
    (let [declarations (vec (concat (filter #(= :keyword-or-text (:name %)) (sources "refinements"))
                                    (fixtures/declarations "packages/web/html/fns.edn" #{:hiccup-node :hiccup})))
          child {:name :head-annotation :namespace "fixture" :parent :hiccup
                 :args {:children {:as :children :type [:list :hiccup-node] :description "Extra head elements"}}}
          definitions (conj declarations child)
          _ (sync/register-type-aliases! declarations)
          base (get @fixtures/web-base-definitions :hiccup)
          _ (registry/record-rich-types! (ids/fn-id "web.html" :hiccup) :hiccup base)
          roots (:by-id @registry/*rich-types-override*)
          prepared (parse/prepare-module definitions {} {})
          package (snapshot/package-index prepared definitions)
          owner (ids/fn-id "fixture" :head-annotation)
          field (ids/slot-id (ids/fn-id "web.html" :hiccup) :children)
          binding (ids/binding-id owner field)
          {:keys [context options]} (snapshot/freeze-closure package [owner] roots)
          graph (analysis/analyze-effective-input context owner options)
          child-field (some #(when (= field (:id %)) %) (get-in graph [:scopes [] :configured-contract :fields]))]
      (is (= :checked (:status graph)) (pr-str (:diagnostics graph)))
      (is (= #{[:value-type :field-read [field]]
               [:value-type :field-read [(ids/slot-id (ids/fn-id "web.html" :hiccup) :attrs)]]}
             (set (map (juxt :kind :trigger :path) (:obligations graph)))))
      (is (= [:list :hiccup-node] (get-in context [:source-binding-types [binding (ids/primitive-fn-id :sequence)]])))
      (is (= [:list (types/resolve-alias :hiccup-node)] (:type child-field)))
      (is (= "Extra head elements" (:description child-field)))
      (is (= nil (get-in context [:bindings binding :expression-kind])))
      (is (not (contains? (get-in graph [:public-input :bindings]) [[owner []] [field]]))))))


(deftest shipped-html-page-forwarding-preserves-baseline-and-refuses-unproved-tag-typing
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            types/*type-aliases-override* (atom {})
            shapes/*marker-registry-override* (atom {:secret {:monotone? true :hide-result? true}})]
    (let [core (mapcat sources (:modules (read-package-edn "package.edn")))
          html-module (edn/read-string (slurp (io/resource "packages/web/html/fns.edn")))
          html (mapv #(assoc % :namespace (:namespace html-module)) (:fns html-module))
          definitions (into (vec core) html)
          declared-ids (set (map #(ids/fn-id (:namespace %) (:name %)) definitions))
          _ (sync/register-type-aliases! definitions)
          _ (doseq [[name {:keys [namespace] :as base}] @fixtures/web-base-definitions
                    :when (contains? declared-ids (ids/fn-id namespace name))]
              (registry/record-rich-types! (ids/fn-id namespace name) name base))
          prepared (parse/prepare-module definitions {} {})
          package (snapshot/package-index prepared definitions)
          owner (ids/fn-id "web.html" :html-page)
          fields [:title :head :scripts]
          original (some #(when (= :html-page (:name %)) %) html)
          legacy-entry (first (:definitions (source/plan-module [original] prepared)))
          {:keys [proofs entry]} (source/forwarded-fields-baseline prepared legacy-entry fields)
          {:keys [context options]} (snapshot/freeze-closure (assoc-in package [:closure-baselines owner] entry)
                                                             [owner] (:by-id @registry/*rich-types-override*))
          original-check (:check-expression options)
          conflict (atom nil)
          checked (fn [{:keys [expected actual substitution address] :as request}]
                    (try (original-check request)
                         (catch clojure.lang.ExceptionInfo error
                           (reset! conflict {:address address :expected (types/resolve substitution expected)
                                             :actual (types/resolve substitution actual)
                                             :raw-expected expected :raw-actual actual
                                             :substitution (select-keys substitution
                                                                        (filter types/type-var?
                                                                                (tree-seq coll? seq [expected actual])))})
                           (throw error))))
          diagnostic (failure #(scope/verify-forwarded-projections
                                 context proofs (fn [ctx id]
                                                  (analysis/analyze-effective-input ctx id (assoc options :check-expression checked)))))
          callback (some #(when (= :_hiccup-normalize-attr-key (:name %)) %) html)
          verified-callback (first (:definitions (source/verify-module (source/plan-module [callback] prepared) prepared)))
          source-links (into {} (keep #(when (and (= :slot (:kind %)) (:source-slot-id %))
                                         [(:id %) (:source-slot-id %)]))
                             (get-in legacy-entry [:legacy :records]))]
      (.mkdirs (io/file "target"))
      (spit "target/input-html-page-type-conflict.edn" (pr-str @conflict))
      (is (= :authored-source-equivalent (:verification entry)) (:blockers entry))
      (is (= (select-keys (:args original) fields) (get-in entry [:proposed :input])))
      (is (= (set fields) (set (map (comp peek :source-path) proofs))))
      (is (every? (comp nil? :target-address) proofs))
      (is (= source-links (into {} (map (juxt :field-id :target-field-id)) proofs)))
      (is (= :authored-source-equivalent (:verification verified-callback)) (:blockers verified-callback))
      (is (= [:types/check-failed :type-mismatch] ((juxt :type :reason) diagnostic)))
      (is (= owner (ffirst (:address diagnostic))))
      (is (= (:address diagnostic) (:address @conflict)))
      (is (= :keyword (:expected @conflict)))
      (is (= [:union :keyword :null] (pop (:actual @conflict))))
      (is (types/type-var? (peek (:actual @conflict))))
      (is (= [(ids/slot-id (ids/fn-id "core.logic" :equal?) :a)] (second (:address diagnostic)))))))


(deftest frozen-conditional-rule-does-not-read-an-ambient-predicate
  (let [predicate (random-uuid)
        target (random-uuid)
        nullable [:union :null :text]
        facts {:test {:type :bool :ref predicate}
               :then {:type nullable :ref target} :else {:type :text}}
        bases @fixtures/web-base-definitions]
    (binding [registry/*rich-types-override*
              (atom {:by-name {} :by-id {predicate {:primary-parent :some? :resolved-bindings {:value {:ref target}}}}})
              registry/*per-org-rich-override* (atom {})]
      (let [id (ids/fn-id "core.logic" :if)
            _ (registry/record-rich-types! id :if (get bases :if))
            entry (registry/rich-type-of-id id)
            result (check/infer-input-signature {:fn-id id :root-entry entry :bindings-info facts
                                                 :argument-effects {} :free-arguments #{}
                                                 :signature-view {:by-id {} :by-name {}}
                                                 :declaration {} :static-return nullable})]
        (is (= :text ((:return-type-rule entry) facts nullable)))
        (is (= nullable (:return result)))))))


(deftest generated-list-parameters-retain-frozen-element-variables
  (let [base (some #(when (= :zipmap (:name %)) %) (sources "collections"))
        owner (ids/fn-id "fixture" :directory)
        definitions [base {:namespace "fixture" :name :directory :parent :zipmap
                           :args {:keys {:append [{:value :first} {:value :second}] :closed true}
                                  :vals {:append ["one" 2] :closed true}}}]
        package (snapshot/package-index (parse/prepare-module definitions {} {}) definitions)
        {:keys [context options]} (snapshot/freeze-closure package [owner] @roots)
        graph (analysis/analyze-effective-input context owner options)]
    (is (= :checked (:status graph)) (pr-str (:diagnostics graph)))
    (is (empty? (:obligations graph)))
    (is (= [:map :keyword [:union :int :text]] (get-in graph [:scopes [] :signature :return])))))


(deftest shipped-generic-return-retains-per-use-substitutions-and-legacy-values
  (let [const (some #(when (= :const (:name %)) %) (sources "logic"))
        zipmap (some #(when (= :zipmap (:name %)) %) (sources "collections"))
        number {:namespace "fixture" :name :number :parent :const :args {:value 7}}
        text {:namespace "fixture" :name :text :parent :const :args {:value "seven"}}
        directory {:namespace "fixture" :name :directory :parent :zipmap
                   :args {:keys {:append [{:value :number} {:value :text}] :closed true}
                          :vals {:append [:fixture/number :fixture/text] :closed true}}}
        definitions [const zipmap number text directory]
        prepared (parse/prepare-module definitions {} {})
        package (snapshot/package-index prepared definitions)
        owner (ids/fn-id "fixture" :directory)
        base (ids/fn-id "core.logic" :const)
        {:keys [context options]} (snapshot/freeze-closure package [owner] @roots)
        graph (analysis/analyze-effective-input context owner options)
        per-use (into {} (for [[_ scope] (:scopes graph)
                               :when (contains? #{(ids/fn-id "fixture" :number) (ids/fn-id "fixture" :text)} (:fn-id scope))]
                           [(:fn-id scope) (get-in scope [:signature :return])]))
        grouped (group-by :kind (parse/parse-prepared-module prepared))
        env (lookups/build-lookups {:fns (:fn grouped) :slots (:slot grouped) :fn-slots (:fn-slot grouped)
                                    :bindings (:binding grouped) :list-items (:binding-list-item grouped)})
        legacy (eager/compile-all (assoc env :base-fns (registry/compute-base-fns-map @loaded-bases)))]
    (is (= 'a (get-in context [:source-return-types [base (ids/primitive-fn-id :any)]])))
    (is (= :checked (:status graph)) (pr-str (:diagnostics graph)))
    (is (= {(ids/fn-id "fixture" :number) :int (ids/fn-id "fixture" :text) :text} per-use))
    (is (= [:map :keyword [:union :int :text]] (get-in graph [:scopes [] :signature :return])))
    (is (= {:number 7 :text "seven"} ((legacy owner) {} {})))
    (is (= :implementation-signature-required
           (:code (failure #(snapshot/freeze-closure package [owner] (assoc-in @roots [base :return] :any))))))))


(deftest shipped-bottom-return-is-frozen-without-executing-the-thrower
  (let [declaration (some #(when (= :throw (:name %)) %) (sources "system"))
        id (ids/fn-id "core.system" :throw)
        package (snapshot/package-index (parse/prepare-module [declaration] {} {}) [declaration])
        {:keys [context options]} (snapshot/freeze-closure package [id] @roots)
        graph (analysis/analyze-effective-input context id options)]
    (is (= :never (get-in context [:source-return-types [id (ids/primitive-fn-id :any)]])))
    (is (= :checked (:status graph)) (pr-str (:diagnostics graph)))
    (is (= :never (get-in graph [:scopes [] :signature :return])))
    (is (= :any (get-in graph [:public-input :routes [:exception] :completion-type])))))


(deftest scoped-bindings-are-certified-together
  (let [declarations (into (filterv #(= :str-trim (:name %)) (sources "strings"))
                           (concat (filter #(= :equal? (:name %)) (sources "logic"))
                                   (filter #(= :to-json-string (:name %)) (sources "system"))))
        host {:namespace "fixture" :name :compare-json :parent :equal?
              :args {:a :str-trim :b :to-json-string :string " \"same\" " :data "same"}}
        owner (ids/fn-id "fixture" :compare-json)
        migrate (fn [definition]
                  (let [originals (conj declarations definition)
                        package (snapshot/package-index (parse/prepare-module originals {} {}) originals)]
                    (snapshot/migrate-binding package owner @roots)))
        result (migrate host)
        rows (get-in result [:v2 :scoped-inputs])]
    (is (= :authored-source-equivalent (:verification result)) (pr-str (:blockers result)))
    (is (= #{[:a :string] [:b :data]} (set (keys rows))))
    (doseq [[path parent field] [[[:a :string] (ids/fn-id "core.strings" :str-trim) :string]
                                 [[:b :data] (ids/fn-id "core.system" :to-json-string) :data]]]
      (is (= (ids/binding-id owner (ids/slot-id parent field)) (get-in rows [path :row :id]))))
    (is (= :types/check-failed
           (:type (failure #(migrate (assoc-in host [:args :string] 7))))))))


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


(deftest forwarded-field-is-verified-with-shipped-implementation-signatures
  (let [codec (some #(when (= :to-json-string (:name %)) %) (sources "system"))
        body {:namespace "fixture" :name :body :parent :core.system/to-json-string}
        host {:namespace "fixture" :name :json-field :parent :core.strings/_csv-escape-cell
              :args {:value :body :data {:type :jsonb :description "Forwarded JSON input."}}}
        originals (into strings [codec body host])
        package (snapshot/package-index (parse/prepare-module originals {} {}) originals)
        owner (ids/fn-id "fixture" :json-field)
        migrated (snapshot/migrate-forwarded-field package owner :data @roots)
        path [:value :data]
        certificate (get-in migrated [:v2 :scoped-inputs path])
        local-slot (ids/slot-id owner :data)
        target-slot (ids/slot-id (ids/fn-id "core.system" :to-json-string) :data)]
    (is (= :authored-source-equivalent (:verification migrated)) (:blockers migrated))
    (is (= [:project [:data]] (get-in migrated [:proposed :bind path])))
    (is (= [local-slot] (get-in certificate [:row :projection-path])))
    (is (= [target-slot] (get-in certificate [:row :target-path])))
    (is (= :implementation-signature-required
           (:code (failure #(snapshot/migrate-forwarded-field package owner :data (dissoc @roots (ids/fn-id "core.system" :to-json-string)))))))
    (let [legacy {:namespace "fixture" :fns [(dissoc host :namespace)]}
          staged {:namespace "fixture" :input-format 2 :fns [(dissoc (:proposed migrated) :namespace)]}
          certified (snapshot/with-migrated-forwarding package owner :data @roots)
          replay (snapshot/with-staged-module certified legacy staged)
          {:keys [context]} (snapshot/freeze-closure replay [owner] @roots)
          row (:row certificate)]
      (is (= row (get-in context [:bindings (:id row)])))
      (is (= :unverified-staged-declaration
             (:code (failure #(snapshot/with-staged-module package legacy staged)))))
      (is (= :unverified-staged-declaration
             (:code (failure #(snapshot/with-staged-module certified legacy
                                                           (assoc-in staged [:fns 0 :bind path] [:project [:other]])))))))))


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


(deftest direct-inline-source-freezes-at-its-authored-use
  (let [host {:namespace "fixture" :name :format-cell :parent :_csv-escape-cell
              :args {:value {:parent :str-upper :args {:string "checked"}}}}
        originals (conj strings host)
        package (snapshot/package-index (parse/prepare-module originals {} {}) originals)
        child-id (:fn-id (first (remove #(contains? (:originals package) (:fn-id %))
                                        (get-in package [:prepared :fn-defs]))))
        base (ids/fn-id "core.strings" :str-upper)
        field (ids/slot-id base :string)
        edge (ids/binding-id host-id (ids/slot-id (ids/fn-id "core.strings" :_csv-escape-cell) :value))
        {:keys [context options]} (snapshot/freeze-closure package [host-id child-id] @roots)
        checked (analysis/analyze-effective-input context host-id options)]
    (is (uuid? child-id))
    (is (= child-id (get-in context [:bindings edge :ref-fn-id])))
    (is (= [base] (get-in context [:fns child-id :parent-ids])))
    (is (= "checked" (get-in context [:bindings (ids/binding-id child-id field) :value])))
    (is (= :checked (:status checked)) (pr-str (:diagnostics checked)))
    (is (= :text (get-in checked [:scopes [edge] :signature :return])))
    (is (= child-id (get-in (snapshot/freeze-closure package [child-id host-id] @roots) [:context :fns child-id :id])))
    (is (= :missing-original-declaration (:code (failure #(snapshot/freeze-closure package [child-id] @roots)))))
    (is (= :conflicting-inline-declaration
           (:code (failure #(snapshot/freeze-closure (assoc-in package [:originals child-id] [host]) [host-id] @roots)))))
    (is (= :missing-original-declaration
           (:code (failure #(snapshot/freeze-closure (update package :originals dissoc base) [host-id] @roots)))))))


(deftest multiple-inline-branches-freeze-every-authored-use
  (let [leaf {:parent :str-upper :args {:string "checked"}}
        host {:namespace "fixture" :name :inline-comparison :parent :equal?
              :args {:a {:parent :str-upper :args {:string leaf}}
                     :b leaf}}
        equal (some #(when (= :equal? (:name %)) %) (sources "logic"))
        originals (conj strings equal host)
        package (snapshot/package-index (parse/prepare-module originals {} {}) originals)
        owner (ids/fn-id "fixture" :inline-comparison)
        base (ids/fn-id "core.strings" :str-upper)
        field (ids/slot-id base :string)
        left-edge (ids/binding-id owner (ids/slot-id (ids/fn-id "core.logic" :equal?) :a))
        right-edge (ids/binding-id owner (ids/slot-id (ids/fn-id "core.logic" :equal?) :b))
        {:keys [context options]} (snapshot/freeze-closure package [owner] @roots)
        left (get-in context [:bindings left-edge :ref-fn-id])
        right (get-in context [:bindings right-edge :ref-fn-id])
        child-edge (ids/binding-id left field)
        child (get-in context [:bindings child-edge :ref-fn-id])
        checked (analysis/analyze-effective-input context owner options)]
    (is (= 3 (count (set [left right child]))))
    (is (every? uuid? [left right child]))
    (doseq [id [right child]]
      (is (= [base] (get-in context [:fns id :parent-ids])))
      (is (= "checked" (get-in context [:bindings (ids/binding-id id field) :value]))))
    (is (= :checked (:status checked)) (pr-str (:diagnostics checked)))
    (is (= :text (get-in checked [:scopes [left-edge child-edge] :signature :return])))))


(deftest inline-list-leaves-preserve-original-operand-positions
  (let [body {:parent :str-upper :args {:string "same"}}
        host {:name :sample :namespace "fixture" :parent :str :args {:parts [body body]}}
        originals (conj (filterv #(#{:str-upper :str} (:name %)) (sources "strings")) host)
        prepared (parse/prepare-module originals {} {})
        definition (some #(when (= :sample (:name %)) %) (:fn-defs prepared))
        children (mapv (:name->id prepared) (get-in definition [:args :parts]))
        owner (:fn-id definition)
        field (ids/slot-id (ids/fn-id "core.strings" :str) :parts)
        {:keys [binding-id builder-id]} (list-builder/origin owner field)
        {:keys [context options]} (snapshot/freeze-closure (snapshot/package-index prepared originals) [owner] @roots)
        link (get-in context [:bindings binding-id])
        ctx (assoc prepared :fn-defs [definition])
        entry (first (:definitions (source/verify-module (source/plan-module [host] ctx) ctx)))
        fragment (get-in entry [:v2 :list-builders [field] :fragments 0])
        checked (analysis/analyze-effective-input context owner options)]
    (is (= 2 (count (set children))))
    (is (= :checked (:status checked)) (pr-str (:diagnostics checked)))
    (is (= builder-id (:ref-fn-id link)))
    (is (false? (list-signature/inline-operand? fragment (assoc link :id owner) 0 (first children))))
    (is (false? (list-signature/inline-operand? (update fragment :items #(vec (reverse %))) link 0 (second children))))
    (doseq [[position child] (map-indexed vector children)]
      (let [operand (ids/binding-list-item-id binding-id position)
            row (get-in context [:bindings operand])]
        (is (uuid? child))
        (is (list-signature/inline-operand? fragment link position child))
        (is (false? (list-signature/inline-operand? fragment link (- 1 position) child)))
        (is (= [builder-id child] ((juxt :fn-id :ref-fn-id) row)))
        (is (= [(ids/slot-id builder-id (str "item-" position))] (:target-path row)))
        (is (= :text (get-in checked [:scopes [binding-id operand] :signature :return])))))))


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
    (is (= :conflicting-runtime-obligations (-> result :diagnostics last :code)))
    (is (false? (get-in result [:coverage :signatures])))
    (is (= (ids/binding-id id field) (:binding-id route)))
    (is (= [[id []] [field]] (:from route)))
    (is (= [[id []] [alias-id]] (:source route)))
    (is (= :union (first (:actual-type route))))
    (is (= :deferred (get-in route [:proof :status])))
    (is (seq (:obligations result)))
    (is (some #{[[id []] [alias-id]]} (:required-addresses result)))))


(defn- package-record-pin
  [resource record-name host-name]
  (let [selected (fixtures/declarations resource #{record-name host-name})
        module-ns (:namespace (first selected))
        defs (into (filterv #(= :get (:name %)) (sources "collections")) selected)
        prepared (parse/prepare-module defs {} {})
        package (snapshot/package-index prepared defs)
        owner (ids/fn-id module-ns host-name)
        record-id (ids/fn-id module-ns record-name)
        entry (fn [id]
                (let [ctx (assoc prepared :fn-defs [(get-in package [:declarations id])])
                      orig (get-in package [:originals id])]
                  (first (:definitions (source/verify-module (source/plan-module orig ctx) ctx)))))]
    {:prepared package :owner owner :record-id record-id :record (entry record-id) :use-entry (entry owner)}))


(deftest stored-record-fields-survive-checked-base-signature
  (let [[record] (fixtures/declarations "packages/web/crud/fns.edn" #{:_tighten-parsed-shape})
        [base] (fixtures/declarations "packages/web/crud-seq/fns.edn" #{:try-apply-tighten})
        [dictionary] (fixtures/declarations "packages/core/refinements/fns.edn" #{:keyword-map})
        ds [record base dictionary]
        id (ids/fn-id (:namespace base) (:name base))
        type-id (ids/fn-id (:namespace record) (:name record))
        package (snapshot/package-index (parse/prepare-module ds {} {}) ds)
        implementations (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
                                  types/*type-aliases-override* (atom {})
                                  shapes/*marker-registry-override* (atom (shapes/markers-snapshot))]
                          (let [loaded @fixtures/web-base-definitions]
                            (sync/register-type-aliases! ds)
                            (registry/record-rich-types! id (:name base) (get loaded (:name base)))
                            (:by-id @registry/*rich-types-override*)))
        {:keys [context options]} (snapshot/freeze-closure package [id] implementations)
        graph (analysis/analyze-effective-input context id options)
        [field] (get-in graph [:scopes [] :configured-contract :fields])]
    (is (= :checked (:status graph)) (pr-str (:diagnostics graph)))
    (is (true? (get-in graph [:coverage :signatures])))
    (is (= #{:db} (get-in graph [:scopes [] :signature :effects])))
    (let [check-literal (fn [value]
                          ((:check-expression options) {:address [[id []] [(:id field)]]
                                                        :expected (:type field) :actual (literals/classify-literal value)
                                                        :expression {:kind :value :value value} :substitution {}}))
          complete {:binding-id id :effects-val "read" :args-val 1 :ret-val 2 :delta true}]
      (is (= :checked (:status (check-literal complete))))
      (is (= :named-record-mismatch (:reason (failure #(check-literal (dissoc complete :delta)))))))
    (is (= type-id (:type-id field)))
    (is (= (:type record) (:type field)))
    (is (= (into {} (map (fn [[name _]] [name (ids/slot-id type-id name)])) (:type record))
           (into {} (map (juxt :name :id) (:fields field)))))))


(deftest named-record-pin-keeps-route-and-original-field-memberships
  (doseq [[file name host alias-name default]
          [["packages/app/ui-inspector/fns.edn" :_inspector-usages-state :_usages-query :state {:value ""}]
           ["packages/app-base/prefs/fns.edn" :_ui-pref-row-shape :_ui-pref-key :row {:value nil}]
           ["packages/app/ui-authoring/fns.edn" :_namespace-choice :_choice-key :choice {}]]]
    (let [{:keys [prepared owner record-id record use-entry]} (package-record-pin file name host)
          report (snapshot/projection-readiness prepared owner @roots)
          [route] (:projections report)
          evidence ((named-types/lookup prepared (constantly record)) name)
          {:keys [body rows]} evidence
          member-ids (into {} (comp (filter #(= :fn-slot (:kind %))) (map (juxt :slot-id :id))) rows)
          get-id (ids/fn-id "core.collections" :get)
          default-id (ids/slot-id get-id :default)
          default-row (some #(when (= [default-id] (:target-path %)) %) (get-in use-entry [:v2 :bindings]))
          target (ids/slot-id get-id :coll)]
      (is (= :authored-source-equivalent (:verification use-entry)))
      (is (= default (select-keys default-row [:value])))
      (if (contains? default :value)
        (is (= [(ids/binding-id owner default-id) "value"] [(:id default-row) (:expression-kind default-row)]))
        (is (nil? default-row)))
      (is (= :incomplete (:status report)) (pr-str (:diagnostics report)))
      (is (some #(= :conflicting-runtime-obligations (:code %)) (:diagnostics report)))
      (is (= (ids/binding-id owner target) (:binding-id route)))
      (is (= [[owner []] [(ids/slot-id owner alias-name)]] (:source route)))
      (is (= (get-in record [:source :type]) body (:actual-type route)))
      (is (= (count body) (count member-ids)))
      (doseq [field (keys body) :let [id (ids/slot-id record-id field)]]
        (is (= (ids/fn-slot-id record-id id) (member-ids id))))
      (let [missing (assoc-in record [:v2 :declaration-records] [])]
        (is (= :named-record-evidence-required (:code (failure #((named-types/lookup prepared (constantly missing)) name))))))
      (let [[field type] (first body)]
        (doseq [unsupported [{:type type :required true} {:nested type}]]
          (let [changed (assoc-in record [:v2 :source :type field] unsupported)]
            (is (= :named-record-evidence-required
                   (:code (failure #((named-types/lookup prepared (constantly changed)) name))))))))
      (doseq [[kind mutate-row] [[:fn #(assoc % :expects-effects #{:db})]
                                 [:fn-slot #(update % :position inc)]
                                 [:fn-slot #(assoc % :fn-id owner)]
                                 [:slot #(assoc % :type-fn-id (ids/primitive-fn-id :null))]
                                 [:slot #(assoc % :required false)]]]
        (let [changed (update-in record [:v2 :declaration-records]
                                 (partial mapv (fn [row] (if (= kind (:kind row)) (mutate-row row) row))))]
          (is (= :named-record-evidence-required
                 (:code (failure #((named-types/lookup prepared (constantly changed)) name))))))))))


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
    (is (some #(= :conflicting-runtime-obligations (:code %)) (:diagnostics report)))
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
