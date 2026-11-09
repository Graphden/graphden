(ns structured-input-cst-test
  "Run with bb scripts/structured_input_cst_test.clj; no JVM or filesystem writes."
  (:require
    [babashka.classpath :as classpath]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests testing]]
    [structured-input-cst :as cst])
  (:import
    (java.security
      MessageDigest)))


(classpath/add-classpath "src")


(require '[graphden.input.source :as source]
         '[graphden.input.syntax :as syntax]
         '[graphden.packages.records.parse :as parse])


(defn- request
  [& operations]
  {:namespace "demo.main" :operations (vec operations)})


(defn- wrap-value
  [definition path]
  {:op :wrap-value :definition definition :path path})


(defn- reason
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))


(deftest rename-preserves-declaration-style-test
  (let [source "; module\n{:namespace \"demo.main\", :fns\n [{:name :base\n   :args ; owned declaration comment\n   {:x {:type :any :value {:args 9 :input 2}}}}]} ; end\n"
        patch (cst/plan source (request {:op :rename-input :definition :base :path [:args]}))
        expected (str/replace source ":args ; owned" ":input ; owned")]
    (is (= expected (:target-text patch)))
    (is (= {:status :applied :text expected} (cst/apply-text patch source)))
    (is (= {:x {:type :any :value {:args 9 :input 2}}}
           (get-in (edn/read-string expected) [:fns 0 :input])))))


(deftest wrapping-preserves-literal-bytes-and-reserved-keys-test
  (let [payload "{:args {:x :some-ref} ; payload, not a definition\n     :parent :fake :bind {:configure [:value :keyword]}\n     :input \"first\nsecond\"}"
        source (str "{:namespace \"demo.main\" :fns\n [{:name :child :parent :const\n   :args {:payload ; before value\n   " payload " ; after value\n          :untouched {:value {:args 7}}}}]}")
        patch (cst/plan source (request (wrap-value :child [:args :payload])))
        expected (str/replace source payload (str "[:value " payload "]"))]
    (is (= expected (:target-text patch)))
    (is (= [:value (edn/read-string payload)]
           (get-in (edn/read-string expected) [:fns 0 :args :payload])))
    (is (= {:value {:args 7}} (get-in (edn/read-string expected) [:fns 0 :args :untouched])))))


(deftest crlf-unicode-and-multiline-string-bytes-test
  (let [source "; 🐱\r\n{:namespace \"demo.main\" :fns [{:name :base :description \"🐱\" :args {:x \"one\r\ntwo\"}}]}\r\n"
        patch (cst/plan source (request {:op :rename-input :definition :base :path [:args]}
                                        (wrap-value :base [:args :x])))
        expected (-> source
                     (str/replace ":args" ":input")
                     (str/replace "\"one\r\ntwo\"" "[:value \"one\r\ntwo\"]"))]
    (is (= expected (:target-text patch)))
    (is (= [:value "one\r\ntwo"] (get-in (edn/read-string expected) [:fns 0 :input :x])))
    (is (= {:status :already-applied :text expected} (cst/apply-text patch expected)))))


(deftest exact-namespace-and-top-level-definition-scope-test
  (let [source "{:namespace \"demo.main\" :fns [{:name :child :args {:x {:name :child :args {:x 2}}}}]}"
        patch (cst/plan source (request (wrap-value :child [:args :x])))
        other-source (str/replace source "demo.main" "other.main")]
    (is (= [:value {:name :child :args {:x 2}}]
           (get-in (edn/read-string (:target-text patch)) [:fns 0 :args :x])))
    (is (= :invalid-module (reason #(cst/plan other-source (request (wrap-value :child [:args :x]))))))
    (is (= :checksum-mismatch (reason #(cst/apply-text patch other-source))))
    (is (= "other.main" (:namespace (cst/plan other-source
                                              {:namespace "other.main"
                                               :operations [(wrap-value :child [:args :x])]}))))))


(deftest namespace-less-modules-preserve-absence-test
  (doseq [source ["{:fns [{:name :base :args {:x 1}}]}"
                  "{:namespace nil :fns [{:name :base :args {:x 1}}]}"]]
    (let [patch (cst/plan source {:namespace nil :operations [(wrap-value :base [:args :x])]})]
      (is (= (str/replace source ":x 1" ":x [:value 1]") (:target-text patch)))
      (is (= :invalid-module (reason #(cst/plan source (request (wrap-value :base [:args :x])))))))))


(deftest tabs-before-node-do-not-shift-source-spans-test
  (let [source "\t{:namespace\t\"demo.main\" :fns [{:name :base\t:args {\t:x\t42}}]}\n"
        patch (cst/plan source (request {:op :rename-input :definition :base :path [:args]}
                                        (wrap-value :base [:args :x])))
        expected (-> source (str/replace ":args" ":input") (str/replace "42" "[:value 42]"))]
    (is (= expected (:target-text patch)))
    (is (= {:status :applied :text expected} (cst/apply-text patch source)))))


(deftest unknown-and-duplicate-definitions-refuse-test
  (testing "Duplicate top-level names refuse even when another definition is selected"
    (is (= :duplicate-definition
           (reason #(cst/plan "{:namespace \"demo.main\" :fns [{:name :a} {:name :a} {:name :b :args {:x 1}}]}"
                              (request (wrap-value :b [:args :x])))))))
  (testing "Unknown and unnamed definitions refuse"
    (is (= :missing-definition
           (reason #(cst/plan "{:namespace \"demo.main\" :fns []}"
                              (request (wrap-value :absent [:args :x]))))))
    (is (= :unnamed-definition
           (reason #(cst/plan "{:namespace \"demo.main\" :fns [{:args {:x 1}}]}" (request)))))))


(deftest missing-and-non-map-paths-refuse-test
  (let [source "{:namespace \"demo.main\" :fns [{:name :base :args {:x [1 2]}}]}"]
    (is (= :missing-path (reason #(cst/plan source (request (wrap-value :base [:args :missing]))))))
    (is (= :non-map-path (reason #(cst/plan source (request (wrap-value :base [:args :x :value]))))))
    (is (= :invalid-argument-path (reason #(cst/plan source (request (wrap-value :base [:description]))))))))


(deftest duplicate-keys-and-unsafe-reader-forms-refuse-test
  (doseq [source ["{:namespace \"demo.main\" :fns [{:name :base :args {:x 1 :x 2}}]}"
                  "{:namespace \"demo.main\" :fns []} {:extra true}"
                  "#=(throw (Exception. \"must never execute\"))"
                  "#unknown {:namespace \"demo.main\" :fns []}"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot read inventory EDN"
          (cst/plan source (request))))))


(deftest conflicting-operations-refuse-test
  (let [source "{:namespace \"demo.main\" :fns [{:name :base :args {:x {:value 2}}}]}"
        wrap (wrap-value :base [:args :x])]
    (is (= :overlapping-operations (reason #(cst/plan source (request wrap wrap)))))
    (is (= :overlapping-operations
           (reason #(cst/plan source (request wrap (wrap-value :base [:args :x :value]))))))))


(deftest reader-discard-at-structural-boundary-refuses-test
  (doseq [source ["{:namespace \"demo.main\" :fns [{:name :base #_:ignored :args {:x 1}}]}"
                  "; leading\n{:namespace \"demo.main\" :fns [#_{:name :base :args {:x :discarded}} {:name :base :args {:x :live}}]} ; trailing\n"
                  "{:namespace \"demo.main\" :fns [{:name :base :args {:x 1}} #_{:name :other}]}"]]
    (is (= :unsupported-reader-discard
           (reason #(cst/plan source (request (wrap-value :base [:args :x]))))))))


(deftest rename-requires-unambiguous-base-declaration-test
  (testing "Both old and new keys refuse"
    (is (= :existing-input
           (reason #(cst/plan "{:namespace \"demo.main\" :fns [{:name :base :args {} :input {}}]}"
                              (request {:op :rename-input :definition :base :path [:args]}))))))
  (testing "Compositions and other paths refuse"
    (is (= :not-base-declaration
           (reason #(cst/plan "{:namespace \"demo.main\" :fns [{:name :child :parent :p :args {}}]}"
                              (request {:op :rename-input :definition :child :path [:args]})))))
    (is (= :invalid-rename-path
           (reason #(cst/plan "{:namespace \"demo.main\" :fns [{:name :base :args {:x {}}}]}"
                              (request {:op :rename-input :definition :base :path [:args :x]})))))))


(deftest rename-refuses-type-row-markers-test
  (doseq [marker [:type :refine :list :union :map :tuple :variant :fn-type :marker]]
    (let [source (pr-str {:namespace "demo.main" :fns [{:name :row marker :any :args {}}]})]
      (is (= :not-base-declaration
             (reason #(cst/plan source (request {:op :rename-input :definition :row :path [:args]}))))))))


(deftest checksum-bound-idempotent-application-test
  (let [source "{:namespace \"demo.main\" :fns [{:name :base :args {:x nil}}]}"
        patch (cst/plan source (request (wrap-value :base [:args :x])))
        applied (cst/apply-text patch source)]
    (is (= [:value nil] (get-in (edn/read-string (:text applied)) [:fns 0 :args :x])))
    (is (= {:status :already-applied :text (:text applied)} (cst/apply-text patch (:text applied))))
    (is (= :checksum-mismatch (reason #(cst/apply-text patch (str source "\n")))))
    (is (= :checksum-mismatch (reason #(cst/apply-text patch (str (:text applied) "\n")))))
    (is (= :invalid-plan (reason #(cst/apply-text (update patch :target-text str "\n") source))))))


(deftest unsupported-moves-and-unknown-operations-refuse-test
  (let [source "{:namespace \"demo.main\" :fns [{:name :base :args {:x 1}}]}"]
    (is (= :unsupported-comment-safe-move
           (reason #(cst/plan source (request {:op :move-args-entry :definition :base
                                               :path [:args :x] :destination [:bind :x]})))))
    (is (= :unknown-operation
           (reason #(cst/plan source (request {:op :guess :definition :base :path [:args]})))))))


(deftest recomputed-target-checksum-does-not-bypass-edit-consistency-test
  (let [source "{:namespace \"demo.main\" :fns [{:name :base :args {:x 1}}]}"
        patch (cst/plan source (request (wrap-value :base [:args :x])))
        target (str/replace (:target-text patch) "demo.main" "tampered.main")
        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String target "UTF-8"))
        tampered (assoc patch :target-text target
                        :target-sha256 (apply str (map #(format "%02x" (bit-and % 255)) digest)))]
    (is (= :invalid-plan (reason #(cst/apply-text tampered source))))
    (is (= :invalid-plan (reason #(cst/apply-text tampered target))))))


(deftest composed-cst-operations-preserve-comments-and-crlf-test
  (let [source " ; lead\r\n{:namespace \"demo.main\" :fns\r\n [{:name :child :parent :sink\r\n   :args ; arguments\r\n   {:a { ; before operator\r\n         :value ; before payload\r\n         {:args :data :ref [:value \"a\r\nb\"]} ; after payload\r\n        }\r\n    :b { ; ref\r\n         :ref, :producer ; close\r\n        }\r\n    :c :producer ; ref leaf\r\n    :d :unknown}}]} ; end\r\n"
        operations [{:op :rename-bind :definition :child :path [:args]}
                    {:op :map-expression-to-vector :definition :child :path [:args :a]}
                    {:op :map-expression-to-vector :definition :child :path [:args :b]}
                    {:op :wrap-ref :definition :child :path [:args :c]}
                    (wrap-value :child [:args :d])]
        patch (cst/plan source (apply request operations))]
    ;; Delimiters alone change for wrappers; all enclosed comments/bytes survive.
    (is (= (-> source
               (str/replace ":args ; arguments" ":bind ; arguments")
               (str/replace "{:a {" "{:a [")
               (str/replace "        }\r\n    :b {" "        ]\r\n    :b [")
               (str/replace "        }\r\n    :c :producer" "        ]\r\n    :c [:ref :producer]")
               (str/replace ":d :unknown" ":d [:value :unknown]"))
           (:target-text patch)))
    (is (= {:a [:value {:args :data :ref [:value "a\r\nb"]}]
            :b [:ref :producer] :c [:ref :producer] :d [:value :unknown]}
           (get-in (edn/read-string (:target-text patch)) [:fns 0 :bind])))
    (is (= {:status :already-applied :text (:target-text patch)}
           (cst/apply-text patch (:target-text patch))))))


(defn- planner-entry
  [raw]
  (let [definitions [{:name :sink :namespace "demo.main" :args {:x :any} :return-type :any}
                     {:name :producer :namespace "demo.main" :args {} :return-type :any}
                     {:name :child :namespace "demo.main" :parent :sink :args {:x raw}}]
        context (parse/prepare-module definitions {} {})]
    [(last (:definitions (source/plan-module definitions context))) context]))


(deftest bridge-matches-supported-source-planner-values-test
  (doseq [raw [nil false 3 "multiline\nstring" :unknown [1 2] [:ref :producer]
               {:data {:args :producer :bind {:ref :unknown}}}
               {:value {:ref :producer :args {:value [:ref :producer]}}}]]
    (let [[entry _] (planner-entry raw)
          original (pr-str {:namespace "demo.main" :fns [(dissoc (:source entry) :namespace)]})
          patch (cst/plan original (apply request (cst/entry-operations entry)))]
      (is (= :supported (:status entry)))
      (is (= (dissoc (:proposed entry) :namespace)
             (first (:fns (edn/read-string (:target-text patch)))))))))


(deftest bridge-retains-reference-spelling-with-equivalent-resolved-identity-test
  (doseq [raw [:producer :demo.main/producer {:ref :producer} {:ref :demo.main/producer}]]
    (let [[entry context] (planner-entry raw)
          original (pr-str {:namespace "demo.main" :fns [(dissoc (:source entry) :namespace)]})
          patch (cst/plan original (apply request (cst/entry-operations entry)))
          actual (get-in (edn/read-string (:target-text patch)) [:fns 0 :bind :x])
          expected (get-in entry [:proposed :bind :x])
          ;; Syntax parses the preserved spelling against the same frozen index.
          syntax-context {:name->id (:name->id context) :binding-id (fn [_ _] :binding)}
          parse-expression #(syntax/parse-expression
                              {:type {:x :any} :fields [{:id :slot :name :x :type :any}]}
                              :scope {:x %} syntax-context)]
      (is (= :supported (:status entry)))
      (is (= (parse-expression expected) (parse-expression actual))))))


(deftest bridge-requires-supported-and-consistent-ledger-test
  (let [[entry _] (planner-entry {:value false})]
    (is (= :unsupported-source-entry
           (reason #(cst/entry-operations (assoc entry :status :blocked)))))
    (is (= :planner-ledger-mismatch
           (reason #(cst/entry-operations (assoc-in entry [:proposed :bind :x] [:value true])))))
    (is (= :planner-ledger-mismatch
           (reason #(cst/entry-operations (assoc-in entry [:source :args :x] {:value false :extra true})))))
    (is (= :unsupported-source-argument
           (reason #(cst/entry-operations (assoc-in entry [:legacy :arguments 0 :policy] {:terminal true})))))
    (is (= :planner-ledger-mismatch
           (reason #(cst/entry-operations (assoc-in entry [:legacy :arguments] [])))))))


(deftest bridge-handles-declarations-and-empty-args-without-manufacturing-keys-test
  (doseq [[definition kind proposed expected]
          [[{:name :base :args {}} :base {:name :base :input {}}
            [{:op :rename-input :definition :base :path [:args]}]]
           [{:name :row :type :text} :type {:name :row :type :text} []]
           [{:name :child :parent :sink :args {}} :composed {:name :child :parent :sink :bind {}}
            [{:op :rename-bind :definition :child :path [:args]}]]
           [{:name :child :parent :sink} :composed {:name :child :parent :sink} []]]]
    (is (= expected (cst/entry-operations {:source definition :kind kind :status :supported
                                           :legacy {:arguments []} :proposed proposed :blockers []})))))


(deftest composed-operations-refuse-unsupported-shapes-test
  (let [source "{:namespace \"demo.main\" :fns [{:name :child :parent :sink :args {:x {:value 1 :extra 2} :y 42}}]}"]
    (is (= :unsupported-expression-map
           (reason #(cst/plan source (request {:op :map-expression-to-vector
                                               :definition :child :path [:args :x]})))))
    (is (= :non-keyword-reference
           (reason #(cst/plan source (request {:op :wrap-ref :definition :child :path [:args :y]}))))))
  (is (= :not-composed-definition
         (reason #(cst/plan "{:namespace \"demo.main\" :fns [{:name :base :args {}}]}"
                            (request {:op :rename-bind :definition :base :path [:args]})))))
  (is (= :existing-target
         (reason #(cst/plan "{:namespace \"demo.main\" :fns [{:name :child :parent :sink :args {} :bind {}}]}"
                            (request {:op :rename-bind :definition :child :path [:args]}))))))


(let [{:keys [fail error]} (run-tests 'structured-input-cst-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
