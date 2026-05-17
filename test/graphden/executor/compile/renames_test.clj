(ns graphden.executor.compile.renames-test
  "Tests for `graphden.executor.compile.renames` — free-arg name
   translation and HOF lambda-param classification.

   `apply-renames` is pure; the rest walk the graph, so those tests
   build a real graph and `l/build-lookups` over it."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(defn- lookups-for
  [storage]
  (l/build-lookups
    {:fns        (sp/query-entities storage :fn {})
     :slots      (sp/query-entities storage :slot {})
     :fn-slots   (sp/query-entities storage :fn-slot {})
     :bindings   (sp/query-entities storage :binding {})
     :list-items (sp/query-entities storage :binding-list-item {})}))


;; ============================================================================
;; apply-renames — pure
;; ============================================================================

(deftest apply-renames-test
  (testing "each {R-name → F-name} entry exposes F's value under R's name"
    (is (= {:x 1 :b 2} (r/apply-renames {:a 1 :b 2} {:x :a}))))

  (testing "an entry whose F-name is absent is a no-op; extra keys pass through"
    (is (= {:b 2} (r/apply-renames {:b 2} {:x :a})))
    (is (= {:a 1} (r/apply-renames {:a 1} {}))))

  (testing "multiple renames are applied together"
    (is (= {:x 1 :y 2} (r/apply-renames {:a 1 :b 2} {:x :a :y :b})))))


;; ============================================================================
;; deep-free-ext-names
;; ============================================================================

(deftest deep-free-ext-names-test
  (testing "an unbound slot surfaces as a deep-free name; a bound one does not"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "dfn-base")
              sa   (setup/create-slot! storage "a" :int)
              sb   (setup/create-slot! storage "b" :int)
              _    (setup/attach-slot! storage (:id base) (:id sa) 0)
              _    (setup/attach-slot! storage (:id base) (:id sb) 1)
              fn1  (setup/create-composed-fn! storage "dfn-1" (:id base))
              _    (setup/bind-value! storage (:id fn1) (:id sa) 10)]
          ;; a is value-bound, b is free → only b surfaces.
          (is (= [:b] (r/deep-free-ext-names (:id fn1) (lookups-for storage)))))
        (finally (sp/close storage)))))

  (testing "deep-free walks across a non-HOF ref into the ref-target's free args"
    (let [storage (setup/create-test-storage)]
      (try
        (let [;; D exposes a free slot `inner`.
              base-d (setup/create-base-fn! storage "dfn-base-d")
              s-in   (setup/create-slot! storage "inner" :int)
              _      (setup/attach-slot! storage (:id base-d) (:id s-in) 0)
              d      (setup/create-composed-fn! storage "dfn-d" (:id base-d))
              ;; C binds its slot `s` to a ref of D.
              base-c (setup/create-base-fn! storage "dfn-base-c")
              s-c    (setup/create-slot! storage "s" :int)
              _      (setup/attach-slot! storage (:id base-c) (:id s-c) 0)
              c      (setup/create-composed-fn! storage "dfn-c" (:id base-c))
              _      (setup/bind-ref! storage (:id c) (:id s-c) (:id d))]
          ;; C's own slot s is ref-bound; D's `inner` bubbles up.
          (is (= [:inner] (r/deep-free-ext-names (:id c) (lookups-for storage)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; hof-lambda-params
;; ============================================================================

(deftest hof-lambda-params-test
  (testing "a deep-free name nobody supplies is a lambda-param of the HOF target"
    (let [storage (setup/create-test-storage)]
      (try
        (let [;; R exposes free `x`.
              base-r (setup/create-base-fn! storage "hlp-base-r")
              slot-x (setup/create-slot! storage "x" :int)
              _      (setup/attach-slot! storage (:id base-r) (:id slot-x) 0)
              r-fn   (setup/create-composed-fn! storage "hlp-r" (:id base-r))
              ;; F is built on an unrelated base — it neither owns nor
              ;; supplies `x`.
              base-f (setup/create-base-fn! storage "hlp-base-f")
              slot-y (setup/create-slot! storage "y" :int)
              _      (setup/attach-slot! storage (:id base-f) (:id slot-y) 0)
              f-fn   (setup/create-composed-fn! storage "hlp-f" (:id base-f))]
          ;; Nothing in F's world supplies x → x is a per-call lambda-param.
          (is (= [:x] (r/hof-lambda-params (:id r-fn) (:id f-fn)
                                           (lookups-for storage)))))
        (finally (sp/close storage)))))

  (testing "a name a caller-relative supplies is captured, not a lambda-param"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "hlp2-base")
              slot (setup/create-slot! storage "x" :int)
              _    (setup/attach-slot! storage (:id base) (:id slot) 0)
              r-fn (setup/create-composed-fn! storage "hlp2-r" (:id base))
              f-fn (setup/create-composed-fn! storage "hlp2-f" (:id base))
              _    (setup/bind-value! storage (:id f-fn) (:id slot) 99)]
          ;; f binds x → x flows in from the closure → no lambda-params.
          (is (= [] (r/hof-lambda-params (:id r-fn) (:id f-fn)
                                         (lookups-for storage)))))
        (finally (sp/close storage))))))
