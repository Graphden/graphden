(ns graphden.packages.registry.impls-test
  "Unit tests for the `registry` package's PURE impls — the thin
   boundary base-fns the publish / install / export graph composes on.

   Each one is meant to be a one-line delegation to a `src/` namespace
   that already has its own tests; what is NOT tested anywhere else is
   that the delegation still holds — the arg order, the return shape,
   the boolean coercion. That is the whole contract of a thin base-fn,
   and it is exactly what silently rots when the callee's signature
   moves. The effectful members (publish, withdraw, materialize) need
   storage and are covered by `packages.registry-test`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "registry" "registry"))


(defn- call
  "Invoke a base-fn impl the way the executor does: args as delays.

   `delay` is a macro, so the map cannot be built with `update-vals`
   over it — the value has to be wrapped inside the expansion."
  [kw args]
  ((impls/impl-of kw)
   (into {} (map (fn [[k v]] [k (delay v)])) args)
   nil))


(defn- priv
  [sym]
  (let [v (ns-resolve 'graphden.packages.app.registry.impls sym)]
    (assert v (str "no such var: " sym))
    @v))


(deftest secret-path-args-lists-every-vault-binding
  (let [fn-defs [{:name :db-url :args {:url {:secret-path "kv/db#url"}}}
                 {:name :plain :args {:n {:value 1}}}
                 {:name :two :args {:a {:secret-path "kv/a"} :b {:secret-path "kv/b"}}}]
        out (call :secret-path-args {:fn-defs fn-defs})]
    (testing "one entry per secret-path arg, naming the fn and the arg"
      (is (= #{{:fn :db-url :arg :url} {:fn :two :arg :a} {:fn :two :arg :b}}
             (set out))))
    (testing "a fn-def with no vault binding contributes nothing"
      (is (not-any? #(= :plain (:fn %)) out)))
    (testing "an empty bundle yields an empty manifest"
      (is (empty? (call :secret-path-args {:fn-defs []}))))))


(deftest strip-secret-paths-reverts-the-arg-to-a-free-slot
  (let [fn-defs [{:name :db-url :args {:url {:secret-path "kv/db#url"} :n {:value 1}}}]
        [stripped] (call :strip-secret-paths {:fn-defs fn-defs})]
    (testing "the vault path does not travel with the exported bundle"
      (is (nil? (get-in stripped [:args :url :secret-path]))))
    (testing "the other args are untouched"
      (is (= {:value 1} (get-in stripped [:args :n]))))))


(deftest encode-unreadable-kws-tags-refs-edn-cannot-spell
  (testing "a version-qualified namespace becomes a tagged literal"
    (let [out (call :encode-unreadable-kws {:value {:parent (keyword "mycorp@1-0-0" "greet")}})]
      (is (not= (keyword "mycorp@1-0-0" "greet") (:parent out))
          "an `@`-qualified keyword is not readable back — it must be tagged")))
  (testing "an ordinary keyword is left alone"
    (is (= {:parent :add} (call :encode-unreadable-kws {:value {:parent :add}}))))
  (testing "scalars pass through"
    (is (= 42 (call :encode-unreadable-kws {:value 42})))
    (is (= "s" (call :encode-unreadable-kws {:value "s"})))))


(deftest semver-compatible?-is-the-caret-range
  (testing "same major: compatible upward"
    (is (true? (call :semver-compatible? {:from "1.2.0" :to "1.3.0"})))
    (is (true? (call :semver-compatible? {:from "1.2.0" :to "1.2.0"}))))
  (testing "a major bump leaves the range"
    (is (false? (call :semver-compatible? {:from "1.2.0" :to "2.0.0"}))))
  (testing "below 1.0 the MINOR is the breaking axis"
    (is (true? (call :semver-compatible? {:from "0.1.0" :to "0.1.5"})))
    (is (false? (call :semver-compatible? {:from "0.1.0" :to "0.2.0"}))))
  (testing "going backwards is not compatible"
    (is (false? (call :semver-compatible? {:from "1.3.0" :to "1.2.0"})))))


(deftest breaking-changes-between-reports-consumer-visible-loss
  (let [old [{:name :greet :args {:who {:type :text}}}]]
    (testing "a removed fn is a breaking change"
      (is (seq (call :breaking-changes-between {:old-fns old :new-fns []}))))
    (testing "an unchanged bundle has none"
      (is (empty? (call :breaking-changes-between {:old-fns old :new-fns old}))))))


(deftest incompatible-dependency-bumps-uses-the-same-range
  ;; Dependencies are a LIST of `{:name :version}` rows (a bundle's
  ;; `:package-dependencies`), not a name→version map.
  (let [dep (fn [v] [{:name "core" :version v}])]
    (testing "a dependency that left its caret range is reported"
      (let [[out] (call :incompatible-dependency-bumps
                        {:old-deps (dep "1.2.0") :new-deps (dep "2.0.0")})]
        (is (= {:kind :dependency-incompatible :name "core" :old "1.2.0" :new "2.0.0"}
               out))))
    (testing "a bump inside the range is not"
      (is (empty? (call :incompatible-dependency-bumps
                        {:old-deps (dep "1.2.0") :new-deps (dep "1.3.0")}))))
    (testing "a dependency merely ADDED or DROPPED is not a break by itself"
      (is (empty? (call :incompatible-dependency-bumps {:old-deps [] :new-deps (dep "1.0.0")})))
      (is (empty? (call :incompatible-dependency-bumps {:old-deps (dep "1.0.0") :new-deps []}))))))


(deftest version-qualified-ns-renames-only-the-package-subtree
  (let [f (priv 'version-qualified-ns)]
    (testing "the root namespace itself is version-qualified"
      (is (= "mycorp@1-0-0" (f "mycorp" "1.0.0" "mycorp"))))
    (testing "and so is everything under it, keeping the tail"
      (is (= "mycorp@1-0-0.util" (f "mycorp" "1.0.0" "mycorp.util")))
      (is (= "mycorp@1-0-0.a.b" (f "mycorp" "1.0.0" "mycorp.a.b"))))
    (testing "a namespace that merely SHARES A PREFIX is left alone"
      ;; `mycorporate` starts with `mycorp` as a string but is a different
      ;; root — renaming it would move a stranger's fns into the copy.
      (is (= "mycorporate" (f "mycorp" "1.0.0" "mycorporate"))))
    (testing "an unrelated namespace and a nil are untouched"
      (is (= "other.ns" (f "mycorp" "1.0.0" "other.ns")))
      (is (nil? (f "mycorp" "1.0.0" nil))))
    (testing "dots in the version become dashes — a version is one ns segment"
      (is (= "p@2-1-3" (f "p" "2.1.3" "p"))))))
