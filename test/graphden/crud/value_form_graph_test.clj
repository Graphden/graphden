(ns graphden.crud.value-form-graph-test
  "Descriptor and renderer policy through the real app.forms graph, including
   the production value-form endpoint and canonical registry overrides."
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.config :as config]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- graph-call
  [name args]
  (exec/execute-by-name harness/*context* name args))


(defn- resolve-form
  ([t] (graph-call "resolve-form" {:type-expr t}))
  ([t depth] (graph-call "_descriptor-step"
                         {:input {:type t :depth depth} :self identity})))


(defn- build-form
  [_ctx form path id value]
  (if (and (= "" path) (nil? id))
    (graph-call "build-form" {:form form :current-value value})
    (throw (ex-info "Use the public root renderer" {}))))


(defn- build-enum-control
  [path id {:keys [base members]}]
  (graph-call "_control-enum-view" {:type-expr [:refine base [:in members]] :form-path path :id id}))


(defn- type-label
  [t]
  (graph-call "_form-type-label" {:type-expr t}))


(defn- build-leaf-form
  [_ctx t path id]
  (graph-call "_form-build-leaf" {:type-expr t :form-path path :id id}))


(defn- in-tree?
  [form x]
  (boolean (some #(= x %) (tree-seq coll? seq form))))


(deftest build-form-composites
  (let [ctx harness/*context*]
    (testing "a record descriptor becomes a labelled fieldset"
      (let [f (build-form ctx (resolve-form {:host :text :port :int})
                          "" nil {:host "h" :port 8080})]
        (is (in-tree? f "value-form-group"))
        (is (in-tree? f "value-form-field"))
        (is (in-tree? f "host"))
        (is (in-tree? f "port"))))
    (testing "a union descriptor renders a branch <select> plus branches,
              pre-selecting the branch the current value fits"
      (let [int-fit  (build-form ctx (resolve-form [:union :int :text])
                                 "" nil 5)
            text-fit (build-form ctx (resolve-form [:union :int :text])
                                 "" nil "hello")]
        (is (in-tree? int-fit "value-form-union"))
        (is (= "0" (get (nth int-fit 1) "data-union-active")))
        (is (= "1" (get (nth text-fit 1) "data-union-active")))
        (is (in-tree? int-fit "value-form-union-branch"))))
    (testing "a list descriptor falls back to a JSON editor"
      (is (= "textarea"
             (first (build-form ctx (resolve-form [:list :int])
                                "" nil [1 2])))))
    (testing "a leaf descriptor delegates to build-leaf-form"
      (is (= "input"
             (first (build-form ctx (resolve-form :int) "" nil 7)))))
    (testing "a composite type with an EXACT registry row skips
              decomposition — :hiccup-node renders the single EDN
              textarea, not a 6-branch union editor"
      (let [f (build-form ctx (resolve-form :hiccup-node)
                          "" nil ["div" {"class" "x"} "hi"])]
        (is (= "textarea" (first f)))
        ;; The const's attr keys keywordize on the JSONB round trip
        ;; (they re-stringify in the JSON response the editor reads).
        (is (= "edn" (get (second f) :data-field-kind))
            "hiccup-node dispatches to the EDN textarea")
        (is (not (in-tree? f "value-form-union"))
            "no branch selector for a registered composite")))
    (testing "the exact tier does not swallow an UNregistered union —
              structural decomposition still applies"
      (is (in-tree? (build-form ctx (resolve-form [:union :int :text])
                                "" nil 5)
                    "value-form-union")))))


;; =============================================================================
;; POST /api/value-form — the production `:value-form-handler`
;; =============================================================================

(defn- value-form!
  "POST `body` to `:value-form-handler`; the decoded JSON envelope."
  [body]
  (let [graph {:ctx harness/*context*
               :all-name->id {:value-form-handler (harness/fn-id "value-form-handler")
                              :pg-query (harness/fn-id "pg-query")}}
        resp (setup/via-graph graph :value-form-handler
                              {:uri "/api/value-form" :request-method :post
                               :headers {"content-type" "application/json"}
                               :body (cheshire/generate-string body)})]
    (cheshire/parse-string (str (:body resp)) true)))


(defn- bound-int-slot!
  "A base fn with an `:int` slot bound to 5. Returns the binding row."
  []
  (let [storage harness/*storage*
        slot (setup/create-slot! storage "n" :int)
        owner (setup/create-base-fn! storage (str "vfh-owner-" (random-uuid)))]
    (setup/bind-value! storage (:id owner) (:id slot) 5)))


(deftest value-form-handler-validation-test
  (testing "an empty request is rejected"
    (is (false? (:ok (value-form! {})))))
  (testing "fn-id without slot-id is rejected"
    (is (false? (:ok (value-form! {:fn-id (str (random-uuid))})))))
  (testing "a malformed binding-id parses to nil — rejected like an absent one"
    (is (false? (:ok (value-form! {:binding-id "not-a-uuid"})))))
  (testing "fn-id + slot-id together identify an unbound free-arg"
    (is (true? (:ok (value-form! {:fn-id (str (random-uuid))
                                  :slot-id (str (random-uuid))}))))))


(deftest value-form-handler-bound-slot-test
  (testing "a bound :int slot yields a number control wrapped in a
            data-form-root div carrying the binding id"
    (let [b (bound-int-slot!)
          {:keys [ok value form]} (value-form! {:binding-id (str (:id b))})
          [tag attrs control] form]
      (is (true? ok))
      (is (= 5 value))
      (is (= "div" tag))
      (is (contains? attrs :data-form-root))
      (is (= (str (:id b)) (:data-binding-id attrs)))
      (is (in-tree? control "number")))))


(deftest value-form-handler-as-test
  (testing "`as` (the editor's \"as:\" chooser) picks the form's type"
    (let [b (bound-int-slot!)
          as-text (value-form! {:binding-id (str (:id b)) :as "text"})
          [_ _ control] (:form as-text)]
      (is (true? (:ok as-text)))
      (is (in-tree? control "text") "a text control, not the slot's number one")))
  (testing "a blank or non-string `as` is dropped — the slot's own type wins"
    (let [b (bound-int-slot!)]
      (doseq [as ["" 7]]
        (let [[_ _ control] (:form (value-form! {:binding-id (str (:id b)) :as as}))]
          (is (in-tree? control "number") (str "as " (pr-str as))))))))


(deftest color-form-is-selected-by-refinement
  (let [form (build-form harness/*context* (resolve-form :color) "" nil "#abcd")]
    (is (in-tree? form "color"))
    (is (in-tree? form "HEX color"))
    (is (in-tree? form "Alpha (0–255)"))
    (is (some #(and (map? %) (contains? % "data-form-type")) (tree-seq coll? seq form)))))


(defn- bound-theme-slot!
  []
  (let [storage harness/*storage*
        slot (setup/create-slot! storage "theme" (harness/fn-id "_account-menu-theme"))
        owner (setup/create-base-fn! storage (str "vfh-theme-" (random-uuid)))]
    (setup/bind-value! storage (:id owner) (:id slot)
                       {:accent "#7c3aed" :canvas-background "#f5f3ff"})))


(defn- color-field-paths
  [form]
  (->> (tree-seq coll? seq form)
       (filter vector?)
       (keep (fn [node]
               (let [attrs (second node)]
                 (when (= "color" (:data-form-widget attrs))
                   (:data-field-path attrs)))))
       set))


(deftest value-form-handler-record-field-paths-test
  (let [binding (bound-theme-slot!)
        {:keys [ok form]} (value-form! {:binding-id (str (:id binding))})]
    (is (true? ok))
    (is (= #{"accent" "canvas-background"} (color-field-paths form)))))


(deftest value-form-route-binds-handler-test
  (let [binding (bound-theme-slot!)
        route (graph-call "api-value-form" {})
        handler (get-in (second route) ["post" "handler"])]
    (is (= "/value-form" (first route)))
    (is (ifn? handler))
    (let [response (handler {:uri "/api/value-form" :request-method :post
                             :headers {"content-type" "application/json"}
                             :body (cheshire/generate-string {:binding-id (str (:id binding))})})
          result (cheshire/parse-string (str (:body response)) true)]
      (is (true? (:ok result)))
      (is (= #{"accent" "canvas-background"}
             (color-field-paths (:form result)))))))


(deftest resolve-form-leaf-test
  (testing "primitives classify as leaf"
    (is (= {:kind :leaf :type :int} (resolve-form :int)))
    (is (= {:kind :leaf :type :text} (resolve-form :text))))
  (testing "a refinement is a single leaf — it dispatches to ONE form-fn"
    (is (= {:kind :leaf :type [:refine :int [:> 0]]}
           (resolve-form [:refine :int [:> 0]])))))


(deftest resolve-form-list-test
  (is (= {:kind :list :type [:list :int] :element {:kind :leaf :type :int}}
         (resolve-form [:list :int]))))


(deftest resolve-form-record-test
  (let [r (resolve-form {:host :text :port :int})]
    (is (= :record (:kind r)))
    (is (= {:host :text :port :int} (:type r))
        "composite descriptor carries its resolved type for exact dispatch")
    (is (= #{:host :port} (set (map :name (:fields r)))))
    (let [host (first (filter #(= :host (:name %)) (:fields r)))]
      (is (= {:kind :leaf :type :text} (:form host))))))


(deftest resolve-form-union-test
  (let [u (resolve-form [:union :int :text])]
    (is (= :union (:kind u)))
    (is (= [:union :int :text] (:type u))
        "composite descriptor carries its resolved type for exact dispatch")
    (testing "each branch keeps its raw type AND its resolved form"
      (is (= #{:int :text} (set (map :type (:branches u)))))
      (is (= #{{:kind :leaf :type :int} {:kind :leaf :type :text}}
             (set (map :form (:branches u))))))))


(deftest resolve-form-nested-test
  (testing "record field that is itself a list recurses"
    (let [r (resolve-form {:tags [:list :text]})
          tags (first (:fields r))]
      (is (= :record (:kind r)))
      (is (= {:kind :list :type [:list :text] :element {:kind :leaf :type :text}}
             (:form tags))))))


(deftest resolve-form-variant-test
  (testing "a tagged variant desugars to a union of record branches"
    (let [r (resolve-form [:variant :ok :text :err :text])]
      (is (= :union (:kind r)))
      (is (= 2 (count (:branches r))))
      (is (every? #(= :record (:kind (:form %))) (:branches r))))))


(deftest resolve-form-depth-guard-test
  (testing "past the recursion-depth guard any type collapses to a leaf"
    (is (= {:kind :leaf :type {:host :text}}
           (resolve-form {:host :text} 13)))))


(deftest build-enum-control-test
  (testing "a keyword-base enum renders colon-prefixed option values"
    (let [sel (build-enum-control "" nil {:base :keyword :members ["get" "post"]})]
      (is (= "select" (first sel)))
      (is (= {"class" "arg-value-edit-input"
              "data-form-field" ""
              "data-field-kind" "enum"}
             (second sel)))
      (is (= [["option" {"value" ":get"} ":get"]
              ["option" {"value" ":post"} ":post"]]
             (drop 2 sel)))))
  (testing "keyword members already colon-prefixed are left alone"
    (is (= ["option" {"value" ":get"} ":get"]
           (nth (build-enum-control "" nil {:base :keyword :members [:get]}) 2))))
  (testing "a non-keyword base keeps member values verbatim"
    (is (= [["option" {"value" "a"} "a"] ["option" {"value" "b"} "b"]]
           (drop 2 (build-enum-control "" nil {:base :text :members ["a" "b"]})))))
  (testing "path and id are threaded onto the <select>"
    (let [attrs (second (build-enum-control "status" "vf-status"
                                            {:base :text :members ["x"]}))]
      (is (= "status" (get attrs "data-field-path")))
      (is (= "vf-status" (get attrs "id"))))))


(deftest type-label-test
  (testing "a primitive shows its name"
    (is (= "int" (type-label :int))))
  (testing "a refinement shows its BASE — not the bare word \"refine\""
    (is (= "int" (type-label [:refine :int [:> 0]]))))
  (testing "a list shows [elem]"
    (is (= "[text]" (type-label [:list :text]))))
  (testing "composites get a short word"
    (is (= "record" (type-label {:a :int})))
    (is (= "fn" (type-label [:fn {} :int]))))
  (testing "any other constructor vector shows its head keyword"
    (is (= "union" (type-label [:union :int :text])))
    (is (= "map" (type-label [:map :keyword :int]))))
  (testing "an unclassifiable value falls back to pr-str"
    (is (= "5" (type-label 5)))))


(deftest build-leaf-form-test
  (let [ctx harness/*context*]
    (testing "a primitive picks its registered form-fn control"
      (let [c (build-leaf-form ctx :int "" nil)]
        (is (= "input" (first c)))
        (is (in-tree? c "number"))))
    (testing "a type with no registered form-fn falls back to _form-json"
      (is (= "textarea" (first (build-leaf-form ctx :uuid "" nil)))))
    (testing "a bounded numeric refinement threads HTML min / max"
      (let [c (build-leaf-form ctx [:refine :int [:and [:>= 1] [:<= 65535]]]
                               "" nil)]
        (is (= 1 (get (second c) "min")))
        (is (= 65535 (get (second c) "max")))))
    (testing "a closed-enum refinement renders a <select>, not an input"
      (let [c (build-leaf-form ctx [:refine :keyword [:in [:get :post]]]
                               "" nil)]
        (is (= "select" (first c)))
        (is (= "enum" (get (second c) "data-field-kind")))))
    (testing "a non-empty path is threaded onto the control"
      (is (= "host" (get (second (build-leaf-form ctx :int "host" nil))
                         "data-field-path"))))))


(deftest resolve-form-text-alias-test
  (testing "a type NAME as a string (the editor's \"as:\" chooser) classifies like the keyword"
    (is (= (resolve-form :text) (resolve-form "text")))
    (is (= :leaf (:kind (resolve-form "int"))))))


(deftest control-attribute-assembly-test
  (let [controls [["attrs" ["input" {"type" "text" "id" "old"}] ["input" {:type "text" :id "old" "id" "new" "data-form-type" "\"int\""}]]
                  ["missing" ["input"] ["input" {"id" "new" "data-form-type" "\"int\""}]]
                  ["child" ["span" "txt"] ["span" {"id" "new" "data-form-type" "\"int\""} "txt"]]
                  ["scalar" "x" "x"]]]
    (harness/sync! (mapv (fn [[suffix control _]]
                           {:name (keyword (str "form-test-" suffix)) :parent :const
                            :args {:value {:value control}}}) controls))
    (doseq [[suffix _ expected] controls]
      (is (= expected (graph-call "_control-merged"
                                  {:target (str "form-test-" suffix)
                                   :type-expr :int :form-path "" :id "new"}))))))


(deftest selected-form-target-preserves-effect-denial
  (let [failure (try
                  (exec/execute-by-name (assoc harness/*context* :allowed-effects #{:db})
                                        "_form-run-target" {:target "current-time-ms"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (= :execution/forbidden-effect (:type (ex-data failure)))
        "Selecting a form must not grant the target additional effects")))


(deftest recursive-form-policy-test
  (testing "deep input stops at exactly the original depth boundary"
    (let [deep (nth (iterate #(vector :list %) :int) 20)
          descriptor (resolve-form deep)
          chain (take-while some? (iterate :element descriptor))]
      (is (= 14 (count chain)))
      (is (= :leaf (:kind (last chain))))))
  (testing "record recursion preserves false and string-keyed values"
    (let [type-expr {:outer {:choice [:union :text :bool]}}
          form (build-form harness/*context* (resolve-form type-expr)
                           "" nil {"outer" {"choice" false}})
          nodes (filter sequential? (tree-seq coll? seq form))
          union (first (filter #(= "value-form-union" (get (second %) "class")) nodes))
          branches (filter #(= "value-form-union-branch" (get (second %) "class")) nodes)]
      (is (= "0" (get (second union) "data-union-active")))
      (is (= [nil "hidden"] (mapv #(get (second %) "hidden") branches)))
      (is (in-tree? form "outer.choice"))
      (is (false? (graph-call "_render-field-value"
                              {:field {:name :choice}
                               :render-state {:value {:choice false "choice" "fallback"}}}))))))


;; Policy is exercised through the actual stored graph, including nested HOFs.
(defn- pick-form-fn
  [registry type]
  (graph-call "form-registry-target" {:registry registry :type-expr type}))


(defn- exact-form-fn
  [registry type]
  (graph-call "_form-exact-target" {:registry registry :type-expr type}))


(defn- collect-bounds
  [constraint integer-base?]
  (graph-call "_bounds-collected" {:type-expr [:refine (if integer-base? :int :float) constraint]}))


(defn- numeric-bounds
  [type]
  (graph-call "_form-bounds" {:type-expr type}))


(defn- enum-of
  [type]
  (graph-call "_form-enum" {:type-expr type}))


(defn- value-fits?
  [value type]
  (graph-call "_form-value-fits?" {:value value :type-expr type}))


;; ============================================================================
;; pick-form-fn — registry dispatch by subtype, most-specific wins
;; ============================================================================

(def ^:private registry
  [[:text "_form-text"] [:int "_form-number"]
   [:numeric "_form-number"] [:any "_form-json"]])


(deftest pick-form-fn-test
  (testing "the most-specific accepting type wins over wider supertypes"
    (is (= "_form-number" (pick-form-fn registry :int)))
    (is (= "_form-text" (pick-form-fn registry :text))))
  (testing "a refinement dispatches via its base type"
    (is (= "_form-number" (pick-form-fn registry [:refine :int [:> 0]]))))
  (testing "no specific match falls through to the :any entry"
    (is (= "_form-json" (pick-form-fn registry :bool))))
  (testing "an empty registry yields nil"
    (is (nil? (pick-form-fn [] :int)))))


(deftest pick-form-fn-marker-typed-slot-test
  ;; A hide-result marker type dispatches to the secret-binding
  ;; widget via a VECTOR registry key — [:secret :any] accepts any
  ;; [:secret T] leaf and outranks the :any fallback. The editor JS
  ;; routes purely on the widget name in the response; no tag names
  ;; live client-side.
  (let [reg [[:text "_form-text"] [:any "_form-json"]
             [[:secret :any] "_form-secret-binding"]]]
    (is (= "_form-secret-binding" (pick-form-fn reg [:secret :text]))
        "marker leaf picks the widget row over :any")
    (is (= "_form-text" (pick-form-fn reg :text))
        "plain text unaffected")))


(deftest exact-form-fn-test
  ;; The pre-decomposition tier: a form-fn registered for a whole
  ;; composite type (the hiccup-node union) wins over structural
  ;; decomposition — but ONLY on exact structural equality, so the
  ;; generic :any / :jsonb rows can never swallow record / union
  ;; editors.
  (let [hiccup-union [:union :null :text :int :float :bool [:list :any]]
        reg [[:text "_form-text"] [:any "_form-json"]
             [hiccup-union "_form-hiccup"]]]
    (testing "the exact composite row matches"
      (is (= "_form-hiccup" (exact-form-fn reg hiccup-union))))
    (testing "a member-order permutation still matches — the rich-types
              registry canonicalizes unions in sorted order"
      (is (= "_form-hiccup"
             (exact-form-fn reg [:union :bool :float :int :null :text
                                 [:list :any]]))))
    (testing "a merely-compatible type does NOT match — no subtype fallback"
      (is (nil? (exact-form-fn reg [:union :null :text])))
      (is (nil? (exact-form-fn reg {:host :text}))))
    (testing "generic rows only match themselves"
      (is (= "_form-json" (exact-form-fn reg :any)))
      (is (nil? (exact-form-fn [[:any "_form-json"]] [:union :int :text]))))))


(deftest pick-form-fn-js-source-prefers-textarea-over-text-input-test
  ;; Block 3.3 — when a slot declares `:js-source`, the registry's
  ;; `js-source -> _form-js-source` entry must outrank the wider
  ;; `text -> _form-text` entry. Pin this here so a future
  ;; registry reorder / rename doesn't silently downgrade JS-body
  ;; editing to a single-line input.
  (let [reg [[:text "_form-text"] [:js-source "_form-js-source"]]]
    (is (= "_form-js-source" (pick-form-fn reg :js-source))
        "the textarea widget wins for the dedicated alias")
    (is (= "_form-text" (pick-form-fn reg :text))
        "the single-line widget still wins for plain :text")))


;; ============================================================================
;; collect-bounds / numeric-bounds — refinement → HTML min/max
;; ============================================================================

(deftest collect-bounds-test
  (testing "inclusive bounds pass through"
    (is (= {:min 1} (collect-bounds [:>= 1] true)))
    (is (= {:max 100} (collect-bounds [:<= 100] true))))
  (testing "exclusive bounds nudge by one for an integer base"
    (is (= {:min 1} (collect-bounds [:> 0] true)))
    (is (= {:max 99} (collect-bounds [:< 100] true))))
  (testing "exclusive bounds pass through unchanged for a real base"
    (is (= {:min 0} (collect-bounds [:> 0] false)))
    (is (= {:max 100} (collect-bounds [:< 100] false))))
  (testing "an :and constraint collects both ends"
    (is (= {:min 1 :max 65535}
           (collect-bounds [:and [:>= 1] [:<= 65535]] true))))
  (testing "non-range constraints contribute nothing"
    (is (= {} (collect-bounds [:= 5] true)))
    (is (= {} (collect-bounds [:in [1 2 3]] true)))
    (is (= {} (collect-bounds :not-a-vector true)))))


(deftest numeric-bounds-test
  (testing "a bounded integer refinement yields min + max"
    (is (= {:min 1 :max 65535}
           (numeric-bounds [:refine :int [:and [:>= 1] [:<= 65535]]]))))
  (testing "a non-numeric base yields nil"
    (is (nil? (numeric-bounds [:refine :text [:= "x"]]))))
  (testing "a plain type (not a refinement) yields nil"
    (is (nil? (numeric-bounds :int))))
  (testing "a refinement with no range bounds yields nil"
    (is (nil? (numeric-bounds [:refine :int [:in [1 2]]])))))


;; ============================================================================
;; enum-of — closed-enum detection
;; ============================================================================

(deftest enum-of-test
  (testing "a closed-enum refinement is recognised"
    (is (= {:base :keyword :members [:get :post]}
           (enum-of [:refine :keyword [:in [:get :post]]]))))
  (testing "a non-:in refinement is not an enum"
    (is (nil? (enum-of [:refine :int [:> 0]]))))
  (testing "a plain type is not an enum"
    (is (nil? (enum-of :int)))))


;; ============================================================================
;; value-fits? — union active-branch picker
;; ============================================================================

(deftest value-fits?-test
  (testing "a literal fits a branch it is a subtype of"
    (is (true? (boolean (value-fits? 5 :int))))
    (is (true? (boolean (value-fits? "x" :text)))))
  (testing "a literal does not fit an unrelated branch"
    (is (not (value-fits? "x" :int))))
  (testing "a nil value fits any branch (caller falls back to branch 0)"
    (is (true? (boolean (value-fits? nil :int)))))
  (testing "a literal satisfying a refinement fits it"
    (is (true? (boolean (value-fits? 8080 [:refine :int [:and [:>= 1] [:<= 65535]]]))))))


(deftest dispatch-tie-order-and-duplicate-types
  (is (= "first" (pick-form-fn [[:text "first"] [:text "second"]] :text)))
  (is (= "first" (exact-form-fn [[:text "first"] [:text "second"]] :text)))
  (is (= "integer-or-null"
         (pick-form-fn [[[:union :null :text] "text-or-null"]
                        [:any "fallback"]
                        [[:union :null :int] "integer-or-null"]] :null))))


(deftest shallow-bound-lists-do-not-consume-recursion-depth
  (binding [config/*max-recursion-depth* 8]
    (is (= {:min 1}
           (numeric-bounds [:refine :int (into [:and] (repeat 24 [:>= 1]))]))))
  (is (= {:min 1 :max 8}
         (numeric-bounds [:refine :int [:and [:>= 2] [:and [:> 4] [:<= 8]] [:>= 1]]]))))


(deftest bounds-child-invokes-the-captured-recursive-callable
  (let [calls (atom [])
        recur-fn (fn [input]
                   (swap! calls conj input)
                   {:min (second (:constraint input))})
        state {:int? true :recur recur-fn}]
    (is (identical? recur-fn (graph-call "_bounds-self" {:bounds-state state})))
    (is (= {:min 1} (graph-call "_bounds-child"
                                {:bounds-state state :item [:>= 1]})))
    (is (= [{:constraint [:>= 1] :int? true}] @calls))
    (reset! calls [])
    (is (= {:min 2} (graph-call "_bounds-step"
                                {:input {:constraint [:and [:>= 1] [:>= 2]] :int? true}
                                 :self recur-fn})))
    (is (= [{:constraint [:>= 1] :int? true}
            {:constraint [:>= 2] :int? true}] @calls))))
