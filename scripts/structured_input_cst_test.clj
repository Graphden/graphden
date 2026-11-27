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


(require '[graphden.input.format :as format]
         '[graphden.input.source :as source]
         '[graphden.input.syntax :as syntax]
         '[graphden.packages.records.ids :as ids]
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


(deftest occupied-reference-removes-the-two-alias-tokens-and-retains-comments
  (doseq [wrapper ["{:as ; alias comment\n :handler :ref :child}"
                   "{:ref :child ; alias comment\n :as :handler}"]]
    (let [raw (str "{:namespace \"demo.main\" :fns [{:name :host :parent :base :args {:value " wrapper "}}]}")
          operations [(assoc (wrap-value :host [:args]) :op :rename-bind)
                      {:op :occupied-reference :definition :host :path [:args :value]}]
          plan (cst/plan raw (apply request operations))
          edited (:text (cst/apply-text plan raw))]
      (is (str/includes? edited "; alias comment\n"))
      (is (= [:ref :child] (get-in (edn/read-string edited) [:fns 0 :bind :value])))
      (is (= :already-applied (:status (cst/apply-text plan edited)))))))


(deftest scoped-input-keys-retain-argument-comments-and-literal-bytes
  (let [raw "{:namespace \"demo.main\" :fns [{:name :host :parent :base :args {:left :reader, :deep ;; free reader input\r\n  \"x\\n y\"}}]}"
        operations [{:op :rename-bind :definition :host :path [:args]}
                    {:op :wrap-ref :definition :host :path [:args :left]}
                    {:op :scope-key :definition :host :path [:args :deep] :public-path [:left :deep]}
                    {:op :wrap-value :definition :host :path [:args :deep]}]
        plan (cst/plan raw {:namespace "demo.main" :operations operations})
        edited (:text (cst/apply-text plan raw))]
    (is (str/includes? edited "[:left :deep] ;; free reader input\r\n  [:value \"x\\n y\"]"))
    (is (= :already-applied (:status (cst/apply-text plan edited))))
    (is (= :invalid-scoped-path (reason #(cst/plan raw {:namespace "demo.main"
                                                        :operations [(assoc (nth operations 2) :public-path [:left :wrong])]}))))))


(deftest inline-scoped-keys-retain-original-source-and-comments
  (let [raw "{:namespace \"demo.main\" :fns [{:name :host :parent :base :args {:value {:parent :reader :args {:left :child :flag ;; bool field\r\n {:value false}}}}}]}"
        original (get-in (edn/read-string raw) [:fns 0 :args :value])
        path [:left :flag]
        proposal {:bind (array-map :left [:ref :child] path [:value false])
                  :legacy-scope-names {path :flag}}
        body (format/migrate-inline original proposal)
        patch (cst/plan raw (request {:op :rename-bind :definition :host :path [:args]}
                                     {:op :inline-ref :definition :host :path [:args :value] :body body}))
        edited (:text (cst/apply-text patch raw))]
    (is (= original (format/inline-origin body)))
    (is (= body (get-in (edn/read-string edited) [:fns 0 :bind :value 1])))
    (is (str/includes? edited "[:left :flag] ;; bool field\r\n [:value false]"))
    (is (= :already-applied (:status (cst/apply-text patch edited))))
    (is (nil? (format/inline-origin (dissoc body :legacy-scope-names))))
    (is (nil? (format/inline-origin (assoc-in body [:bind path] false))))
    (is (nil? (format/inline-origin (assoc-in body [:legacy-scope-names path] :left))))
    (is (nil? (format/inline-origin (assoc-in body [:legacy-scope-names [:other :flag]] :other))))
    (is (= (assoc-in original [:args :flag] false)
           (format/inline-origin (format/migrate-inline (assoc-in original [:args :flag] false) proposal))))))


(deftest scoped-inline-annotations-keep-comments-and-the-legacy-name
  (let [raw "{:namespace \"demo.main\" :fns [{:name :host :parent :base :args {:value {:parent :reader :args {:flag ;; binding comment\r\n {:as :flag :type :bool :description \"Switch\"}}}}}]}"
        original (get-in (edn/read-string raw) [:fns 0 :args :value])
        path [:left :flag]
        proposal {:configure {path {:type :bool :description "Switch"}} :legacy-scope-names {path :flag}}
        body (format/migrate-inline original proposal)
        patch (cst/plan raw (request {:op :rename-bind :definition :host :path [:args]}
                                     {:op :inline-ref :definition :host :path [:args :value] :body body}))
        edited (:text (cst/apply-text patch raw))]
    (is (= original (format/inline-origin body)))
    (is (= body (get-in (edn/read-string edited) [:fns 0 :bind :value 1])))
    (is (str/includes? edited "[:left :flag] ;; binding comment\r\n"))
    (is (= :already-applied (:status (cst/apply-text patch edited))))
    (is (nil? (format/inline-origin (dissoc body :legacy-scope-names))))))


(deftest mixed-inline-fields-retain-comments-and-original-argument-order
  (let [raw (str "{:namespace \"demo.main\" :fns [{:name :host :parent :base "
                 ":args {:value {:parent :reader :args {:before {:value false} ; binding tail\r\n"
                 " ; configure field\r\n :flag {:as :flag :type :bool}"
                 " :after {:value 1}}}}}]}")
        original (get-in (edn/read-string raw) [:fns 0 :args :value])
        body (format/migrate-inline original {:bind {:before [:value false] :after [:value 1]}
                                              :configure {[:flag] {:type :bool}}})
        patch (cst/plan raw (request {:op :rename-bind :definition :host :path [:args]}
                                     {:op :inline-ref :definition :host :path [:args :value] :body body}))
        edited (:text (cst/apply-text patch raw))
        emitted (get-in (edn/read-string edited) [:fns 0 :bind :value 1])]
    (is (= body emitted))
    (is (= [:before :flag :after] (vec (keys (:args (format/inline-origin emitted))))))
    (is (str/includes? edited "[:value false] ; binding tail\r\n"))
    (is (str/includes? edited "; configure field\r\n [:flag]"))
    (is (= :already-applied (:status (cst/apply-text patch edited))))))


(deftest scoped-metadata-only-fields-are-written-at-the-confirmed-path
  (let [raw (str "{:namespace \"demo.main\" :fns [{:name :host :parent :wrapper "
                 ":args {:flag ; retained field comment\r\n {:as :flag :type :bool}}}]}")
        original (first (:fns (edn/read-string raw)))
        path [:child :flag]
        proposed (-> original (dissoc :args) (assoc :bind {} :configure {path {:type :bool}}))
        edge-id (random-uuid)
        slot-id (random-uuid)
        binding-id (random-uuid)
        certificate {:source-path [:args :flag] :public-path path :configuration {:type :bool}
                     :edges [{:binding-id edge-id}]
                     :row {:id binding-id :target-path [slot-id] :scope-path [edge-id]}}
        argument {:source-path [:args :flag] :origin {:slot-id slot-id :binding-id binding-id}
                  :scope-certificate certificate}
        entry {:source original :proposed proposed :kind :composed :status :supported
               :legacy {:arguments [argument]}}
        edit (cst/plan raw {:namespace "demo.main" :operations (cst/entry-operations entry)})
        target (:target-text edit)]
    (is (= proposed (first (:fns (edn/read-string target)))))
    (is (str/includes? target "[:child :flag] ; retained field comment\r\n"))
    (is (= :already-applied (:status (cst/apply-text edit target))))))


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


(deftest explicit-scalar-literal-markers-preserve-inline-identity-and-comments
  (let [text (str "{:namespace \"demo.main\" :fns ["
                  "{:name :sink :args {:x :any :y :any} :return-type :any} "
                  "{:name :child :parent :sink :args "
                  "{:x {:parent :sink :args {:x {:literal? ; inline marker\r\n true :value \"a\r\nb\"}}}"
                  " :y {:value ; outer payload\r\n :sink :literal? true}}}]}")
        target (migrate-child text)
        child (peek (:fns (edn/read-string target)))]
    (is (= [:value :sink] (get-in child [:bind :y])))
    (is (= [:value "a\r\nb"] (get-in child [:bind :x 1 :bind :x])))
    (is (= :literal-marker-first (get-in child [:bind :x 1 :legacy-forms :x])))
    (is (str/includes? target "; inline marker\r\n"))
    (is (str/includes? target "; outer payload\r\n"))
    (is (not (str/includes? target ":literal?")))))


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


(deftest simultaneous-renames-keep-each-pin-and-comment
  (let [text "{:namespace \"demo.main\" :fns [{:name :sink :args {:a {:type :int} :b {:type :text}} :return-type :any} {:name :aliases :parent :sink :args {:a {:as :n :type ; number pin\r\n :int} :b {:as ; name route\r\n :label :type :text}}}]}"
        target (migrate-child text)
        rewritten (peek (:fns (edn/read-string target)))]
    (is (= {:n {:type :int :required false} :label {:type :text :required false}} (:input rewritten)))
    (is (= {:a [:project [:n]] :b [:project [:label]]} (:bind rewritten)))
    (is (= {[:a] {:type :int} [:b] {:type :text}} (:configure rewritten)))
    (doseq [annotation ["; number pin\r\n" "; name route\r\n"]]
      (is (= 1 (count (re-seq (re-pattern annotation) target)))))))


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


(deftest list-literal-marker-is-removed-without-changing-data-or-comments
  (let [text (str "{:namespace \"demo.main\" :fns ["
                  "{:name :sink :args {:xs {:type [:list :any]}} :return-type :any} "
                  "{:name :read :args {} :return-type :int} "
                  "{:name :child :parent :sink :args {:xs ["
                  "{:literal? ; first marker\r\n true :value :read} "
                  "{:value ; multi-line payload\r\n \"a\r\nb\" ; second marker\r\n :literal? true}]}}]}")
        definitions (mapv #(assoc % :namespace "demo.main") (:fns (edn/read-string text)))
        ctx (parse/prepare-module definitions {} {})
        plan (source/verify-module (source/plan-module definitions ctx) ctx)
        entry (peek (:definitions plan))
        fragment (-> entry :legacy :arguments first :list-builder :fragments peek)
        op (cst/list-builder-operation (peek definitions) :xs fragment (:name->id ctx))
        patch (cst/plan text (request {:op :rename-bind :definition :child :path [:args]} op))
        after (:text (cst/apply-text patch text))]
    (is (= :authored-source-equivalent (:verification entry)))
    (is (= [[:value :read] [:value "a\r\nb"]]
           (get-in (edn/read-string after) [:fns 2 :bind :xs 1 :items])))
    (is (str/includes? after "; first marker\r\n"))
    (is (str/includes? after "; second marker\r\n"))
    (is (str/includes? after "; multi-line payload\r\n \"a\r\nb\""))
    (is (not (str/includes? after ":literal?")))
    (is (= :already-applied (:status (cst/apply-text patch after))))))


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


(deftest generic-projection-retains-the-authored-pin-and-variable-declaration
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :collect :args {:xs {:type [:list a]}} :return-type :any}\r\n"
                  "{:name :child :parent :collect :args {:xs {:as ; alias\r\n :items}}}]}")
        target (migrate-child text)
        child (peek (:fns (edn/read-string target)))]
    (is (str/includes? target "; alias\r\n"))
    (is (= {:items {:type '[:list a] :required false}} (:input child)))
    (is (= {:xs [:project [:items]]} (:bind child)))))


(deftest inline-list-cst-keeps-comments-and-two-identical-bodies
  (let [body "{:args {:x {:ref ; qualified\r\n :demo.main/producer}} :parent :sink}"
        text (str "{:namespace \"demo.main\" :fns [{:name :sink :args {:x :any :items :sequence} :return-type :any}"
                  "{:name :producer :return-type :any}"
                  "{:name :child :parent :sink :args {:items {:closed true :append [nil ; first item\r\n "
                  body " ; second item\r\n " body "]}}}]}")
        target (migrate-child text)]
    (is (str/includes? target "[:value nil] ; first item\r\n [:ref"))
    (is (str/includes? target " ; second item\r\n [:ref"))
    (is (str/includes? target ":x [:ref ; qualified\r\n :demo.main/producer]"))
    (is (str/includes? target ":tail :closed"))
    (is (not (str/includes? target "_anon-")))))


(deftest nested-inline-list-cst-uses-the-original-append-and-item-spans
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :sink :args {:x :any :items :sequence} :return-type :any}"
                  "{:name :producer :return-type :any}"
                  "{:name :child :parent :sink :args {:items [{:parent :sink :args {:items "
                  "{:closed false ; keep tail\r\n :append [{:value nil :literal? true}"
                  " {:literal? ; literal first\r\n true :value :producer} {:ref ; qualified\r\n :demo.main/producer}"
                  " {:parent :sink :args {:x 7}} {:type ; positional type\r\n :text :as :pin}]}}}]}}]}")
        target (migrate-child text)]
    (is (str/includes? target ":tail :open ; keep tail\r\n :items"))
    (is (str/includes? target "[:ref ; qualified\r\n :demo.main/producer]"))
    (is (str/includes? target ":legacy-forms {:items [:list-origin [:closed :append]"))
    (is (str/includes? target ":input {:pin {:type :text, :required false}}"))
    (is (str/includes? target "; positional type\r\n"))
    (is (str/includes? target "; literal first\r\n"))
    (is (str/includes? target ":literal-marker-first"))
    (is (str/includes? target ":literal-marker-last"))
    (is (not (str/includes? target "_anon-")))))


(deftest whole-module-patch-requires-complete-authored-replay
  (doseq [header ["" ":input-format 1 "]]
    (let [text (str "; comment before module\r\n{" header ":namespace \"demo.main\" :fns ["
                    "{:name :base :args {:x {:type :text}} :return-type :text}\r\n"
                    "{:name :child :parent :base :args {:x ; literal comment\r\n"
                    "\"first\r\nsecond\"}}]}\r\n")
          definitions (mapv #(assoc % :namespace "demo.main") (:fns (edn/read-string text)))
          ctx (parse/prepare-module definitions {} {})
          verify (fn [entries] (:definitions (source/verify-module {:definitions entries} ctx)))
          entries (verify (:definitions (source/plan-module definitions ctx)))
          patch (cst/plan-verified-module text entries verify)
          target (:target-text patch)]
      (is (= 2 (:input-format (edn/read-string target))))
      (is (str/includes? target "; literal comment\r\n[:value \"first\r\nsecond\"]"))
      (is (= {:status :already-applied :text target} (cst/apply-text patch target)))
      (is (= :whole-module-proof-required
             (reason #(cst/plan-verified-module text (pop entries) verify))))
      (is (= :whole-module-proof-required
             (reason #(cst/plan-verified-module text entries (constantly []))))))))


(deftest certified-forwarding-is-inserted-without-reprinting-the-authored-fields
  (let [text (str "{:namespace \"demo.main\" :fns [{:name :response :parent :base\r\n"
                  " :args {:left :encoder ; reference comment\r\n"
                  "        :right :other\r\n"
                  "        :data ; field comment\r\n        {:type :text}}}]}\r\n")
        forwarding {[:left :nested :data] [:project [:data]]
                    [:right :data] [:project [:data]]}
        operation {:op :partition-composed-args :definition :response :path [:args]
                   :declarations [:data] :operators {:left :wrap-ref :right :wrap-ref} :forwarded-bindings forwarding}
        patch (cst/plan text {:namespace "demo.main" :operations [operation]})
        target (:target-text patch)
        rewritten (first (:fns (edn/read-string target)))]
    (is (= {:data {:type :text}} (:input rewritten)))
    (is (= (assoc forwarding :left [:ref :encoder] :right [:ref :other]) (:bind rewritten)))
    (is (str/includes? target "; reference comment\r\n"))
    (is (str/includes? target "; field comment\r\n"))
    (is (= {:status :already-applied :text target} (cst/apply-text patch target)))))


(deftest metadata-only-self-pin-preserves-comments-and-the-original-callable-shape
  (let [text "{:namespace \"x\" :fns [{:name :put :args {:value :any} :return-type :any}\r\n{:name :put-fn :parent :put :args {:value ; target\r\n {:type [:fn {:arg b} c] :as ; identical name\r\n :value}}}]}"
        defs (:fns (edn/read-string text))
        originals (mapv #(assoc % :namespace "x") defs)
        prepared (parse/prepare-module originals {} {})
        verified (last (:definitions (source/verify-module (source/plan-module originals prepared) prepared)))
        patch (cst/plan text {:namespace "x" :operations (cst/entry-operations verified)})
        target (:text (cst/apply-text patch text))
        rewritten (last (:fns (edn/read-string target)))]
    (is (= :authored-source-equivalent (:verification verified)))
    (is (= '{[:value] {:type [:fn {:arg b} c]}} (:configure rewritten)))
    (is (not-any? #(contains? rewritten %) [:args :input :bind]))
    (is (str/includes? target "; target\r\n"))
    (is (str/includes? target "; identical name\r\n"))
    (is (= :already-applied (:status (cst/apply-text patch target))))))


(deftest free-annotations-preserve-comments-payloads-and-crlf-without-moving-entries-test
  (let [text "{:namespace \"demo.main\" :fns [{:name :child :parent :base :args ; arguments\r\n {:title {:type :text :as ; identity\r\n          :title :description \"multi\r\nline {:as :title}\"}\r\n  :head {:as :head :type [:list :text] :description \"Head\"}}}]}\r\n"
        raw (first (:fns (edn/read-string text)))
        defs [{:name :base :namespace "demo.main" :args {:title :text :head {:type [:list :text]}} :return-type :text}
              (assoc raw :namespace "demo.main")]
        ctx (parse/prepare-module defs {} {})
        entry (last (:definitions (source/verify-module (source/plan-module defs ctx) ctx)))
        patch (cst/plan text (apply request (cst/entry-operations entry)))
        expected (-> text
                     (str/replace ":args ; arguments" ":configure ; arguments")
                     (str/replace "{:title {" "{[:title] {")
                     (str/replace ":type :text :as ; identity\r\n          :title" ":type :text  ; identity\r\n          ")
                     (str/replace ":head {:as :head" "[:head] { "))]
    (is (= :authored-source-equivalent (:verification entry)) (:blockers entry))
    (is (= expected (:target-text patch)))
    (is (= (:configure (:proposed entry)) (get-in (edn/read-string expected) [:fns 0 :configure])))
    (is (= :already-applied (:status (cst/apply-text patch expected))))))


(deftest annotation-only-inline-keeps-its-original-uuid-and-spelling-test
  (doseq [spec ["{:as ; same name\r\n :title :description \"Title\" :type :text}"
                "{:as ; same name\r\n :title}"]]
    (let [text (str "{:namespace \"demo.main\" :fns [{:name :child :parent :sink :args {:value {:parent :base :args ; inline args\r\n {:title " spec "}}}}]}")
          raw (first (:fns (edn/read-string text)))
          defs [{:name :base :namespace "demo.main" :args {:title :text} :return-type :text}
                {:name :sink :namespace "demo.main" :args {:value :text} :return-type :text}
                (assoc raw :namespace "demo.main")]
          ctx (parse/prepare-module defs {} {})
          entry (last (:definitions (source/verify-module (source/plan-module defs ctx) ctx)))
          patch (cst/plan text (apply request (cst/entry-operations entry)))
          target (:target-text patch)
          proposal (assoc (first (:fns (edn/read-string target))) :namespace "demo.main")
          replay (first (:definitions (source/verify-module {:definitions [(assoc entry :proposed proposal)]} ctx)))]
      (is (= :authored-source-equivalent (:verification entry)) (:blockers entry))
      (is (= :authored-source-equivalent (:verification replay)) (:blockers replay))
      (is (= (:identity entry) (:identity replay)))
      (is (= (get-in entry [:v2 :inline-definitions]) (get-in replay [:v2 :inline-definitions])))
      (is (str/includes? target "; inline args\r\n"))
      (is (str/includes? target "; same name\r\n"))
      (is (= :already-applied (:status (cst/apply-text patch target)))))))


(deftest described-projection-preserves-metadata-comments-and-multiline-bytes
  (let [description "\"first\r\nline {:as :data :args :data}\""
        text (str "{:namespace \"demo.main\" :fns [{:name :base :args {:value :any} :return-type :any}\r\n"
                  "{:name :child :parent :base :args {:value {; alias header\r\n"
                  " :description " description " ; description tail\r\n"
                  " :as ; alias spelling\r\n :body :type ; type spelling\r\n :text}}}]}\r\n")
        target (migrate-child text)
        child (peek (:fns (edn/read-string target)))]
    (is (= {:body {:type :text :required false}} (:input child)))
    (is (= {:value [:project [:body]]} (:bind child)))
    (is (= {[:value] {:type :text :description (edn/read-string description)}} (:configure child)))
    (doseq [original-bytes [description "; alias header\r\n" "; description tail\r\n" "; alias spelling\r\n" "; type spelling\r\n"]]
      (is (str/includes? target original-bytes)))))


(deftest inline-description-remains-part-of-the-inline-declaration
  (let [text "{:namespace \"demo.main\" :fns [{:name :base :args {:value :any} :return-type :any} {:name :child :parent :base :args {:value {:parent :base :description \"Function documentation\"}}}]}"
        target (migrate-child text)
        child (peek (:fns (edn/read-string target)))]
    (is (= "Function documentation" (get-in child [:bind :value 1 :description])))
    (is (not (contains? child :configure)))))


(deftest description-only-projection-keeps-its-configuration-without-a-pin
  (let [text "{:namespace \"demo.main\" :fns [{:name :base :args {:value :any} :return-type :any} {:name :child :parent :base :args {:value {:description \"Body\" :as :body}}}]}"
        target (migrate-child text)
        child (peek (:fns (edn/read-string target)))]
    (is (= {:body {:type :any :required false}} (:input child)))
    (is (= {[:value] {:description "Body"}} (:configure child)))))


(deftest mixed-free-annotations-stage-with-value-bindings-and-original-comments
  (let [text (str "{:namespace \"demo.main\" :fns [\r\n"
                  " {:name :base :args {:target :any :request :any} :return-type :any}\r\n"
                  " {:name :resolve :args {} :return-type :any}\r\n"
                  " {:name :route :parent :base :args ; call inputs\r\n"
                  "  {:target :resolve ; value binding\r\n"
                  "   ; request configuration\r\n"
                  "   :request {:as ; same public name\r\n"
                  "             :request :description \"Inbound\r\nrequest\"}}}]}\r\n")
        definitions (mapv #(assoc % :namespace "demo.main") (:fns (edn/read-string text)))
        prepared (parse/prepare-module definitions {} {})
        verify (fn [entries] (:definitions (source/verify-module {:definitions entries} prepared)))
        entries (verify (:definitions (source/plan-module definitions prepared)))
        patch (cst/plan-verified-module text entries verify)
        applied (cst/apply-text patch text)
        target (:text applied)
        route (peek (:fns (edn/read-string target)))]
    (is (= :applied (:status applied)))
    (is (= {:target [:ref :resolve]} (:bind route)))
    (is (= {[:request] {:description "Inbound\r\nrequest"}} (:configure route)))
    (is (not (contains? route :args)))
    (doseq [comment-text ["; call inputs\r\n" "; value binding\r\n" "; request configuration\r\n" "; same public name\r\n"]]
      (is (= 1 (count (re-seq (re-pattern comment-text) target)))))
    (is (= :already-applied (:status (cst/apply-text patch target))))))


(deftest typed-list-reference-preserves-its-occurrence-and-source-comments
  (let [text (str "{:namespace \"demo.main\" :fns ["
                  "{:name :sink :args {:xs {:type [:list :any]}} :return-type :any} "
                  "{:name :read :args {} :return-type :int} "
                  "{:name :child :parent :sink :args {:xs [{:type ; precision\r\n :int :ref ; source\r\n :read}]}}]}")
        definitions (mapv #(assoc % :namespace "demo.main") (:fns (edn/read-string text)))
        ctx (parse/prepare-module definitions {} {})
        plan (source/verify-module (source/plan-module definitions ctx) ctx)
        entry (peek (:definitions plan))
        fragment (-> entry :legacy :arguments first :list-builder :fragments peek)
        op (cst/list-builder-operation (peek definitions) :xs fragment (:name->id ctx))
        patch (cst/plan text (request {:op :rename-bind :definition :child :path [:args]} op))
        after (:text (cst/apply-text patch text))
        item (first (:items fragment))]
    (is (= :authored-source-equivalent (:verification entry)))
    (is (= (ids/binding-list-item-id (:binding-id fragment) 0) (:id item)))
    (is (= [[:ref :read {:type :int}]] (get-in (edn/read-string after) [:fns 2 :bind :xs 1 :items])))
    (is (str/includes? after "; precision\r\n"))
    (is (str/includes? after "; source\r\n"))
    (is (= :already-applied (:status (cst/apply-text patch after))))))


(deftest typed-list-refs-require-a-compatible-declared-return
  (let [source [{:namespace "demo" :name :typed-producer :args {} :return-type [:union :null :int]}
                {:namespace "demo" :name :sink :args {:xs {:type [:list :int]}} :return-type :any}
                {:namespace "demo" :name :child :parent :sink :args {:xs [{:ref :typed-producer :type :int}]}}]
        ctx (parse/prepare-module source {} {})
        entry (peek (:definitions (source/plan-module source ctx)))]
    (is (some #{:list-reference-type-evidence-required} (map :code (:blockers entry))))))


(deftest enclosing-query-replaces-args-with-the-verified-bind
  (let [source {:name :has-id? :parent :equal? :args {:a {:parent :query-param} :b "match"}}
        proposed {:name :has-id? :parent :equal? :bind {:a [:ref :_query-child] :b [:value "match"]}}
        text (pr-str {:namespace "fixture" :fns [source]})]
    (doseq [kind [:query-lookup-inline :response-signature-test]]
      (let [operations (cst/entry-operations {:status :supported :kind kind :source source :proposed proposed})
            result (cst/plan text {:namespace "fixture" :operations operations})]
        (is (= proposed (first (:fns (edn/read-string (:target-text result))))))))))


(let [{:keys [fail error]} (run-tests 'structured-input-cst-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
