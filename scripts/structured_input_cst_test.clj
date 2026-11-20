(ns structured-input-cst-test
  "Run with bb scripts/structured_input_cst_test.clj; no JVM or filesystem writes."
  (:require
    [babashka.classpath :as classpath]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests testing]])
  (:import
    (java.security
      MessageDigest)))


(classpath/add-classpath "src")


(require '[graphden.input.source :as source]
         '[graphden.input.syntax :as syntax]
         '[graphden.packages.records.parse :as parse]
         '[structured-input-cst :as cst])


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
        digest (MessageDigest/.digest (MessageDigest/getInstance "SHA-256") (String/.getBytes target "UTF-8"))
        tampered (assoc patch :target-text target
                        :target-sha256 (str/join (map #(format "%02x" (bit-and % 255)) digest)))]
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


(defn- migrate-child
  [text]
  (let [{module-namespace :namespace :keys [fns]} (edn/read-string text)
        definitions (mapv #(assoc % :namespace module-namespace) fns)
        ctx (parse/prepare-module definitions {} {})
        planned (source/plan-module [(peek definitions)] ctx)
        entry (first (:definitions planned))
        patch (cst/plan text {:namespace module-namespace :operations (cst/entry-operations entry)})
        target (:target-text patch)
        authored (assoc (peek (:fns (edn/read-string target))) :namespace module-namespace)
        verified (source/verify-module (assoc-in planned [:definitions 0 :proposed] authored) ctx)]
    (is (= :authored-source-equivalent (get-in verified [:definitions 0 :verification])))
    (is (= {:status :already-applied :text target} (cst/apply-text patch target)))
    target))


(deftest mixed-composed-input-preserves-entry-comments-and-literal-bytes
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :base :args {:pin :text :flag :bool} :return-type :text}\r\n"
                  "{:name :child :parent :base :args ; original map\r\n"
                  "{:pin ; bound text\r\n \"one\r\ntwo\" ; pin tail\r\n"
                  " ; free address\r\n :address {:type {:city :text} :required false} ; address tail\r\n"
                  " :flag false ; flag tail\r\n ; free number\r\n :count {:type :int}}}]}")
        target (migrate-child text)
        child (peek (:fns (edn/read-string target)))]
    (is (= {:pin [:value "one\r\ntwo"] :flag [:value false]} (:bind child)))
    (is (= [:address :count] (vec (keys (:input child)))))
    (is (str/includes? target ":input ; original map\r\n{ ; free address\r\n :address"))
    (is (str/includes? target "; address tail\r\n ; free number\r\n :count"))
    (is (str/includes? target ":bind {:pin ; bound text\r\n [:value \"one\r\ntwo\"] ; pin tail\r\n :flag [:value false] ; flag tail\r\n"))))


(deftest inline-source-location-keeps-comments-qualification-and-literal-bytes
  (let [body "{ ; вложенная функция\r\n :parent ; qualified\r\n :demo.main/producer}"
        literal "{:parent :producer :text \"one\r\ntwo\"}"
        text (str "{:namespace \"demo.main\" :fns [{:name :sink :args {:x :any :y :any} :return-type :any}\r\n"
                  "{:name :producer :return-type :any} {:name :child :parent :sink :args {"
                  ":x " body " :y {:value " literal "}}}]}")
        target (migrate-child text)]
    (is (str/includes? target (str ":x [:ref " body "]")))
    (is (str/includes? target (str ":y [:value " literal "]")))
    (is (not (str/includes? target "_anon-")))))


(deftest typed-reference-and-literal-annotations-use-their-original-source-spans
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :base :args {:x :any :y :any} :return-type :any}\r\n"
                  "{:name :producer :return-type :any} {:name :child :parent :base :args {"
                  ":x {:type ; list type\r\n [:list :text] ; type tail\r\n :ref ; producer\r\n :producer}"
                  " :new {:type :text} :y {:value ; literal\r\n \"a\r\nb\" :type :text}}}]}")
        target (migrate-child text)
        child (peek (:fns (edn/read-string target)))]
    (is (= {:x [:ref :producer] :y [:value "a\r\nb"]} (:bind child)))
    (is (= {[:x] {:type [:list :text]} [:y] {:type :text}} (:configure child)))
    (is (= {:new {:type :text}} (:input child)))
    (is (str/includes? target ":ref ; producer\r\n :producer"))
    (is (str/includes? target "[:x] {:type ; list type\r\n [:list :text] ; type tail\r\n}"))
    (is (str/includes? target ":value ; literal\r\n \"a\r\nb\""))))


(deftest inline-single-argument-cst-keeps-literal-and-ref-source-spans
  (let [original (str "{:namespace \"demo.main\" :fns [{:name :sink :return-type :any "
                      ":args {:x :any :y :any :z :any}} {:name :producer :return-type :any} "
                      "{:name :child :parent :sink :args {"
                      ":x {:args ; bind here\r\n {:x {:value ; payload\r\n {:parent :datum :text \"a\r\nb\"}}} :parent :sink} "
                      ":y {:parent :demo.main/sink :args {:x ; qualified ref\r\n :demo.main/producer}} "
                      ":z {:parent :sink :args {:x ; bare nil\r\n nil}} :added {:type :any}}}]}")
        target (migrate-child original)]
    (is (str/includes? target ":x [:ref {:bind ; bind here\r\n {:x [:value ; payload\r\n {:parent :datum :text \"a\r\nb\"}]} :parent :sink}]"))
    (is (str/includes? target ":y [:ref {:parent :demo.main/sink :bind {:x ; qualified ref\r\n [:ref :demo.main/producer]}}]"))
    (is (str/includes? target ":z [:ref {:parent :sink :bind {:x ; bare nil\r\n nil}}]"))
    (is (not (str/includes? target "_anon-")))))


(deftest nested-inline-cst-preserves-wrapped-references-and-bare-collections
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :sink :args {:x :any :y :any} :return-type :any}"
                  "{:name :producer :return-type :any} {:name :child :parent :sink :args {"
                  ":x {:args {:y {:args {:x {:ref ; keep the ref\r\n :demo.main/producer}"
                  " :y {:foo ; keep the data\r\n \"a\r\nb\"}} :parent :sink} :x nil} :parents [:sink]}}}]}")
        target (migrate-child text)]
    (is (str/includes? target ":x [:ref ; keep the ref\r\n :demo.main/producer]"))
    (is (str/includes? target ":y [:value {:foo ; keep the data\r\n \"a\r\nb\"}]"))
    (is (str/includes? target ":legacy-forms {:x :ref-wrapper, :y :bare-value} :bind"))
    (is (not (str/includes? target "_anon-")))))


(deftest inline-type-assertions-and-projection-retain-comments
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :sink :args {:x :text :y :any} :return-type :any}"
                  "{:name :producer :return-type :any} {:name :child :parent :sink :args {"
                  ":x {:parent :sink :args {:x {:type ; typed reference\r\n :any :ref :producer}}} "
                  ":y {:parent :sink :args {:x {:as ; alias\r\n :exposed :type ; pin\r\n :text}}}}}]}")
        target (migrate-child text)
        child (peek (:fns (edn/read-string target)))]
    (is (str/includes? target "[:x] {:type ; typed reference\r\n :any}"))
    (is (str/includes? target ":project ; alias\r\n [:exposed]"))
    (is (str/includes? target ":type ; pin\r\n :text"))
    (is (= {:exposed {:type :text :required false}} (get-in child [:bind :y 1 :input])))))


(deftest nested-inline-cst-keeps-legacy-body-order-across-map-kinds
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :sink :args {:x :any} :return-type :any}"
                  "{:name :producer :return-type :any} {:name :child :parent :sink :args {"
                  ":x {:parent :sink :args {:x {:args {:x {:type :any :ref :producer}} :parent :sink"
                  " :return-type :any :return-type-wide? true :description \"Test\","
                  " :effects #{} :expects-effects [] :lambda-params []}}}}}]}")
        target (migrate-child text)]
    (is (str/includes? target ":legacy-order [:args :parent :return-type :return-type-wide? :description :effects :expects-effects :lambda-params]"))
    (is (str/includes? target ":legacy-forms {:x :type-first}"))
    (is (not (str/includes? target "_anon-")))))


(deftest outer-inline-type-pins-move-to-the-enclosing-configurations
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :sink :args {:x :any} :return-type :any}"
                  "{:name :producer :return-type :any} {:name :child :parent :sink :args {"
                  ":x {:type ; outer pin\r\n :keyword :args {:x {:parent :producer"
                  " :type ; inner pin\r\n :text :args {}}} :parent :sink}}}]}")
        target (migrate-child text)
        child (peek (:fns (edn/read-string target)))
        outer (get-in child [:bind :x 1])
        inner (get-in outer [:bind :x 1])]
    (is (= {[:x] {:type :keyword}} (:configure child)))
    (is (= {[:x] {:type :text}} (:configure outer)))
    (is (= {:parent :producer :bind {}} inner))
    (is (str/includes? target ":type ; outer pin\r\n :keyword"))
    (is (str/includes? target ":type ; inner pin\r\n :text"))))


(deftest own-field-positions-survive-a-large-mixed-map
  (let [text "{:namespace \"demo.main\" :fns [{:name :base :return-type :text :args {:a :int :b :int :c :int :d :int :e :int :f :int :g :int}} {:name :child :parent :base :args {:z {:type :int} :a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :x {:type :text} :q {:type :bool}}}]}"
        args (:args (peek (:fns (edn/read-string text))))
        target (migrate-child text)]
    (is (= (mapv key (filter (comp map? val) args))
           (vec (keys (:input (peek (:fns (edn/read-string target))))))))))


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


(deftest inherited-primitive-rename-preserves-comments-and-projection
  (let [text "{:namespace \"demo.main\" :fns [{:name :sink :args {:x {:type :text} :extra {:type :text}} :return-type :text} {:name :alias :parent :sink :args {:x { ; old slot\r\n :as ; new slot\r\n :label ; kept\r\n} :extra {:value \"keep  bytes\"}}}]}"
        target (migrate-child text)
        rewritten (peek (:fns (edn/read-string target)))]
    (is (= {:label {:type :text :required false}} (:input rewritten)))
    (is (= {:x [:project [:label]] :extra [:value "keep  bytes"]} (:bind rewritten)))
    (doseq [expected-comment ["; old slot\r\n" "; new slot\r\n" "; kept\r\n"]]
      (is (= 1 (count (re-seq (re-pattern expected-comment) target)))))))


(deftest rename-and-explicit-type-pin-preserve-both-commented-fields
  (let [text "{:namespace \"demo.main\" :fns [{:name :sink :args {:x {:type :text}} :return-type :text} {:name :alias :parent :sink :args {:x {:type ; pin\r\n :text ; checked\r\n :as ; route\r\n :label}}}]}"
        target (migrate-child text)
        rewritten (peek (:fns (edn/read-string target)))]
    (is (= {:x [:project [:label]]} (:bind rewritten)))
    (is (= {[:x] {:type :text}} (:configure rewritten)))
    (doseq [expected-comment ["; pin\r\n" "; checked\r\n" "; route\r\n"]]
      (is (= 1 (count (re-seq (re-pattern expected-comment) target)))))))


(deftest original-concurrency-module-replays-renames-and-stable-binding-ids
  (let [text (slurp "resources/packages/core/concurrency/fns.edn")
        {module-ns :namespace :keys [fns]} (edn/read-string text)
        children #{:_retry-pause :_interval-sleep}
        named (into [] (comp (filter #(contains? (conj children :sleep) (:name %)))
                             (map #(assoc % :namespace module-ns))) fns)
        ctx (parse/prepare-module named {} {})
        planned (source/plan-module (filterv #(contains? children (:name %)) named) ctx)
        patch (cst/plan text {:namespace module-ns :operations (vec (mapcat cst/entry-operations (:definitions planned)))})
        target (:text (cst/apply-text patch text))
        rewritten (into {} (map (juxt :name #(assoc % :namespace module-ns))) (:fns (edn/read-string target)))
        replay (source/verify-module (update planned :definitions
                                             #(mapv (fn [entry] (assoc entry :proposed (get rewritten (get-in entry [:source :name])))) %))
                                     ctx)]
    (is (= {:status :already-applied :text target} (cst/apply-text patch target)))
    (is (= {:supported 2 :blocked 0} (:counts replay)))
    (doseq [entry (:definitions replay)]
      (let [[row] (get-in entry [:v2 :bindings])
            [arg] (get-in entry [:legacy :arguments])]
        (is (= :authored-source-equivalent (:verification entry)))
        (is (= (get-in arg [:origin :binding-id]) (:id row)))
        (is (= [(get-in arg [:origin :slot-id])] (:target-path row)))
        (is (= [(get-in arg [:rename :alias-id])] (:projection-path row)))))))


(deftest list-contributions-preserve-item-bytes-and-ref-spelling
  (doseq [wrap [(fn [xs] xs) #(str "{:append ; appending\r\n " % "}") #(str "{:closed ; seal\r\n true :append " % "}")]]
    (let [items "[:read ; first\r\n {:value ; literal\r\n \"a\r\nb\"} nil false 1N]"
          text (str "{:namespace \"demo.main\" :fns [{:name :sink :args {:xs {:type [:list :any]}} :return-type :any}"
                    "{:name :read :args {} :return-type :int} {:name :child :parent :sink :args {:xs " (wrap items) "}}]}")
          definitions (mapv #(assoc % :namespace "demo.main") (:fns (edn/read-string text)))
          ctx (parse/prepare-module definitions {} {})
          entry (peek (:definitions (source/plan-module definitions ctx)))
          fragment (-> entry :legacy :arguments first :list-builder :fragments peek)
          op (cst/list-builder-operation (peek definitions) :xs fragment (:name->id ctx))
          patch (cst/plan text (request {:op :rename-bind :definition :child :path [:args]} op))
          after (:text (cst/apply-text patch text))]
      (is (= :already-applied (:status (cst/apply-text patch after))))
      (is (= (:form op) (get-in (edn/read-string after) [:fns 2 :bind :xs])))
      (is (str/includes? after "[[:ref :read] ; first\r\n [:value ; literal\r\n \"a\r\nb\"] [:value nil] [:value false] [:value 1N]]"))
      (is (= :list-fragment-drift
             (reason #(cst/list-builder-operation (assoc (peek definitions) :name :other) :xs fragment (:name->id ctx))))))))


(deftest mixed-list-contributions-retain-field-trivia-and-configuration
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :base :args {:xs :sequence :flag :bool} :return-type :any}\r\n"
                  "{:name :child :parent :base :args {:xs ; list\r\n [{:value \"line one\r\nline two\"} false] ; end\r\n"
                  " :flag {:value true :type :bool} ; assertion\r\n :title {:type :text}}}]}")
        target (migrate-child text)
        child (peek (:fns (edn/read-string target)))]
    (is (= {:title {:type :text}} (:input child)))
    (is (= {[:flag] {:type :bool}} (:configure child)))
    (is (= {:xs [:list-builder {:items [[:value "line one\r\nline two"] [:value false]] :tail :open}]
            :flag [:value true]} (:bind child)))
    (is (str/includes? target ":xs ; list\r\n [:list-builder"))
    (is (str/includes? target "; end\r\n"))
    (is (str/includes? target "; assertion\r\n"))))


(deftest list-and-typed-inline-producer-survive-the-same-field-partition
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :base :args {:xs :sequence :flag :bool} :return-type :any}"
                  "{:name :producer :return-type :bool} "
                  "{:name :child :parent :base :args {:xs [false] :flag {:parent :producer :type :bool} "
                  ":title {:type :text}}}]}")
        child (peek (:fns (edn/read-string (migrate-child text))))]
    (is (= {:title {:type :text}} (:input child)))
    (is (= {[:flag] {:type :bool}} (:configure child)))
    (is (= {:xs [:list-builder {:items [[:value false]] :tail :open}]
            :flag [:ref {:parent :producer}]} (:bind child)))))


(deftest independent-scalar-projection-and-list-preserve-their-own-origins
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :base :args {:xs :sequence :key {:type :text}} :return-type :any}"
                  "{:name :child :parent :base :args {:key {:as :label} :xs [false nil]}}]}")
        child (peek (:fns (edn/read-string (migrate-child text))))]
    (is (= {:label {:type :text :required false}} (:input child)))
    (is (= [:project [:label]] (get-in child [:bind :key])))
    (is (= [:list-builder {:items [[:value false] [:value nil]] :tail :open}] (get-in child [:bind :xs])))))


(deftest positional-list-inputs-preserve-item-comments-and-rich-annotation-trivia
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :collect :args {:xs :sequence} :return-type :any}\r\n"
                  "{:name :child :parent :collect :args {:xs [{:as ; uses caller\r\n"
                  " :label :type [:union ; rich input\r\n :text :null]} {:as :other} \"a  b\"]}}]}")
        target (migrate-child text)
        child (peek (:fns (edn/read-string target)))]
    (is (= :unsupported-reader-discard
           (reason #(migrate-child (str/replace text ":text :null" ":text #_:discarded :null")))))
    (is (= {:label {:type [:union :text :null] :required false} :other {:type :any :required false}} (:input child)))
    (is (= [[:project [:label]] [:project [:other]] [:value "a  b"]] (get-in child [:bind :xs 1 :items])))
    (doseq [remark ["; uses caller\r\n" "; rich input\r\n"]]
      (is (= 1 (count (re-seq (re-pattern remark) target)))))))


(deftest positional-occurrence-policy-preserves-the-original-literal-and-comments
  (let [description "\"line 1\\n; stays in the string\""
        text (str "{:namespace \"demo.main\" :fns [{:name :collect :args {:xs :sequence} :return-type :any}\r\n"
                  "{:name :child :parent :collect :args {:xs [{:description " description " ; item note\r\n"
                  " :as :value :required ; explicit on the item\r\n false :type :text}]}}]}")
        result (migrate-child text)
        child (peek (:fns (edn/read-string result)))]
    (is (= {:value {:type :text :required false}} (:input child)))
    (is (= [:project [:value] {:description (edn/read-string description) :required false}] (get-in child [:bind :xs 1 :items 0])))
    (doseq [snippet [description "; item note\r\n" "; explicit on the item\r\n"]]
      (is (str/includes? result snippet)))))


(let [{:keys [fail error]} (run-tests 'structured-input-cst-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
