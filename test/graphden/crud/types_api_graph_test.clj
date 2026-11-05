(ns ^:integration graphden.crud.types-api-graph-test
  "Graph-path tests for `/api/types/candidates` + `/api/types/usages` +
   `/api/types/compatible`.

   These exercise the production GRAPH fn-def chain that the HTTP
   handler reaches — the only implementation of the parse / validate
   stages and of the candidates enumeration (the Clojure copies that
   used to stand in for them in `types-api-test` are gone).

   Regression caught: Phase 5
   `apply-hof-translation` copying env-binding `rt/thunk`s to slot-id
   keys triggered a `call-with-cache` recursion on every
   `POST /api/types/candidates`. `bb test` was green because the
   Clojure-helper-based unit test bypassed the graph chain; smoke
   caught it only at `bb rebuild` against the running server. This
   ns pins the graph chain so the next class of regression surfaces
   in `bb test`."
  (:require
    [cheshire.core :as cheshire]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.type-check :as tc]
    [graphden.crud.types-api :as ta]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.graph-harness :as gh :refer [*graph*]]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


(use-fixtures :once
  (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*))))


(defn- post-via
  "POST `body` to `fn-name` via the executor — same path the production
   handler reaches. Returns the parsed JSON response body."
  [fn-name body]
  (let [response (setup/via-graph
                   *graph*
                   fn-name
                   {:uri (str "/api/" (name fn-name))
                    :request-method :post
                    :headers {"content-type" "application/json"}
                    :body (cheshire/generate-string body)})]
    (cheshire/parse-string (:body response) true)))


;; =============================================================================
;; /api/types/candidates — Phase 5 HOF translation regression coverage
;; =============================================================================

(deftest candidate-fit-graph-test
  (doseq [[expected arity fit]
          [['[:fn {:item a} b] 1 :exact]
           ['[:fn {:item a} b] 0 :ignores]
           ['[:fn {:item a} b] 3 :captures]
           ['[:fn {:acc a :item b} a] 2 :exact]
           [[:fn {} :any] 0 :exact]
           [[:fn {} :any] 2 :captures]
           [[:fn {:x :int} :text #{:db}] 1 :exact]
           [[:fn {:x :int} :text #{} #{:jvm-server}] 0 :ignores]
           [:fn-ref 5 :exact]
           [:text 0 :exact]
           [:text 2 :captures]]]
    (is (= fit (gh/exec-name :candidate-fit {:expected expected :arity arity}))
        (str expected " / " arity))))


(deftest candidate-fit-stored-contract-test
  (let [{:keys [storage all-name->id]} *graph*]
    (doseq [nm [:_candidate-fit-args :_candidate-fit-expected-arity
                :_candidate-fit-arity :_candidate-fit-identity?
                :_candidate-fit-exact? :_candidate-fit-captures?
                :candidate-fit]]
      (let [id (get all-name->id nm)]
        (is (some? (tc/reconstruct-fn-def storage id)))
        (is (nil? (tc/type-check-fn-after-mutation! storage id)) (str nm))))
    (let [rich (registry/rich-type-of-id (get all-name->id :candidate-fit))]
      (is (= (update-vals {:expected :type-expression :arity :non-negative-int}
                          types/resolve-alias)
             (:args rich)))
      (is (= #{} (:effects rich)))
      (is (types/subtype? (:return rich) :keyword)))))


(deftest types-candidates-expected-any-returns-non-empty-test
  (testing "POST /api/types/candidates {expected: 'any'} → {:ok true :count >0 :candidates [...]}"
    (let [result (post-via :types-candidates-handler {:expected "any"})]
      (is (true? (:ok result))
          "happy path returns ok=true; if this fails with a 500, the SO
           recursion is back — check Phase 5 HOF translation isn't copying
           env-binding thunks to slot-id keys")
      (is (pos? (:count result)))
      (is (vector? (:candidates result))))))


(deftest types-candidates-missing-expected-returns-validation-error-test
  (testing "POST /api/types/candidates {} → {:ok false :error 'Request body must include expected'}"
    (let [result (post-via :types-candidates-handler {})]
      (is (false? (:ok result)))
      (is (string? (:error result))))))


(deftest types-candidates-with-effects-filter-narrows-result-test
  (testing "effects=[] should narrow candidates to pure-only — smaller than the unfiltered set"
    (let [all-result (post-via :types-candidates-handler {:expected "any"})
          pure-result (post-via :types-candidates-handler
                                {:expected "any" :effects []})]
      (is (true? (:ok pure-result)))
      (is (<= (:count pure-result) (:count all-result))
          "pure subset ≤ all"))))


(deftest types-candidates-name-prefix-narrows-result-test
  ;; The name-prefix filter had NO test, which is how it came to be applied in
  ;; the wrong place: inside the per-row callback, AFTER every fn in the registry
  ;; had already been looked up, reshaped and recursively type-checked. The
  ;; endpoint therefore cost O(whole graph) even when the caller had narrowed the
  ;; answer to one fn — and the editor's type-picker almost always has. Measured
  ;; at 3945 fns: identical ~0.11 s whether the prefix matched 57 candidates or
  ;; 1, and 45 s under concurrent load, which is what timed out the type e2e
  ;; tests and got written off as GC pauses.
  ;;
  ;; The scan is now narrowed by prefix BEFORE the map. This test pins the
  ;; BEHAVIOUR that reordering must not change.
  (testing "name-prefix returns only fns whose name starts with it"
    (let [all-result (post-via :types-candidates-handler {:expected "any"})
          prefixed   (post-via :types-candidates-handler
                               {:expected "any" :name-prefix "str-"})]
      (is (true? (:ok prefixed)))
      (is (pos? (:count prefixed))
          "the seed graph has str-* fns; an empty result means the filter now
           runs against something other than the fn name")
      (is (< (:count prefixed) (:count all-result))
          "a prefix must narrow, not pass everything through")
      (is (every? #(str/starts-with? (name (:name %)) "str-")
                  (:candidates prefixed))
          "every returned candidate matches the prefix")))

  (testing "a prefix matching nothing returns an empty candidate set, not an error"
    (let [none (post-via :types-candidates-handler
                         {:expected "any"
                          :name-prefix "zzz-no-such-fn-prefix"})]
      (is (true? (:ok none)))
      (is (zero? (:count none)))
      (is (= [] (:candidates none)))))

  (testing "no prefix still enumerates the whole registry"
    (let [all-result (post-via :types-candidates-handler {:expected "any"})]
      (is (pos? (:count all-result))
          "omitting name-prefix must mean 'no filter', not 'match nothing'"))))


;; =============================================================================
;; /api/types/usages — same env-binding-thunk pattern, separate graph chain
;; =============================================================================

(deftest types-usages-missing-target-returns-validation-error-test
  (testing "POST /api/types/usages {} → {:ok false :error '...'}"
    (let [result (post-via :types-usages-handler {})]
      (is (false? (:ok result))))))


(deftest types-usages-accepts-fn-id-synonym-test
  (testing "POST {fn-id: <uuid>} parses — the body the /api/fns/usages
            alias (inspector Used-by) sends; an unknown id is an empty
            result, not a validation error"
    (let [result (post-via :types-usages-handler
                           {:fn-id (str (random-uuid))
                            :root-id (str (random-uuid))})]
      (is (true? (:ok result)))
      (is (zero? (:count result)))
      (is (map? (:reachability result)))
      (is (not-any? #(= :editor (:root-kind %))
                    (get-in result [:reachability :roots]))
          "a submitted root absent from the authorized graph grants no path"))))


;; =============================================================================
;; /api/types/compatible — env-bindings + nested validation chain
;; =============================================================================

(deftest types-compatible-int-subset-of-int-returns-ok-test
  (testing "POST /api/types/compatible {expected:'int' candidate:'int'} → {:ok true}"
    (let [result (post-via :types-compatible-handler
                           {:expected "int" :candidate "int"})]
      (is (true? (:ok result))))))


(deftest types-compatible-text-not-subset-of-int-returns-incompatible-test
  (testing "POST /api/types/compatible {expected:'int' candidate:'text'} → {:ok false :reason ...}"
    (let [result (post-via :types-compatible-handler
                           {:expected "int" :candidate "text"})]
      (is (false? (:ok result)))
      (is (some? (:reason result))))))


;; /api/types/compatible-many — one candidate, N expected, one request
;; (the wrap flow's slot check; it used to POST /compatible per slot).

(deftest types-compatible-many-answers-each-expected-in-order-test
  (testing "POST /api/types/compatible-many {candidate:'int' expected:[int text any [list int]]}"
    (let [result (post-via :types-compatible-many-handler
                           {:candidate "int"
                            :expected ["int" "text" "any" ["list" "int"]]})]
      (is (true? (:ok result)))
      (is (= [true false true false] (:results result))
          "same verdicts, same order, as four /compatible calls"))))


(deftest types-compatible-many-structural-candidate-test
  (testing "a structural candidate ([list text]) against named slot types"
    (let [result (post-via :types-compatible-many-handler
                           {:candidate ["list" "text"]
                            :expected [["list" "text"] ["list" "int"] "any"]})]
      (is (= [true false true] (:results result))))))


(deftest types-compatible-many-edge-cases-test
  (testing "no expected → empty results; no candidate → {:ok false :error}"
    (is (= [] (:results (post-via :types-compatible-many-handler {:candidate "int" :expected []}))))
    (let [r (post-via :types-compatible-many-handler {:expected ["int"]})]
      (is (false? (:ok r)))
      (is (string? (:error r))))))


(deftest types-candidates-fn-ref-slot-admits-every-fn-test
  ;; A `:fn-ref` slot takes a fn's IDENTITY — the bound fn is never run, so
  ;; its return type is no filter. The Clojure helper grew an `identity-slot?`
  ;; arm and its unit test went green while the GRAPH composition the editor
  ;; actually serves kept comparing `return ⊆ :fn-ref` and answered zero:
  ;; the lesson-37 picker showed "Compatible · 0" for a slot every fn fits.
  ;; This pins the graph path.
  (testing "expected 'fn-ref' enumerates the same set 'any' does"
    (let [any-result (post-via :types-candidates-handler {:expected "any"})
          ref-result (post-via :types-candidates-handler {:expected "fn-ref"})]
      (is (true? (:ok ref-result)))
      (is (pos? (:count ref-result)))
      (is (= (set (map :name (:candidates any-result)))
             (set (map :name (:candidates ref-result))))
          "no return-type filter for an identity slot")))

  (testing "the other filters still apply to an identity slot"
    (let [prefixed (post-via :types-candidates-handler
                             {:expected "fn-ref" :name-prefix "str-"})]
      (is (pos? (:count prefixed)))
      (is (every? #(str/starts-with? (name (:name %)) "str-")
                  (:candidates prefixed))))))


(deftest types-candidates-admits-ring-wrap-for-handler-slot-test
  (testing "the wraps a listener is built from are admitted for `:http-server`'s :handler"
    ;; Their `request` arg is the `:ring-request-shape` record. The DB-side
    ;; alias rebuild used to widen that record's union-typed `body` field to
    ;; `:any` (a slot can only name a type-row), and under contravariance the
    ;; wider slot-side request made every wrap "incompatible" in the picker
    ;; while the write path accepted the bind. The rebuild now keeps the
    ;; package-declared body for platform rows (types/core
    ;; `package-alias-bodies`).
    (let [expected ["fn" {:request "ring-request-shape"} "ring-response-shape"]
          result (post-via :types-candidates-handler {:expected expected})
          names (set (map :name (:candidates result)))]
      (is (true? (:ok result)))
      (is (contains? names "encode-stringify-wrap")
          (str "wraps with a `request` arg must be compatible; got " (pr-str names)))
      (is (contains? names "error-boundary-wrap")))))


;; =============================================================================
;; Validation arms not covered above
;; =============================================================================

(deftest types-compatible-missing-side-returns-validation-error-test
  (testing "a missing `expected` or `candidate` is rejected"
    (is (false? (:ok (post-via :types-compatible-handler {:candidate "int"}))))
    (is (false? (:ok (post-via :types-compatible-handler {:expected "int"}))))))


(deftest types-usages-malformed-target-returns-validation-error-test
  (testing "a type-fn-id that is not a uuid is rejected"
    (is (false? (:ok (post-via :types-usages-handler {:type-fn-id "not-a-uuid"}))))))


(deftest types-candidates-effects-filter-keeps-only-pure-rows-test
  (testing "effects=[] → every surviving candidate is pure"
    (let [res (post-via :types-candidates-handler {:expected "any" :effects []})]
      (is (true? (:ok res)))
      (is (every? #(empty? (:effects %)) (:candidates res))))))


;; =============================================================================
;; Candidate admission + ranking — stub signatures under a per-test prefix
;; (recorded into the fixture's isolated registry; the name-prefix filter
;; keeps each assertion to its own rows)
;; =============================================================================

(defn- stub-fns!
  "Record `sigs` (`{suffix rich-type}`) as registry entries named
   `<prefix><suffix>`. Returns the prefix."
  [sigs]
  (let [prefix (str "tcg" (subs (str (random-uuid)) 0 8) "-")]
    (doseq [[suffix rt] sigs]
      (registry/record-rich-types-raw! (random-uuid) (keyword (str prefix suffix))
                                       (merge {:effects #{}} rt)))
    prefix))


(defn- candidates
  "`{suffix candidate-row}` for `expected` (wire form) among `prefix`'s rows."
  [prefix expected]
  (let [res (post-via :types-candidates-handler {:expected expected :name-prefix prefix})]
    (is (true? (:ok res)) (pr-str res))
    (into {}
          (map (fn [c] [(subs (:name c) (count prefix)) c]))
          (:candidates res))))


(deftest types-candidates-rank-arity-and-fit-test
  (let [p (stub-fns! {"shout" {:return :text :args {:item :text}}
                      "motto" {:return :text :args {}}
                      "greet" {:return :text :args {:who :text :how :text}}})]
    (testing "every candidate of a 1-arg callable slot carries :arity and :fit"
      (let [by-name (candidates p ["fn" {:item "a"} "b"])]
        (is (= [1 "exact"] ((juxt :arity :fit) (by-name "shout"))))
        (is (= [0 "ignores"] ((juxt :arity :fit) (by-name "motto"))))
        (is (nil? (by-name "greet"))
            "a two-arg fn without an :item arg is not admitted to a one-arg slot")))
    (testing "a value slot ranks finished values :exact and templates :captures"
      (let [by-name (candidates p "text")]
        (is (= "exact" (:fit (by-name "motto"))))
        (is (= ["captures" 2] ((juxt :fit :arity) (by-name "greet"))))))))


(deftest types-candidates-callable-slot-admits-by-signature-test
  ;; A fn-typed slot receives the CALLABLE, not its result — admissibility
  ;; is "candidate signature ⊆ slot", the rule `check-binding!` applies on
  ;; write.
  (let [p (stub-fns! {"tick" {:return :text :args {}}
                      "counter" {:return :int :args {}}
                      "greeter" {:return :text :args {:who :text}}
                      "producer" {:return [:fn {} :text] :args {}}})]
    (testing "a zero-arity callable slot admits ordinary fns"
      (let [names (set (keys (candidates p ["fn" {} "any"])))]
        (is (contains? names "tick") "a plain `() → text` fn belongs in a `() → any` slot")
        (is (contains? names "greeter") "so does one with free args — hof-wrap captures them")
        (is (contains? names "producer") "and a callable-producing fn still qualifies")))
    (testing "the callable slot's return type still filters"
      (let [names (set (keys (candidates p ["fn" {} "text"])))]
        (is (contains? names "tick"))
        (is (not (contains? names "counter")) "`() → text` rejects a fn returning :int")))
    (testing "a 1-arg callable slot checks the argument contravariantly"
      (let [names (set (keys (candidates p ["fn" {:who "text"} "text"])))]
        (is (contains? names "greeter"))
        (is (not (contains? names "counter")))))))


(deftest types-candidates-polymorphic-slot-test
  (testing "a POLYMORPHIC callee fits a polymorphic slot over the wire"
    ;; `map`'s :func is `[:fn {:item a} b]`; a `(item:a) → [a]` callee and a
    ;; `(item:text) → text` one both belong in it.
    (let [p (stub-fns! {"echo" {:return '[:list a] :args '{:item a}}
                        "shout" {:return :text :args {:item :text}}
                        "pair" {:return :int :args {:x :int :y :int}}})
          names (set (keys (candidates p ["fn" {:item "a"} "b"])))]
      (is (contains? names "echo") "a generic callee is admitted")
      (is (contains? names "shout") "a concrete one-arg callee is admitted")
      (is (not (contains? names "pair"))
          "a two-arg callee with neither arg named :item does not fit a one-arg slot"))))


(deftest picker-policy-projects-after-canonical-filter-test
  ;; Supply only the acquisition boundary. The real stored graph must capture
  ;; expected in the predicate, retain order, and remove the internal reference.
  (let [entries [{:name "a/same" :kind "refinement" :type-ref :a/same}
                 {:name "b/same" :kind "refinement" :type-ref :b/same}
                 {:name "int" :kind "primitive" :type-ref :int}]
        reads (atom 0)]
    (binding [types/*type-aliases-override*
              (atom {:a/same [:refine :int [:>= 0]] :b/same :text})]
      (with-redefs [ta/type-name-entries (fn [_] (swap! reads inc) entries)]
        (is (= [{:name "a/same" :kind "refinement"}
                {:name "int" :kind "primitive"}]
               (gh/exec-name :compatible-type-names {:expected :int})))
        (is (= 1 @reads) "one acquisition, never one query per candidate")
        (is (= (mapv #(dissoc % :type-ref) entries)
               (gh/exec-name :type-name-kinds {})))
        (is (= [] (gh/exec-name :compatible-type-names {:expected :bool})))))))


(deftest rich-type-wire-projection-preserves-unknown-fields-test
  (let [omitted [:resolved-bindings :description :source-file :source-line
                 :tags :arg-effects :call-time-effects :primary-parent]
        full {:alpha (merge (zipmap omitted (repeat "metadata"))
                            {:return :int :args {} :effects #{} :future-field 42})
              :beta {:return [:list :text] :slot-types {:value :text}}}
        reads (atom 0)]
    (with-redefs [ta/all-rich-types (fn [_] (swap! reads inc) full)]
      (is (= (update-vals full #(apply dissoc % omitted))
             (gh/exec-name :api-rich-types {})))
      (is (= 1 @reads) "wire projection shares one scoped snapshot"))
    (with-redefs [ta/all-rich-types (constantly {})]
      (is (= {} (gh/exec-name :api-rich-types {}))))))


(deftest rich-type-wire-uses-production-snapshot-test
  (let [full (gh/exec-name :all-rich-types {})
        lean (gh/exec-name :api-rich-types {})
        omitted [:resolved-bindings :description :source-file :source-line
                 :tags :arg-effects :call-time-effects :primary-parent]]
    (is (seq full))
    (is (= (update-vals full #(apply dissoc % omitted)) lean))))


(defn- check-stored-type-policies!
  []
  (let [{:keys [storage all-name->id]} *graph*]
    ;; Dependency order matters: ordinary golden clones intentionally do not
    ;; run the package sweep. Check the persisted definitions, not source copies.
    (doseq [nm [:_type-name-wire-row :_type-name-compatible?
                :_type-name-compatible-entries :type-name-kinds
                :compatible-type-names :_rich-type-wire-entry :api-rich-types]]
      (let [id (get all-name->id nm)]
        (is (some? (tc/reconstruct-fn-def storage id)) (str nm " is composed"))
        (is (nil? (tc/type-check-fn-after-mutation! storage id))
            (str nm " checks after storage reconstruction"))))))


(deftest stored-type-policies-have-precise-contracts-test
  (check-stored-type-policies!)
  (doseq [[nm args return]
          [[:type-name-kinds {} [:list {:name :text :kind :text}]]
           [:compatible-type-names {:expected :type-expression}
            [:list {:name :text :kind :text}]]
           [:api-rich-types {} :keyword-map]]]
    (let [rich (registry/rich-type-of-id (get (:all-name->id *graph*) nm))]
      (is (= (update-vals args types/resolve-alias) (:args rich))
          (str nm " exposes only its public inputs"))
      (is (types/subtype? (:return rich) return) (str nm " retains its public return"))
      (is (= #{:db} (:effects rich)) (str nm " retains scoped-read effects")))))


(deftest secret-expected-cannot-enter-plain-picker-test
  (check-stored-type-policies!)
  (let [source (keyword (gh/uniq "secret-picker-expected"))
        consumer (keyword (gh/uniq "secret-picker-selection"))]
    (registry/record-rich-types! source
                                 {:args {} :return-type [:secret :type-expression]})
    (is (thrown? clojure.lang.ExceptionInfo
          (check/check-fn-def! {:name consumer :parent :compatible-type-names
                                :args {:expected {:ref source}}}))
        "A secret cannot be bound to the public picker input")))


(deftest picker-identity-and-acl-survive-the-complete-graph-test
  (let [{:keys [storage ctx]} *graph*
        stem (gh/uniq "picker-type")
        left (sp/create-entity storage :ns {:name (gh/uniq "picker-a")})
        hidden (sp/create-entity storage :ns {:name (gh/uniq "picker-z")})
        int-id (get setup/primitive-fn-ids :int)
        text-id (get setup/primitive-fn-ids :text)
        root (sp/create-entity storage :fn
                               {:name stem :base-fn-id int-id :constraint [:>= 0]})
        twin (sp/create-entity storage :fn
                               {:name stem :namespace-id (:id left)
                                :base-fn-id text-id :constraint [:not= ""]})
        concealed (sp/create-entity storage :fn
                                    {:name stem :namespace-id (:id hidden)
                                     :base-fn-id int-id :constraint [:>= 1]})
        ids (mapv :id [root twin concealed])
        root-name (str "/" stem)
        left-name (str (:name left) "/" stem)
        hidden-name (str (:name hidden) "/" stem)]
    (try
      (exec-ctx/invalidate-graph-cache! ctx ids)
      (binding [types/*type-aliases-override* (atom (types/aliases-snapshot))
                tenancy/*seams-override*
                (atom {:graph-read-filter-fn
                       #(update % :fns
                                (fn [rows] (filterv (fn [row] (not= (:id concealed) (:id row))) rows)))})]
        (types/register-type-alias! (keyword "" stem) [:refine :int [:>= 0]])
        (types/register-type-alias! (keyword (:name left) stem) [:refine :text [:not= ""]])
        (types/register-type-alias! (keyword (:name hidden) stem) [:refine :int [:>= 1]])
        (let [all (gh/exec-name :type-name-kinds {})
              compatible (gh/exec-name :compatible-type-names {:expected :int})
              fixture-rows #(filterv (fn [row] (str/includes? (:name row) stem)) %)]
          (is (= [{:name root-name :kind "refinement"}
                  {:name left-name :kind "refinement"}]
                 (fixture-rows all)) "colliding visible rows retain canonical selection spelling")
          (is (= [{:name root-name :kind "refinement"}] (fixture-rows compatible))
              "the text twin and concealed int subtype never enter the int picker")
          (is (= (sort (map :name all)) (map :name all)))
          (is (not-any? #(= hidden-name (:name %)) all))
          (is (not-any? #(contains? % :type-ref) (concat all compatible)))))
      (finally
        (doseq [id ids] (sp/delete-entity storage :fn id))
        (sp/delete-entity storage :ns (:id left))
        (sp/delete-entity storage :ns (:id hidden))
        (exec-ctx/invalidate-graph-cache! ctx ids)))))


(deftest mismatch-graph-preserves-message-policy-test
  (doseq [[expected candidate message]
          [[:any :int "every type is a subtype of :any"]
           [:int :any ":any is not a subtype of :int — :any can't narrow to a concrete type without an explicit cast"]
           [[:refine :int [:> 0]] :int ":int lacks the refinement constraint required by [:refine :int [:> 0]]"]
           [[:refine :int [:> 0]] [:refine :int [:> 5]] "refinement constraints differ — [:> 5] ≠ [:> 0]"]
           [:int :text ":text is not a primitive subtype of :int"]
           [[:fn {:x :int} :int] [:fn {} :int] "function signature mismatch — [:fn {} :int] is not a subtype of [:fn {:x :int} :int]"]
           [:int [:list :int] "[:list :int] is not a subtype of :int"]
           [[:fn {} :int #{} #{:browser-main}] [:fn {} :int #{} #{:jvm-server}]
            "Execution mismatch: requires Browser; callback has JVM."]
           [[:fn {} :int #{} #{:browser-main}] [:fn {} :int #{} :unknown]
            "Execution mismatch: requires Browser; callback has unknown execution (no runtime proof)."]
           [[:fn {} :int #{} #{:browser-main :jvm-server}] [:fn {} :int #{} #{}]
            "Execution mismatch: requires Browser + JVM; callback has no proven runtime."]
           [[:fn {} :int #{} #{:browser-main}] [:fn {} :int #{} [:execution-var 'execution]]
            "Execution mismatch: requires Browser; callback has inferred execution (not yet proven)."]]]
    (is (= message (gh/exec-name :describe-type-mismatch
                                 {:expected expected :candidate candidate}))
        (str expected " / " candidate))))


(deftest namespace-index-graph-conceals-and-acquires-once-test
  (let [{:keys [storage ctx]} *graph*
        ns-row (sp/create-entity storage :ns {:name (gh/uniq "index-ns")})
        hidden-ns (sp/create-entity storage :ns {:name (gh/uniq "index-hidden-ns")})
        visible (sp/create-entity storage :fn {:name (gh/uniq "index-visible")
                                               :namespace-id (:id ns-row)})
        concealed (sp/create-entity storage :fn {:name (gh/uniq "index-hidden")
                                                 :namespace-id (:id hidden-ns)})
        root (sp/create-entity storage :fn {:name (gh/uniq "index-root")})
        acquire ta/candidate-ns-facts
        acquired (atom nil)
        calls (atom 0)
        ids (mapv :id [visible concealed root])]
    (try
      (exec-ctx/invalidate-graph-cache! ctx ids)
      (binding [tenancy/*seams-override*
                (atom {:graph-read-filter-fn
                       (fn [graph]
                         (is (some #(= (:id ns-row) (:id %)) (:namespaces graph))
                             "ACL receives the acquired namespace snapshot")
                         (update graph :fns
                                 (fn [rows]
                                   (filterv (fn [row] (not= (:id concealed) (:id row))) rows))))})]
        (with-redefs [ta/candidate-ns-facts (fn [context]
                                              (swap! calls inc)
                                              (reset! acquired (acquire context)))]
          (let [result (gh/exec-name :fn-ns-index {})]
            (is (= {:ns (:name ns-row) :ns-id (str (:id ns-row))}
                   (get result (:id visible))))
            (is (not (contains? result (:id concealed))))
            (is (not (contains? result (:id root))))
            (is (= 1 @calls) "the HOF callback shares one acquired input")
            (is (= (:name ns-row) (get-in @acquired [:paths (:id ns-row)])))
            (is (not (contains? (:paths @acquired) (:id hidden-ns)))
                "callable facts do not expose hidden-only namespace paths")
            (is (every? #(= #{:id :namespace-id} (set (keys %))) (:rows @acquired))
                "raw row content cannot escape through the facts primitive"))))
      (finally
        (doseq [id ids] (sp/delete-entity storage :fn id))
        (sp/delete-entity storage :ns (:id ns-row))
        (sp/delete-entity storage :ns (:id hidden-ns))
        (exec-ctx/invalidate-graph-cache! ctx ids)))))


(defn- check-stored-type-presentation!
  []
  (let [{:keys [storage all-name->id]} *graph*]
    (doseq [nm [:_candidate-ns-id :_candidate-ns-present? :_candidate-ns-rows
                :_candidate-ns-paths :_candidate-ns-path :_candidate-ns-value :_candidate-ns-key
                :_candidate-ns-keys :_candidate-ns-values :fn-ns-index
                :_type-profile-label :_type-profile-labels :_type-profile-labels-sorted
                :_type-execution-label
                :_mismatch-expected-text :_mismatch-expected-refined?
                :_mismatch-expected-primitive? :_mismatch-expected-fn?
                :_mismatch-expected-constraint :_mismatch-expected-execution
                :_mismatch-candidate-text :_mismatch-candidate-refined?
                :_mismatch-candidate-primitive? :_mismatch-candidate-fn?
                :_mismatch-candidate-constraint :_mismatch-candidate-execution
                :describe-type-mismatch]]
      (let [id (get all-name->id nm)]
        (is (some? (tc/reconstruct-fn-def storage id)))
        (is (nil? (tc/type-check-fn-after-mutation! storage id)) (str nm))))
    (doseq [[nm args effects result]
            [[:fn-ns-index {} #{:db} [:map :uuid :keyword-map]]
             [:describe-type-mismatch {:expected :type-expression :candidate :type-expression}
              #{} :text]]]
      (let [rich (registry/rich-type-of-id (get all-name->id nm))]
        (is (= (update-vals args types/resolve-alias) (:args rich)) (str nm))
        (is (= effects (:effects rich)) (str nm))
        (is (types/subtype? (:return rich) result) (str nm))))))


(deftest migrated-type-presentation-stored-contract-test
  (check-stored-type-presentation!))


(deftest mismatch-secret-ingress-is-rejected-test
  (check-stored-type-presentation!)
  (let [source (keyword (gh/uniq "secret-mismatch-input"))]
    (registry/record-rich-types! source {:args {} :return-type [:secret :type-expression]})
    (doseq [slot [:expected :candidate]]
      (is (thrown? clojure.lang.ExceptionInfo
            (check/check-fn-def! {:name (keyword (gh/uniq "secret-mismatch"))
                                  :parent :describe-type-mismatch
                                  :args (assoc {:expected {:value :int} :candidate {:value :text}}
                                               slot {:ref source})}))))))


(deftest namespace-index-empty-and-pathless-parity-test
  (with-redefs [ta/candidate-ns-facts (constantly {:rows [] :paths {}})]
    (is (= {} (gh/exec-name :fn-ns-index {}))))
  (let [id (random-uuid)
        ns-id (random-uuid)]
    (with-redefs [ta/candidate-ns-facts
                  (constantly {:rows [{:id id :namespace-id ns-id}]
                               :paths {}})]
      (is (= {id {:ns-id (str ns-id)}} (gh/exec-name :fn-ns-index {}))
          "missing namespace tables/paths retain identity, without inventing a path"))))
