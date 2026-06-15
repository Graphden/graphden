(ns graphden.types.tightening-test
  "Regression tests for the type tightenings landed in commit 55930708:

   - :ring-request-shape.headers narrowed from :jsonb to [:map :text :text]
   - :list-entities / :value-kinds return-types narrowed
   - :get-in/:assoc-in/:update-in .path items narrowed to key-union
   - :dissoc.map narrowed to [:map a b]

   Each test catches the specific REGRESSION a future :any widening
   would introduce. Mix of loader-output assertions (proves the EDN
   declaration shipped the tighter type) and check-fn-def! assertions
   (proves the type-checker enforces it on use)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


;; Load core+web so the EDN declarations populate base-fn-defs +
;; fn-defs. `loader/load-packages` returns data only; the
;; `register-type-aliases!` system-init step walks fn-defs to register
;; every `:type` / `:refine` / `:list` / `:union` / `:variant`. Mirror
;; that here so the alias snapshot includes `:ring-request-shape` for
;; the assertions below. Done via a `:once` fixture rather than a
;; `defonce` because other tests in the suite call
;; `types/clear-aliases!` and we need to re-seed AFTER they run.
(def ^:private loaded
  (loader/load-packages ["core" "web"]))


(defn- seed-aliases!
  []
  (types/clear-aliases!)
  (let [register-type-aliases!
        @(requiring-resolve 'graphden.system.core/register-type-aliases!)]
    (register-type-aliases! (:fn-defs loaded))))


;; -----------------------------------------------------------------------------
;; :ring-request-shape.headers — record-typed map of text → text

(deftest ring-request-shape-headers-is-map-of-text-to-text
  (testing ":ring-request-shape's :headers field is [:map :text :text]"
    (let [shape (get (types/aliases-snapshot) :ring-request-shape)]
      (is (map? shape) ":ring-request-shape resolves to a record alias")
      (is (= [:map :text :text] (:headers shape))
          "headers field is [:map :text :text], not :jsonb")
      (is (and (vector? (:body shape))
               (= :union (first (:body shape)))
               (contains? (set (rest (:body shape))) :input-stream)
               (contains? (set (rest (:body shape))) :null))
          "body is the Ring canonical union including :input-stream"))))


;; -----------------------------------------------------------------------------
;; :value-kinds — narrower return-type in the EDN

(deftest value-kinds-declares-list-of-text-return
  (testing ":value-kinds EDN declares :return-type [:list :text]"
    (is (= [:list :text]
           (:return-type (get (:base-fn-defs loaded) :value-kinds))))))


;; -----------------------------------------------------------------------------
;; :get-in / :assoc-in / :update-in .path items — key-union narrowing

(defn- expected-path-item-type
  "The tightened element type for sequence-arg path slots — keyword/
   int/text. Pulled from the loaded EDN so the test breaks if the
   declaration drifts."
  []
  (-> loaded :base-fn-defs (get :get-in)
      :args :path :type
      (nth 1)))


(deftest get-in-path-item-type-is-key-union
  (testing ":get-in.path is declared [:list [:union :keyword :int :text]]"
    (is (= [:union :int :keyword :text]
           (let [t (expected-path-item-type)]
             ;; types/make-union sorts; either ordering is fine —
             ;; compare as sets.
             (when (and (vector? t) (= :union (first t)))
               (into [:union] (sort-by pr-str (rest t)))))))))


(deftest assoc-in-and-update-in-share-tightened-path
  (testing ":assoc-in and :update-in match :get-in's path declaration"
    (is (= (-> loaded :base-fn-defs (get :get-in) :args :path :type)
           (-> loaded :base-fn-defs (get :assoc-in) :args :path :type)))
    (is (= (-> loaded :base-fn-defs (get :get-in) :args :path :type)
           (-> loaded :base-fn-defs (get :update-in) :args :path :type)))))


;; -----------------------------------------------------------------------------
;; :dissoc — accepts records (record ⊆ [:map :keyword :any])

(deftest dissoc-map-is-generic-map-type
  (testing ":dissoc.map is declared [:map a b], not :jsonb"
    (let [t (-> loaded :base-fn-defs (get :dissoc) :args :map :type)]
      (is (vector? t))
      (is (= :map (first t))
          ":dissoc accepts a homogeneous-map type, not the flat :jsonb"))))


;; -----------------------------------------------------------------------------
;; Behavioural tests — feed values through check-fn-def! to prove the
;; declarations are enforced.

;; `with-isolated-rich-types` keeps the synthetic `:get-in` / `:dissoc`
;; / `:stub-bool-fn` shapes this ns writes from leaking into
;; sibling integration tests (see check-test for the same rationale).
(use-fixtures :once exec/with-isolated-rich-types)


(use-fixtures :each
  exec/with-clean-registry
  (fn [t]
    ;; Re-seed aliases — earlier tests in the suite may have called
    ;; `types/clear-aliases!`. Without this the snapshot is empty.
    (seed-aliases!)
    ;; Seed only the rich-types we actually need; no sync, no DB.
    (registry/record-rich-types!
      :get-in
      {:args {:map  {:type :jsonb}
              :path {:type [:list [:union :keyword :int :text]]}}
       :return-type :any})
    (registry/record-rich-types!
      :dissoc
      {:args {:map {:type [:map 'a :any]}
              :key {:type 'a}}
       :return-type [:map 'a :any]})
    (t)))


(deftest get-in-path-rejects-bool-item
  (testing "a fn-def passing a :bool-returning ref into :get-in's :path is rejected"
    (registry/record-rich-types-raw!
      :stub-bool-fn
      {:args {} :return :bool})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :bad-get-in
                                :parent :get-in
                                :args {:map  {}
                                       :path [:stub-bool-fn]}}))
        ":bool is not a member of [:union :keyword :int :text]")))


(deftest get-in-path-accepts-literal-keyword-items
  (testing "literal keyword path items still pass after tightening"
    (is (some? (check/check-fn-def! {:name :ok-get-in
                                     :parent :get-in
                                     :args {:map  {:a {:b "v"}}
                                            :path [{:value :a}
                                                   {:value :b}]}})))))


(deftest dissoc-accepts-record-via-subtype
  (testing ":dissoc.map [:map a b] accepts a record-typed ref"
    (registry/record-rich-types-raw!
      :stub-record-fn
      {:args {} :return {:foo :int :bar :text}})
    (is (some? (check/check-fn-def! {:name :ok-dissoc
                                     :parent :dissoc
                                     :args {:map :stub-record-fn
                                            :key {:value :foo}}})))))


(deftest dissoc-rejects-list-ref
  (testing ":dissoc.map [:map a b] rejects a [:list :int]-returning ref"
    (registry/record-rich-types-raw!
      :stub-list-fn
      {:args {} :return [:list :int]})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :bad-dissoc
                                :parent :dissoc
                                :args {:map :stub-list-fn
                                       :key {:value :foo}}})))))
