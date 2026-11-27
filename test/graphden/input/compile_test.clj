(ns graphden.input.compile-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.runtime :as runtime]
    [graphden.input.analysis :as analysis]
    [graphden.input.browser :as browser]
    [graphden.input.compile :as compile]
    [graphden.input.compiler-fixture :as fixture]
    [graphden.input.obligations :as obligations]
    [graphden.input.runtime :as input]
    [graphden.packages.records.ids :as ids]))


(use-fixtures :each
  (fn [test-fn]
    (binding [registry/*rich-types-override* (atom {})
              eager/*always-fresh-fn-ids* (atom #{})]
      (test-fn))))


(defbase pair [left right]
  [left right])


(defbase read-user [user]
  (let [{:keys [name]} user]
    [name (count user) (set (keys user))]))


(defbase echo [x]
  x)


(defn- execute-context
  [root closure]
  {:compiled-registry (atom {(fixture/id root) closure})
   :graph-cache (atom {:fns [{:id (fixture/id root) :name (name root) :parent-ids []}]
                       :slots [] :fn-slots [] :bindings [] :list-items []})
   :base-fns {}})


(defn- compiled
  [definitions functions]
  (let [{:keys [context analysis]} (fixture/model definitions :root)]
    {:analysis analysis :context context
     :closure (compile/compile-checked analysis
                                       {:context context
                                        :implementations (fixture/implementations definitions functions)})}))


(defn- legacy-closure
  "Independent legacy compilation over the same FLAT declarations. Never
   shares a runtime context or memo with the canonical compiler under test."
  ([definitions functions] (legacy-closure definitions functions {}))
  ([definitions functions {:keys [extra-bindings extra-refs]}]
   (let [{:keys [context]} (fixture/model definitions :root)
         graph {:fns (into (mapv (fn [[name id]] {:id id :name (clojure.core/name name)})
                                 (ids/primitive-fn-ids)) (vals (:fns context)))
                :slots (vec (vals (:fields context)))
                :fn-slots (vec (mapcat (fn [[fn-id row]]
                                         (map #(assoc % :id (random-uuid) :fn-id fn-id)
                                              (get-in context [:members (:input-type-fn-id row)]))) (:fns context)))
                :bindings (into (mapv #(assoc % :slot-id (first (:target-path %))) (vals (:bindings context)))
                                extra-bindings)
                :list-items []}
         lookup (assoc (lookups/build-lookups graph) :base-fns functions)
         registry (atom {})]
     (letfn [(build
               [owner]
               (let [id (fixture/id owner)]
                 (or (get @registry id)
                     (let [children (into {} (keep (fn [expression]
                                                     (when-let [target (:ref expression)]
                                                       [(fixture/id target) (build target)])))
                                          (concat (vals (get-in definitions [owner :bindings]))
                                                  (for [target (get extra-refs owner)] {:ref target})))
                           closure (eager/compile-fn id lookup children)]
                       (swap! registry assoc id closure)
                       closure))))]
       (build :root)))))


(defn- invoke
  ([closure] (invoke closure {} {}))
  ([closure supplied extra-context]
   (executor/execute (merge (execute-context :root closure) extra-context)
                     (fixture/id :root) supplied)))


(defn- rejection-code
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo failure (:code (ex-data failure)))))


(deftest authored-callable-parameter-can-be-read-inside-a-reference
  (let [definitions {:root {:input {:callback [:fn {:item :text} :text #{}]} :return :text
                            :bindings {[:callback] {:ref :child :id :callback-use}}}
                     :child {:input {:body :text} :return :text :lambda-params [:item]
                             :bindings {[:body] {:ref :reader :id :read-item}}}
                     :reader {:input {:item :text} :return :text}}
        functions {:root (fn [args _] ((runtime/resolve-arg args :callback) "through the reference"))
                   :child (fn [args _] (runtime/resolve-arg args :body))
                   :reader (fn [args _] (runtime/resolve-arg args :item))}
        {:keys [analysis closure]} (compiled definitions functions)
        address (fixture/address :root [] :root [:callback])
        item (fixture/address :root [:callback-use :read-item] :reader [:item])
        competing (-> definitions
                      (assoc :other {:input {:item :text} :return :text})
                      (assoc-in [:child :input :other] :text)
                      (assoc-in [:child :bindings [:other]] {:ref :other}))]
    (is (= "through the reference" (invoke closure)))
    (is (= [{:parameter :item :target item :value-path []}]
           (get-in analysis [:closures address :call-sites])))
    (is (= :invalid-lambda-params (rejection-code #(fixture/model competing :root))))))


(deftest graph-callable-reuses-captured-cells-and-freshens-equal-call-inputs
  (let [events (atom [])
        definitions {:root {:input {:callback [:fn {:item :any} :any #{}] :config :any} :return :any
                            :bindings {[:callback] {:ref :child :id :callback-use}
                                       [:config] {:ref :source}}}
                     :child {:input {:item :any :config :any :computed :any} :return :any
                             :field-origins {[:config] :root}
                             :bindings {[:computed] {:ref :local}}}
                     :local {:input {:item :any} :return :any :field-origins {[:item] :child}}
                     :source {:input {} :return :any}}
        functions {:root (fn [args _]
                           (let [callback (runtime/resolve-arg args :callback)]
                             (swap! events conj :ready)
                             [(select-keys (meta callback)
                                           [:graphden.executor/fn-id :graphden.executor/lambda-params])
                              (mapv callback [nil false false])]))
                   :child (fn [args _]
                            [(runtime/resolve-arg args :config)
                             (runtime/resolve-arg args :item)
                             (runtime/resolve-arg args :computed)
                             (runtime/resolve-arg args :computed)])
                   :local (fn [args _]
                            (let [item (runtime/resolve-arg args :item)]
                              (swap! events conj [:callback item]) item))
                   :source (fn [_ _] (swap! events conj :capture) false)}
        {:keys [analysis context closure]} (compiled definitions functions)
        [metadata values] (invoke closure)]
    (is (= {:graphden.executor/fn-id (fixture/id :child) :graphden.executor/lambda-params [:item]} metadata))
    (is (= [[false nil nil nil] [false false false false] [false false false false]] values))
    (is (= [:ready :capture [:callback nil] [:callback false] [:callback false]] @events))
    (is (= :unsupported-browser-primitive
           (rejection-code #(browser/export-named analysis
                                                  {:snapshot-id (:snapshot-id context)
                                                   :allow-fn? (constantly true)
                                                   :implementations (fixture/implementations definitions functions)}))))))


(deftest exposed-callback-capture-keeps-its-scope-and-surrounding-invocation
  (let [definitions {:root {:input {:style {:callback [:fn {:item :text} :text #{}] :mark :text}}
                            :return :any :bindings {[:style :callback] {:ref :child :id :callback-use}}}
                     :child {:input {:item :text :mark :text} :return :text}}
        functions {:root (fn [args _]
                           (let [callback (:callback (runtime/resolve-arg args :style))]
                             [(callback "Ada") (callback "Ben")]))
                   :child (fn [args _]
                            (str (runtime/resolve-arg args :item)
                                 (runtime/resolve-arg args :mark)))}
        {:keys [analysis context]} (fixture/model definitions :root)
        callback (compile/compile-named analysis {:context context
                                                  :implementations (fixture/implementations definitions functions)})
        arguments (fn [mark] {:style {:mark "independent outer field" :callback {:mark mark}}})]
    (is (= ["Ada!" "Ben!"] (invoke callback (arguments "!") {})))
    (is (= ["Ada?" "Ben?"] (invoke callback (arguments "?") {})))
    (is (= ["Ada!" "Ben!"] (invoke callback (arguments "!") {})))
    (is (= :missing-required-input
           (rejection-code #(invoke callback {:style {:mark "not a capture"}} {}))))))


(deftest callback-routes-a-single-record-argument-by-declared-fields
  (let [item {:value :any :unused :any :details {:flag :any :note :any}}
        definitions {:root {:input {:callback [:fn {:item item} :any #{}]} :return :any
                            :bindings {[:callback] {:ref :child}}}
                     :child {:input {:item item} :return :any
                             :optional #{[:item :unused] [:item :details] [:item :details :note]}}}
        {:keys [closure]} (compiled definitions
                                    {:root (fn [args _]
                                             (let [callback (runtime/resolve-arg args :callback)]
                                               [(callback {:value nil})
                                                (callback {:value false :details {:flag nil :note false}})
                                                (callback {:value "next" :details {:flag true}})
                                                (callback (input/record-view
                                                            {:value (input/value-cell "lazy")
                                                             :unused (input/field-cell #(throw (ex-info "Unused field" {})))}))
                                                (rejection-code #(callback {:details {:flag true}}))
                                                (rejection-code #(callback {:value nil :details {}}))
                                                (rejection-code #(callback {:value nil :details {:extra 1}}))
                                                (rejection-code #(callback {:value nil :details nil}))
                                                (rejection-code #(callback 1))]))
                                     :child (fn [args _]
                                              (let [{:keys [value details] :as item} (runtime/resolve-arg args :item)]
                                                [value (contains? item :details) (:flag details)
                                                 (contains? details :note) (:note details)]))})]
    (is (= [[nil false nil false nil]
            [false true nil true false]
            ["next" true true false nil]
            ["lazy" false nil false nil]
            :missing-required-input :missing-required-input :unknown-input-field
            :record-input-required :record-input-required]
           (invoke closure)))))


(deftest callback-map-presence-and-lifetime-remain-explicit
  (let [definitions {:root {:input {:callback [:fn {:item :any :flag :any} :any #{}]} :return :any
                            :bindings {[:callback] {:ref :child}}}
                     :child {:input {:item :any :flag :any} :optional #{[:flag]} :return :any}}
        {:keys [closure]} (compiled definitions
                                    {:root (fn [args _]
                                             (let [callback (runtime/resolve-arg args :callback)]
                                               [callback [(callback {:item nil})
                                                          (callback {:item false :flag nil})
                                                          (callback {:item nil :flag false})
                                                          (rejection-code #(callback {:flag false}))
                                                          (rejection-code #(callback {:item true :extra 1}))]]))
                                     :child (fn [args _]
                                              [(runtime/resolve-arg args :item)
                                               (contains? args :flag)
                                               (runtime/resolve-arg args :flag)])})
        [callback results] (invoke closure)]
    (is (= [[nil false nil] [false true nil] [nil true false]
            :missing-required-input :unknown-callback-parameter] results))
    (is (= :callback-outside-invocation (rejection-code #(callback {:item false}))))))


(deftest zero-argument-callback-uses-the-legacy-variadic-call-shape
  (let [definitions {:root {:input {:callback [:fn {} :any #{}] :config :any} :return :any
                            :bindings {[:callback] {:ref :child} [:config] {:value nil}}}
                     :child {:input {:config :any} :return :any :lambda-params []
                             :field-origins {[:config] :root}}}
        {:keys [closure]} (compiled definitions
                                    {:root (fn [args _]
                                             (let [callback (runtime/resolve-arg args :callback)]
                                               [(callback) (callback false)
                                                @(future (rejection-code callback))]))
                                     :child (fn [args _]
                                              [(contains? args :config) (runtime/resolve-arg args :config)])})]
    (is (= [[true nil] [true nil] :callback-outside-invocation] (invoke closure)))))


(deftest unopened-callback-local-cell-cannot-run-after-the-root-returns
  (let [runs (atom 0)
        definitions {:root {:input {:callback [:fn {:item :any} :any #{}]} :return :any
                            :bindings {[:callback] {:ref :child}}}
                     :child {:input {:item :any :payload {:late :int}} :return :any
                             :bindings {[:payload :late] {:ref :local}}}
                     :local {:input {} :return :int}}
        {:keys [closure]} (compiled definitions
                                    {:root (fn [args _] ((runtime/resolve-arg args :callback) true))
                                     :child (fn [args _] (runtime/resolve-arg args :payload))
                                     :local (fn [_ _] (swap! runs inc))})
        payload (invoke closure)]
    (is (= :callback-outside-invocation (rejection-code #(:late payload))))
    (is (zero? @runs))))


(deftest nested-fields-remain-lazy-through-real-base-abi
  (let [reads (atom [])
        definitions {:root {:input {:user {:name :text :unused :text}} :return :any
                            :bindings {[:user :name] {:ref :name} [:user :unused] {:ref :unused}}}
                     :name {:input {} :return :text}
                     :unused {:input {} :return :text}}
        functions {:root read-user
                   :name (fn [_ _] (swap! reads conj :name) "Ada")
                   :unused (fn [_ _] (swap! reads conj :unused) (throw (Exception. "unread")))}
        {:keys [closure]} (compiled definitions functions)]
    (is (= ["Ada" 2 #{:name :unused}] (invoke closure)))
    (is (= [:name] @reads))
    (is (= ["Ada" 2 #{:name :unused}] (invoke closure)))
    (is (= [:name :name] @reads))))


(deftest named-shared-ref-keeps-value-based-memo-across-use-scopes
  (let [definitions {:root {:input {:left :text :right :text} :return :any
                            :bindings {[:left] {:ref :child :id :left-use}
                                       [:right] {:ref :child :id :right-use}}}
                     :child {:input {:x :text} :return :text}}
        run-new (fn [a b]
                  (let [calls (atom [])
                        {:keys [closure]} (compiled definitions
                                                    {:root pair :child (fn [args _]
                                                                         (let [x (runtime/resolve-arg args :x)]
                                                                           (swap! calls conj x) x))})]
                    [(invoke closure {(fixture/address :root [:left-use] :child [:x]) a
                                      (fixture/address :root [:right-use] :child [:x]) b} {}) @calls]))
        run-old (fn [x]
                  (let [calls (atom [])
                        closure (legacy-closure definitions
                                                {:root pair :child (fn [args _]
                                                                     (let [x (runtime/resolve-arg args :x)]
                                                                       (swap! calls conj x) x))})]
                    [(invoke closure {:x x} {}) @calls]))]
    (binding [eager/*always-fresh-fn-ids* (atom #{})]
      (is (= (run-old "same") (run-new "same" "same") [["same" "same"] ["same"]]))
      (is (= [["one" "two"] ["one" "two"]] (run-new "one" "two"))))))


(deftest ref-results-are-not-dereferenced-or-called-twice
  (let [definitions {:root {:input {:left :any :right :any} :return :any
                            :bindings {[:left] {:ref :atom} [:right] {:ref :callable}}}
                     :atom {:input {} :return :any}
                     :callable {:input {} :return :any}}
        run (fn [compiler]
              (let [reads (atom 0)
                    value (atom :payload)
                    callable (runtime/thunk #(swap! reads inc))
                    functions {:root pair :atom (fn [_ _] value) :callable (fn [_ _] callable)}
                    closure (compiler definitions functions)
                    result (invoke closure)]
                [(identical? value (first result)) (identical? callable (second result)) @reads]))]
    (is (= [true true 0] (run legacy-closure)
           (run #(-> (compiled %1 %2) :closure))))))


(deftest shared-parent-with-an-authored-computed-input-still-fires-once
  (let [definitions {:root {:input {:left :text :right :text} :return :any
                            :bindings {[:left] {:ref :child} [:right] {:ref :child}}}
                     :child {:input {:x :text} :return :text :bindings {[:x] {:ref :source}}}
                     :source {:input {} :return :text}}
        run (fn [compiler]
              (let [calls (atom [])
                    functions {:root pair
                               :child (fn [args _]
                                        (swap! calls conj :child)
                                        (runtime/resolve-arg args :x))
                               :source (fn [_ _] (swap! calls conj :source) "value")}]
                [(invoke (compiler definitions functions)) @calls]))]
    (doseq [fresh? [false true]]
      (binding [eager/*always-fresh-fn-ids* (atom (if fresh? #{(fixture/id :source)} #{}))]
        (is (= [["value" "value"] [:child :source]]
               (run legacy-closure) (run #(-> (compiled %1 %2) :closure))))))))


(deftest literal-dictionaries-and-explicit-nil-false-stay-opaque
  (doseq [value [nil false {:ref :unknown :value {:nested :payload}}]]
    (let [definitions {:root {:input {:x :any} :return :any :bindings {[:x] {:value value}}}}
          {:keys [closure]} (compiled definitions {:root echo})]
      (is (= value (invoke closure)))
      (is (= value (invoke (legacy-closure definitions {:root echo})))))))


(deftest trace-capture-does-not-consume-a-returned-lazy-record
  (let [reads (atom 0)
        definitions {:root {:input {:x :any} :return :any :bindings {[:x] {:ref :child}}}
                     :child {:input {} :return :any}}
        record (input/record-view {:safe (input/value-cell 1)
                                   :unread (input/field-cell #(do (swap! reads inc)
                                                                  (throw (Exception. "unread"))))})
        {:keys [closure context]} (compiled definitions {:root echo :child (fn [_ _] record)})
        trace (eager/new-path-trace {:capture-values? true})]
    (binding [executor/*path-trace* trace eager/*traced-fn-ids* (atom eager/trace-all)
              registry/*rich-types-override* (atom {:by-id (:signatures context)})]
      (is (identical? record (invoke closure))))
    (is (zero? @reads))
    (is (= 1 (count (:entries @trace))))
    (is (true? (:value-truncated? (first (:entries @trace)))))
    (is (= 1 (:safe record)))
    (is (zero? @reads))))


(deftest trace-rendering-keeps-a-fully-realized-map-result
  (let [value (doall (map inc [1 2 3]))
        captured (eager/render-captured-value value)]
    (is (identical? value (:value captured)))
    (is (= [2 3 4] (:value captured)))
    (is (not (:value-truncated? captured)))))


(deftest existing-secret-and-missing-type-trace-policy-stays-fail-closed
  (let [secret [:secret :text]
        definitions {:root {:input {:x secret} :return secret :bindings {[:x] {:ref :child}}}
                     :child {:input {} :return secret}}
        {:keys [closure context]} (compiled definitions {:root echo :child (fn [_ _] "private-value")})]
    (doseq [entries [{} (:signatures context)]]
      (let [trace (eager/new-path-trace {:capture-values? true})]
        (binding [executor/*path-trace* trace eager/*traced-fn-ids* (atom eager/trace-all)
                  registry/*rich-types-override* (atom {:by-id entries})]
          (is (= "private-value" (invoke closure))))
        (is (= 1 (count (:entries @trace))))
        (is (some? (:hidden (first (:entries @trace)))))
        (is (not-any? #(contains? % :value) (:entries @trace)))))))


(deftest request-cache-override-and-cacheless-handler-policy-use-existing-seam
  (let [calls (atom 0)
        definitions {:root {:input {:left :text :right :text} :return :any
                            :bindings {[:left] {:ref :child} [:right] {:ref :child}}}
                     :child {:input {} :return :text}}
        {:keys [closure]} (compiled definitions {:root pair :child (fn [_ _] (str (swap! calls inc)))})
        cache (java.util.HashMap.)]
    (binding [eager/*request-call-cache* cache]
      (is (= ["1" "1"] (invoke closure)))
      (is (= ["1" "1"] (invoke closure))))
    (is (= 1 @calls))
    (is (= #{(fixture/id :child)} (set (map first (java.util.Map/.keySet cache)))))
    (is (= ["2" "3"] (invoke closure {} (eager/defer-handler-call-cache {}))))
    (binding [eager/*always-fresh-fn-ids* (atom #{(fixture/id :child)})]
      (is (= ["4" "5"] (invoke closure))))))


(deftest free-input-validation-and-execute-guards-are-retained
  (let [calls (atom 0)
        definitions {:root {:input {:x :text} :return :text}}
        {:keys [closure]} (compiled definitions {:root (fn [args _]
                                                         (executor/record-effect! :state)
                                                         (swap! calls inc)
                                                         (runtime/resolve-arg args :x))})
        supplied {(fixture/address :root [] :root [:x]) "valid"}]
    (is (thrown? clojure.lang.ExceptionInfo (invoke closure)))
    (is (thrown? clojure.lang.ExceptionInfo (invoke closure supplied
                                                    {:execute-guard (fn [_ _] (throw (ex-info "denied" {})))})))
    (is (thrown? clojure.lang.ExceptionInfo (invoke closure supplied {:allowed-effects #{}})))
    (binding [executor/*cancel-check* #(throw (InterruptedException. "cancelled"))]
      (is (thrown? InterruptedException (invoke closure supplied {}))))
    (is (zero? @calls))
    (is (= "valid" (invoke closure supplied {})))
    (is (= 1 @calls))))


(deftest primitive-obligations-run-on-field-read-and-cache-failures
  (let [calls (atom 0)
        definitions {:root {:input {:x :text} :return :text :bindings {[:x] {:ref :child}}}
                     :child {:input {} :return :any}}
        functions {:root (fn [args _]
                           (mapv (fn [_]
                                   (try (runtime/resolve-arg args :x)
                                        (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
                                 (range 2)))
                   :child (fn [_ _] (swap! calls inc) 42)}
        {:keys [closure analysis]} (compiled definitions functions)]
    (is (= :checked (:status analysis)))
    (is (= :field-read (:trigger (first (:obligations analysis)))))
    (is (= [:execution-error/type-mismatch :execution-error/type-mismatch] (invoke closure)))
    (is (= 1 @calls))))


(deftest wider-implementation-obligation-keeps-the-narrow-field-read-validator
  (let [definitions {:root {:input {:x :text} :return :text}}
        {:keys [context analysis]} (fixture/model definitions :root)
        address (fixture/address :root [] :root [:x])
        field-check {:address address :code :runtime-type-obligation}
        abi-check (assoc field-check :expected-type :any)
        implementations (fixture/implementations definitions {:root echo})]
    (doseq [diagnostics [[field-check abi-check] [abi-check field-check]]]
      (let [graph (obligations/separate (assoc analysis :diagnostics diagnostics))
            closure (compile/compile-checked graph {:context context :implementations implementations})]
        (is (= "valid" (invoke closure {address "valid"} {})))
        (is (= :execution-error/type-mismatch
               (try (invoke closure {address 42} {})
                    nil
                    (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))))


(deftest computed-list-retains-a-field-read-validator-for-its-union-arm
  (let [collection-type '[:union :null [:list :any] [:map a :any]]
        root {:input {:x collection-type} :return :any :bindings {[:x] {:ref :child}}}
        definitions {:root root :child {:input {} :return [:list :any]}}
        result (atom [1 "two"])
        calls (atom 0)
        functions {:root echo :child (fn [_ _] (swap! calls inc) @result)}
        {:keys [closure analysis]} (compiled definitions functions)
        address (fixture/address :root [] :root [:x])]
    (is (= :field-read (:trigger (first (:obligations analysis)))))
    (is (= address (:address (first (:obligations analysis)))))
    (is (zero? @calls))
    (is (= [1 "two"] (invoke closure)))
    (doseq [invalid [nil {:key "value"} 42]]
      (reset! result invalid)
      (is (= :execution-error/type-mismatch
             (try (invoke closure) nil
                  (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))))
    (is (= 4 @calls))
    (is (= :runtime-obligations-required
           (try (compiled (assoc-in definitions [:child :return] :any) functions) nil
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))
    (let [literal (assoc-in definitions [:root :bindings [:x]] {:value []})]
      (is (= [] (invoke (:closure (compiled literal functions))))))))


(deftest computed-keyword-dictionary-keys-are-validated-when-consumed
  (let [dictionary [:map :keyword :any]
        collection '[:union :null [:map :keyword :any] [:list :any]]
        result (atom {:child nil})
        calls (atom 0)
        functions {:root echo :child (fn [_ _] (swap! calls inc) @result)}]
    (doseq [expected [dictionary collection]]
      (reset! result {:child nil})
      (let [definitions {:root {:input {:x expected} :return :any :bindings {[:x] {:ref :child}}}
                         :child {:input {} :return dictionary}}
            {:keys [closure analysis]} (compiled definitions functions)
            before @calls]
        (is (some #(= :field-read (:trigger %)) (:obligations analysis)))
        (is (= before @calls))
        (is (= {:child nil} (invoke closure)))
        (reset! result {"child" nil})
        (is (= :execution-error/type-mismatch
               (try (invoke closure) nil
                    (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))))


(deftest computed-null-retains-a-field-read-validator-for-its-union-arm
  (let [definitions {:root {:input {:x '[:union :null [:map a :any]]}
                            :return :any :bindings {[:x] {:ref :child}}}
                     :child {:input {} :return :null}}
        result (atom nil)
        functions {:root echo :child (fn [_ _] @result)}
        {:keys [closure]} (compiled definitions functions)]
    (is (nil? (invoke closure)))
    (reset! result {:key :value})
    (is (= :execution-error/type-mismatch
           (try (invoke closure) nil
                (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))


(deftest optional-absence-remains-distinct-from-explicit-nil
  (let [definitions {:root {:input {:x :any} :optional #{[:x]} :return :any}}
        {:keys [closure]} (compiled definitions {:root (fn [args _]
                                                         [(contains? args :x) (runtime/resolve-arg args :x)])})]
    (is (= [false nil] (invoke closure)))
    (is (= [true nil] (invoke closure {(fixture/address :root [] :root [:x]) nil} {})))))


(deftest unsupported-input-cache-shapes-refuse-without-consuming-them
  (let [reads (atom 0)
        definitions {:root {:input {:x :any} :return :any}}
        {:keys [closure]} (compiled definitions {:root echo})
        value (lazy-seq (swap! reads inc) [1])
        error (try (invoke closure {(fixture/address :root [] :root [:x]) value} {})
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (= :opaque-input-key-required (:code (ex-data error))))
    (is (zero? @reads))))


(deftest typed-callable-producer-preserves-the-produced-function
  (let [callable-type [:fn {:item :int} :int #{}]
        definitions {:root {:input {:x callable-type} :return callable-type :bindings {[:x] {:ref :child}}}
                     :child {:input {} :return callable-type}}
        calls (atom 0)
        callable (fn [x] (swap! calls inc) (inc x))
        {:keys [closure analysis]} (compiled definitions {:root echo :child (fn [_ _] callable)})]
    (is (= :callable-producer (get-in analysis [:scopes [] :bindings
                                                (fixture/address :root [] :root [:x]) :reference :mode])))
    (is (identical? callable (invoke closure)))
    (is (zero? @calls))
    (is (= 4 ((invoke closure) 3)))
    (is (= 1 @calls))))


(deftest forward-projection-shares-one-producer-cell
  (let [definitions {:root {:input (array-map :left :text :right :text) :return :any
                            :bindings {[:right] {:ref :child}}}
                     :child {:input {} :return :text}}
        {:keys [context]} (fixture/model definitions :root)
        row {:id (random-uuid) :fn-id (fixture/id :root) :scope-path []
             :target-path (fixture/field-path :root [:left])
             :expression-kind "projection" :projection-scope-path []
             :projection-path (fixture/field-path :root [:right])}
        context (assoc-in context [:bindings (:id row)] row)
        plan (analysis/analyze-effective-input context (fixture/id :root)
                                               {:check-expression (requiring-resolve 'graphden.types.check/check-input-binding)})
        reads (atom 0)
        closure (compile/compile-checked plan
                                         {:context context
                                          :implementations (fixture/implementations
                                                             definitions {:root pair :child (fn [_ _] (swap! reads inc) "one")})})]
    (is (= ["one" "one"] (invoke closure)))
    (is (= 1 @reads))))


(defn- captured-computation-closure
  [definitions functions]
  (let [{:keys [context]} (fixture/model definitions :root)
        row {:id (fixture/id :capture) :fn-id (fixture/id :root)
             :scope-path [(fixture/id :child-use)]
             :target-path (fixture/field-path :child [:source])
             :expression-kind "projection" :projection-scope-path []
             :projection-path (fixture/field-path :root [:source])}
        context (assoc-in context [:bindings (:id row)] row)
        plan (analysis/analyze-effective-input context (fixture/id :root)
                                               {:check-expression (requiring-resolve 'graphden.types.check/check-input-binding)})
        implementations (-> (fixture/implementations definitions functions)
                            (assoc-in [(fixture/id :root) :argument-paths] {:x (fixture/field-path :root [:x])})
                            (assoc-in [(fixture/id :root) :root-entry :args] {:x :int}))]
    (compile/compile-checked plan {:context context :implementations implementations})))


(deftest computed-capture-does-not-reuse-an-earlier-invocation-in-request-cache
  (let [definitions {:root {:input {:source :int :x :int} :return :int
                            :bindings {[:source] {:ref :source} [:x] {:ref :child :id :child-use}}}
                     :child {:input {:source :int} :return :int}
                     :source {:input {} :return :int}}
        old-compiler (fn [definitions functions]
                       (legacy-closure
                         (-> definitions (update-in [:root :input] dissoc :source)
                             (update-in [:root :bindings] dissoc [:source])) functions
                         {:extra-refs {:root [:source]}
                          :extra-bindings [{:id (fixture/id :legacy-env) :fn-id (fixture/id :root)
                                            :slot-id (first (fixture/field-path :child [:source]))
                                            :ref-fn-id (fixture/id :source)}]}))
        run (fn [compiler]
              (let [reads (atom 0)
                    closure (compiler definitions {:root echo
                                                   :child (fn [args _] (runtime/resolve-arg args :source))
                                                   :source (fn [_ _] (swap! reads inc))})]
                (binding [eager/*request-call-cache* (java.util.HashMap.)
                          eager/*always-fresh-fn-ids* (atom #{(fixture/id :source)})]
                  [[(invoke closure) (invoke closure)] @reads])))]
    (is (= [[1 2] 2] (run old-compiler) (run captured-computation-closure)))))
