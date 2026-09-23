(ns ^{:cost :heavy} graphden.packages.export-test
  "Round-trip tests for the graph → fns.edn exporter — the PURE layer, no
   database (the live-graph export is `export-graph-test`).

   1. `roundtrip-*` — hand-written fixtures covering every role +
      binding shape, asserting EXACT records-level round-trip
      (`parse(export(parse(fns))) == parse(fns)`).
   2. `corpus-fixpoint` — loads the real core/web/app packages and
      asserts the exporter reaches a stable fixpoint (the property
      publish / install relies on)."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.export :as export]
    [graphden.packages.loader :as loader]
    [graphden.packages.records :as records]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.wire :as wire]
    [graphden.types.core :as types]))


;; Both registries these tests touch are process-global; scope them to this
;; namespace's thread so the parallel runner's siblings neither see nor
;; perturb them. The secret fixture's `ex/vault-get` must be REGISTERED as
;; a hide-result resolver — the export's `hidden-resolver?` keys
;; secret-path emission on the resolver's registered return marker (by ID),
;; not on its name.
(use-fixtures :once
  (fn [t]
    (binding [types/*type-aliases-override* (atom {})]
      (exec/with-isolated-rich-types
        (fn []
          (registry/record-rich-types!
            (records/fn-id "ex" :vault-get)
            :vault-get
            {:args {:in {:type :text}} :return-type [:secret :text]})
          (t))))))


;; parse itself doesn't read the alias registry, but keep every test
;; hermetic (thread-local under the :once binding above).
(use-fixtures :each
  (fn [t] (types/clear-aliases!) (t) (types/clear-aliases!)))


(defn- norm
  "Records as an order-insensitive, key-order-insensitive set."
  [records]
  (set (map #(into (sorted-map) %) records)))


(defn- roundtrips-exactly?
  "True iff `fns` survives parse → export → parse with identical
   records."
  [fns]
  (= (norm (parse/parse-module fns))
     (norm (parse/parse-module (export/records->fn-defs (parse/parse-module fns))))))


(defn- diff-report
  [fns]
  (let [a (norm (parse/parse-module fns))
        b (norm (parse/parse-module (export/records->fn-defs (parse/parse-module fns))))]
    {:only-orig (remove b a) :only-rt (remove a b)}))


;; =============================================================================
;; Type-row roles
;; =============================================================================

(deftest roundtrip-type-rows
  (let [fns [{:name :user-shape :namespace "ex" :type {:nm :text :age :int} :description "u"}
             {:name :pos-int :namespace "ex" :refine {:base :int :constraint [:> 0]}}
             {:name :int-list :namespace "ex" :list :int}
             {:name :str-or-int :namespace "ex" :union [:text :int]}
             {:name :smap :namespace "ex" :map {:key :text :value :int}}
             {:name :pair :namespace "ex" :tuple [:text :int]}
             {:name :result :namespace "ex" :variant [:ok :int :err :text]}
             {:name :handler-t :namespace "ex" :fn-type [{:request :int} :text]}]]
    (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))))


;; =============================================================================
;; Base-fns + composed binding shapes
;; =============================================================================

(deftest roundtrip-base-and-composed
  (let [fns [{:name :pos-int :namespace "ex" :refine {:base :int :constraint [:> 0]}}
             {:name :resp :namespace "ex" :type {:status :int :body :text}}
             {:name :add :namespace "ex"
              :args {:a :int :b {:type :int :required false}} :return-type :int}
             {:name :collect :namespace "ex" :args {:items :sequence} :return-type :sequence}
             {:name :sink :namespace "ex" :args {:data :jsonb} :return-type :any}
             ;; literal value + ref + required-narrow
             {:name :add-10 :namespace "ex" :parent :add :args {:a {:value 10} :b :pos-int}}
             ;; PB' own-slot
             {:name :tmpl :namespace "ex" :parent :add :args {:a 1 :extra {:type :jsonb}}}
             ;; list-append: bare (closed nil) / closed true / not-closed
             {:name :seed-open :namespace "ex" :parent :collect :args {:items [1 2 3]}}
             {:name :seed-closed :namespace "ex" :parent :collect :args {:items {:append [1 2] :closed true}}}
             {:name :seed-nc :namespace "ex" :parent :collect :args {:items {:append [9]}}}
             ;; type-override on a value binding
             {:name :over :namespace "ex" :parent :sink :args {:data {:value {"k" 1} :type :resp}}}
             ;; multi-parent
             {:name :multi :namespace "ex" :parents [:collect :sink] :args {:items [7] :data {"a" 1}}}]]
    (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))))


;; =============================================================================
;; Renames (scalar / no-op / positional) + type-override-only bindings
;; =============================================================================

(deftest roundtrip-renames
  (let [fns [{:name :resp :namespace "ex" :type {:status :int :body :text}}
             ;; scalar rename with explicit type
             {:name :resp-ok :namespace "ex" :parent :resp
              :args {:body {:as :text-out :type :text} :status 200}}
             ;; no-op rename re-exposing a free arg (empty binding) +
             ;; type-override-only binding (must stay a binding, not a
             ;; PB' own-slot)
             {:name :resp-passthrough :namespace "ex" :parent :resp
              :args {:status {:as :status} :body {:as :body :type :text}}}
             ;; positional rename inside a list binding
             {:name :catfn :namespace "ex" :args {:colls :sequence} :return-type :sequence}
             {:name :cat-scripts :namespace "ex" :parent :catfn
              :args {:colls [{:as :scripts :type :sequence}]}}]]
    (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))))


;; =============================================================================
;; Inline structural / composite types + effects metadata
;; =============================================================================

(deftest roundtrip-inline-types-and-meta
  (let [fns [;; inline [:fn args ret] as a slot type (kept in :constraint)
             {:name :invoke :namespace "ex" :args {:f [:fn {:x :int} :int] :x :int} :return-type :int}
             {:name :double :namespace "ex" :parent :invoke :args {:x 2}}
             {:name :wrap :namespace "ex" :parent :invoke :args {:f :double :x 5}}
             ;; inline composite record as a slot type
             {:name :rec-slot :namespace "ex" :args {:cfg {:host :text :port :int}} :return-type :any}
             ;; effects + branch-local pass-through
             {:name :svc :namespace "ex" :args {:p :int} :return-type :any
              :expects-effects [:network :io] :branch-local? true}]]
    (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))))


;; =============================================================================
;; Marker-def type-rows + binding :terminal / :description annotations
;; =============================================================================

(deftest roundtrip-marker-def
  (testing "a `{:marker …}` declaration survives parse → export → parse
            (was silently exported as a bare `{:name …}`, losing the
            marker's hide-result flags)"
    (let [fns [{:name :pii :namespace "ex" :marker {:hide-result? true}}]]
      (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))
      (is (= {:marker {:hide-result? true} :name :pii :namespace "ex"}
             (first (export/records->fn-defs (parse/parse-module fns))))))))


(deftest roundtrip-binding-terminal-and-description
  (testing "binding-level `:terminal` / `:description` survive round-trip
            (both were dropped by the exporter's binding emission)"
    (let [fns [{:name :bp :namespace "ex" :args {:x :int :y :int :z :int} :return-type :int}
               {:name :src :namespace "ex" :args {:v :int} :return-type :int}
               {:name :child :namespace "ex" :parent :bp
                :args {;; value + terminal + description together
                       :x {:value 5 :terminal true :description "sealed"}
                       ;; terminal-only binding on an inherited slot
                       :y {:terminal true}
                       ;; ref + terminal (bare-ref form must promote to a map)
                       :z {:ref :src :terminal true}}}]]
      (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))
      (let [child (first (filter #(= :child (:name %))
                                 (export/records->fn-defs (parse/parse-module fns))))]
        (is (= {:value 5 :terminal true :description "sealed"} (get-in child [:args :x])))
        (is (= {:terminal true} (get-in child [:args :y])))
        (is (= {:ref :src :terminal true} (get-in child [:args :z])))))))


;; =============================================================================
;; Secret-path bindings — faithful round-trip + share-time stripping
;; =============================================================================

(def ^:private secret-fixture
  "A sink with a plain slot + a fn-def that vault-binds it. The binding
   form (not the slot's declared type) is what drives the round-trip —
   the `[:secret …]` marker lives in the rich-types registry, which the
   parse/export layer never consults."
  [{:name :vault-get :namespace "ex" :args {:in :text} :return-type :text}
   ;; ^ the row alone isn't enough for the export's hidden-resolver?
   ;; check — see the fixture-registration fixture below, which
   ;; records its rich-type with a [:secret :text] return (the export
   ;; classifies secret-path emission by the resolver's registered
   ;; hide-result marker, keyed by ID).
   {:name :sink :namespace "ex" :args {:password :text :sql :text} :return-type :int}
   {:name :db-call :namespace "ex" :parent :sink
    :args {:password {:secret-path "user-db/password"} :sql {:value "SELECT 1"}}}])


(deftest roundtrip-secret-path
  (testing "a {:secret-path …} binding survives parse → export → parse"
    (is (roundtrips-exactly? secret-fixture) (pr-str (diff-report secret-fixture))))
  (testing "parse stores the path as a :vault-get RESOLVER binding
            (the retired :override-kind marker is no longer written)"
    (let [recs (parse/parse-module secret-fixture)
          b (first (filter #(and (= :binding (:kind %))
                                 (= "user-db/password" (:value %)))
                           recs))]
      (is (some? b) "binding row with the path exists")
      (is (nil? (:override-kind b)))
      (is (some? (:resolver-fn-id b)))
      (is (true? (:value-present b)))))
  (testing "export emits {:secret-path …}, never a {:value <path>} literal"
    (let [out (export/records->fn-defs (parse/parse-module secret-fixture))
          db-call (first (filter #(= :db-call (:name %)) out))]
      (is (= {:secret-path "user-db/password"}
             (get-in db-call [:args :password]))
          "the regression this guards: the path silently degrading to a
           plain literal (broken secret + disclosed path) on re-import"))))


(deftest secret-path-detection-survives-name-duplication
  ;; Audit-3 regression: the old detection compared `ref-kw` output to
  ;; the BARE :vault-get — a same-named composed fn in ANY namespace
  ;; put "vault-get" into :dup-names, ref-kw qualified it, the `=`
  ;; missed, and vault PATHS leaked into bundles as generic resolver
  ;; forms that strip/manifest never saw. Detection is now keyed by
  ;; the resolver ID's registered hide-result marker.
  (let [dup-fixture [{:name :vault-get :namespace "ex"
                      :args {:in :text} :return-type :text}
                     {:name :sink :namespace "ex"
                      :args {:password :text :sql :text} :return-type :int}
                     ;; the name-thief: a same-named COMPOSED fn
                     {:name :vault-get :namespace "other.ns"
                      :parent :sink
                      :args {:password {:value "x"} :sql {:value "y"}}}
                     {:name :db-call :namespace "ex" :parent :sink
                      ;; explicit QUALIFIED resolver form — the sugar's
                      ;; own dup-safety is covered by its canonical-first
                      ;; resolution; here we pin the EXPORT side.
                      :args {:password {:resolver :ex/vault-get
                                        :value "user-db/password"}
                             :sql {:value "SELECT 1"}}}]
        out (export/records->fn-defs (parse/parse-module dup-fixture))
        db-call (first (filter #(= :db-call (:name %)) out))]
    (testing "the secret binding still emits the dedicated wire key"
      (is (= {:secret-path "user-db/password"}
             (get-in db-call [:args :password]))))
    (testing "strip and manifest still see it"
      (is (= [{:fn :db-call :arg :password}] (export/secret-path-args out)))
      (let [stripped (export/strip-secret-paths out)]
        (is (not (contains? (:args (first (filter #(= :db-call (:name %))
                                                  stripped)))
                            :password)))))))


(deftest strip-secret-paths-policy
  (let [out (export/records->fn-defs (parse/parse-module secret-fixture))]
    (testing "secret-path-args manifests every vault-path binding"
      (is (= [{:fn :db-call :arg :password}] (export/secret-path-args out))))
    (testing "strip removes the arg entry entirely — slot reverts to free"
      (let [stripped (export/strip-secret-paths out)
            db-call (first (filter #(= :db-call (:name %)) stripped))]
        (is (not (contains? (:args db-call) :password)))
        (is (= {:value "SELECT 1"} (get-in db-call [:args :sql]))
            "non-secret bindings untouched")))
    (testing "strip keeps a remainder when the map carried more than the path"
      (let [defs [{:name :x :namespace "ex" :parent :sink
                   :args {:password {:secret-path "p" :required true}}}]
            [stripped] (export/strip-secret-paths defs)]
        (is (= {:required true} (get-in stripped [:args :password])))))))


;; =============================================================================
;; Corpus fixpoint — the publish / install guarantee
;; =============================================================================

(deftest corpus-fixpoint
  (let [packages (loader/load-packages ["core" "web" "app" "registry" "mcp"])
        all-defs (vec (concat (map (fn [[nm d]] (assoc d :name nm)) (:base-fn-defs packages))
                              (:fn-defs packages)))
        sorted (deps/topological-sort all-defs)
        recs1 (parse/parse-module sorted)
        out1  (export/records->fn-defs recs1)
        recs2 (parse/parse-module out1)
        out2  (export/records->fn-defs recs2)
        recs3 (parse/parse-module out2)]
    (testing "round-trip preserves the record count (nothing gained/lost)"
      (is (= (count recs1) (count recs2)))
      (is (= (count recs2) (count recs3))))
    (testing "exporter is a fixpoint after the first round"
      (is (= (norm recs2) (norm recs3))
          "second round-trip must be bit-identical to the first")
      (is (= (set out1) (set out2))
          "exported EDN must be stable"))
    (testing "first-round normalisation stays within the documented tail"
      ;; Behaviour-preserving drift (anon-composite identity + HOF
      ;; owner-disambiguation) only; guard against silent ballooning.
      (let [diff (count (remove (norm recs2) (norm recs1)))]
        (is (< diff (* 0.05 (count recs1)))
            (str "first-round diff " diff " exceeded 5% — investigate a regression"))))))


(deftest roundtrip-resolver-binding
  (let [fns [{:name :rslv :namespace "ex" :args {:v :text} :return-type :text}
             {:name :sink2 :namespace "ex" :args {:x :text} :return-type :any}
             {:name :ruser :namespace "ex" :parent :sink2
              :args {:x {:resolver :rslv :value "stored"}}}]]
    (testing "a {:resolver …} binding survives parse → export → parse"
      (is (roundtrips-exactly? fns) (pr-str (diff-report fns))))
    (testing "export emits {:resolver …}, never a plain literal"
      (let [out (export/records->fn-defs (parse/parse-module fns))
            ruser (first (filter #(= :ruser (:name %)) out))]
        (is (= {:resolver :rslv :value "stored"}
               (get-in ruser [:args :x])))))))


(deftest roundtrip-list-and-resolver-binding-metadata
  ;; Regression: the compact list / resolver emissions dropped the
  ;; binding's type-override and `:required`, and a seal with no items
  ;; (`{:closed true}`) was not exported at all — each lost on
  ;; export → import and on registry publish → install.
  (let [fns [{:name :seqs :namespace "ex" :type {:x :any}}
             {:name :collect :namespace "ex"
              :args {:items {:type :sequence :required false}} :return-type :sequence}
             {:name :rslv :namespace "ex" :args {:v :text} :return-type :text}
             {:name :sink2 :namespace "ex"
              :args {:x {:type :any :required false}} :return-type :any}
             {:name :typed-list :namespace "ex" :parent :collect
              :args {:items {:append [1 2] :type :seqs}}}
             {:name :required-list :namespace "ex" :parent :collect
              :args {:items {:append [1] :closed true :required true}}}
             {:name :sealed-empty :namespace "ex" :parent :collect
              :args {:items {:closed true}}}
             {:name :typed-resolver :namespace "ex" :parent :sink2
              :args {:x {:resolver :rslv :value "stored" :type :text}}}]]
    (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))))


(deftest roundtrip-per-ns-duplicates
  ;; Stage 5: same-named fns in different namespaces round-trip — the
  ;; exporter emits QUALIFIED refs for duplicated names so re-parse
  ;; resolves precisely instead of hitting the ambiguity error.
  (let [fns [{:name :dup-base :namespace "ns-a" :args {:x :any} :return-type :any}
             {:name :same-name :namespace "ns-a" :parent :dup-base :args {:x {:value 1}}}
             {:name :same-name :namespace "ns-b" :parent :dup-base :args {:x {:value 2}}}
             {:name :caller :namespace "ns-c" :parent :dup-base
              :args {:x :ns-b/same-name}}]]
    (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))
    (testing "the exported caller carries the qualified ref"
      (let [out (export/records->fn-defs (parse/parse-module fns))
            caller (first (filter #(= :caller (:name %)) out))]
        (is (= :ns-b/same-name (get-in caller [:args :x])))))))


(deftest roundtrip-unspellable-ns-duplicates
  ;; Duplicated names living in namespaces that can't be spelled as
  ;; READABLE keyword namespaces: a version-materialized `@`-ns and the
  ;; ROOT (nil) ns. The exporter still qualifies — `(keyword
  ;; "lib@1-2-0.sub" n)` / `(keyword "" n)` are legal in-memory values —
  ;; and the parser resolves them through the always-qualified name→id
  ;; keys. Pre-fix the exporter fell back to a BARE ref that re-import
  ;; rejected as ambiguous or mis-resolved.
  (let [at-ns "lib@1-2-0.sub"
        fns [{:name :dup-base :namespace "ns-a" :args {:x :any} :return-type :any}
             {:name :same-name :namespace at-ns :parent :dup-base :args {:x {:value 1}}}
             {:name :same-name :namespace nil :parent :dup-base :args {:x {:value 2}}}
             {:name :caller-at :namespace "ns-c" :parent :dup-base
              :args {:x (keyword at-ns "same-name")}}
             {:name :caller-root :namespace "ns-c" :parent :dup-base
              :args {:x (keyword "" "same-name")}}]]
    (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))
    (testing "exported refs are the true qualified keywords, never bare"
      (let [out (export/records->fn-defs (parse/parse-module fns))
            arg-x (fn [n] (get-in (first (filter #(= n (:name %)) out)) [:args :x]))]
        (is (= (keyword at-ns "same-name") (arg-x :caller-at)))
        (is (= (keyword "" "same-name") (arg-x :caller-root)))))))


(deftest wire-edn-text-roundtrip
  ;; The EDN TEXT boundary: `pr-str` of an `@`-qualified or empty-ns
  ;; keyword is unreadable, so `encode-unreadable-kws` spells them
  ;; `#graphden/ref` and `wire-readers` decodes back to the SAME
  ;; keywords. Everything readable passes through untouched.
  (let [form {:fns [{:name :caller
                     :parent :dup-base
                     :args {:a (keyword "lib@1-2-0.sub" "same-name")
                            :b (keyword "" "same-name")
                            :c :ns-b/plain-qualified
                            :d :bare
                            :e {:ref (keyword "lib@1-2-0.sub" "other")}
                            :f [(keyword "lib@1-2-0" "in-vector") :bare-2]}}]}
        text (pr-str (wire/encode-unreadable-kws form))
        back (edn/read-string {:readers wire/wire-readers} text)]
    (testing "the printed text carries the tag, not the raw @ keyword"
      (is (str/includes? text "#graphden/ref \"lib@1-2-0.sub/same-name\""))
      (is (str/includes? text "#graphden/ref \"/same-name\""))
      (is (not (str/includes? text ":lib@"))))
    (testing "reading the text back restores the exact original form"
      (is (= form back)))
    (testing "plain EDN read of the text does NOT throw (tag is the only carrier)"
      (is (some? (edn/read-string {:readers wire/wire-readers} text))))))


;; --------------------------------------------------------------------------
;; constraint-type-ns — namespace-aware resolution of a constraint type-name.
;; Guards the dependency scan against last-write-wins misclassification of a
;; type-name that is DUPLICATED across namespaces (ADR-identity-model stage 5:
;; names are per-namespace). A plain `{name → ns}` index would pick whichever
;; ns happened to be indexed last; this resolver mirrors sync (own-ns wins,
;; else the sole ns, else prefer an external one so a dep is never dropped).
;; --------------------------------------------------------------------------

(deftest constraint-type-ns-is-namespace-aware
  (let [resolve* #'export/constraint-type-ns
        root "dup.pkg"
        ;; :widget defined in the owner's OWN ns AND externally; :mixed in an
        ;; internal sub-ns AND externally; :gadget only externally; :inside
        ;; only in a sub-ns; :solo only in the owner ns.
        name->nss {:widget #{"dup.pkg" "ext.lib"}
                   :mixed  #{"dup.pkg.sub" "ext.lib"}
                   :gadget #{"ext.lib"}
                   :inside #{"dup.pkg.sub"}
                   :solo   #{"dup.pkg"}}]
    (testing "the fn's OWN namespace wins over a same-named external sibling"
      ;; deterministic — a last-write-wins map could return \"ext.lib\" instead
      (is (= "dup.pkg" (resolve* name->nss root :widget "dup.pkg"))))
    (testing "a sole external definition resolves external (a real dep)"
      (is (= "ext.lib" (resolve* name->nss root :gadget "dup.pkg"))))
    (testing "a sole internal sub-ns definition resolves internal (not a dep)"
      (is (= "dup.pkg.sub" (resolve* name->nss root :inside "dup.pkg"))))
    (testing "duplicated across the subtree boundary, owner elsewhere → the EXTERNAL ns is preferred so the dep is never silently dropped"
      (is (= "ext.lib" (resolve* name->nss root :mixed "other.pkg"))))
    (testing "unknown / qualified names resolve to nil (classified non-external, as before)"
      (is (nil? (resolve* name->nss root :absent "dup.pkg"))))))
