(ns ^:serial graphden.system.targeted-recheck-test
  "Targeted futures report bounded terminal outcomes, including stale presence."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging :as log]
    [clojure.tools.logging.impl :as log-impl]
    [graphden.crud.type-check :as type-check]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.system.branch-router.recheck-lifecycle :as lifecycle]))


(def ^:private receipt-prefix "targeted branch type recheck ")


(defn- capture-factory
  [lines terminal]
  (reify log-impl/LoggerFactory
    (name [_] "targeted-recheck-test")

    (get-logger
      [_ _]
      (reify log-impl/Logger
        (enabled? [_ _] true)

        (write!
          [_ level _ message]
          (let [text (str message)]
            (swap! lines conj {:level level :message text})
            (when (str/starts-with? text receipt-prefix)
              (deliver terminal (edn/read-string (subs text (count receipt-prefix)))))))))))


(defn- run-targeted!
  "Wait for both the receipt and admission callback completion before mocks end."
  [ctx ids checker admission]
  (let [lines (atom [])
        terminal (promise)
        completed (promise)]
    (binding [log/*logger-factory* (capture-factory lines terminal)]
      (with-redefs [type-check/type-check-fn-after-mutation! checker
                    lifecycle/try-with-recovery-admission
                    (fn [_ callback]
                      (try (admission callback)
                           (finally (deliver completed true))))]
        (is (nil? (recheck/recheck-ctx-types! ctx nil ids)) "the public API remains asynchronous and nil-returning")
        (let [receipt (deref terminal 5000 ::timeout)]
          (is (map? receipt) "the targeted worker emits a terminal receipt")
          (is (true? (deref completed 5000 ::timeout)) "the admission callback has finished")
          (is (= 1 (count (filter #(str/starts-with? (:message %) receipt-prefix) @lines)))
              "exactly one terminal receipt")
          {:receipt receipt :lines @lines})))))


(defn- context
  [rich]
  {:rich-types-atom (atom {:by-id rich :by-name {}})
   :per-org-rich-atom (atom {})
   :compile-deps (atom {:reverse-deps {}})
   :type-recheck-state (atom {:generation 1})})


(deftest targeted-check-reports-publication-and-missing-separately
  (doseq [publish? [true false]]
    (testing (if publish? "the checker records an entry" "nil alone does not prove publication")
      (let [id (random-uuid)
            ctx (context {})
            calls (atom [])
            checker (fn [storage checked-id options]
                      (swap! calls conj [storage checked-id options])
                      (when publish?
                        (swap! (:rich-types-atom ctx) assoc-in [:by-id checked-id] {:return :int}))
                      nil)
            {:keys [receipt]} (run-targeted! ctx [id] checker (fn [callback] (callback)))]
        (is (= [[nil id {}]] @calls) "internal collection options never reach the checker")
        (is (= {:status :checked :outcome (if publish? :present :missing)
                :requested-count 1 :checked-count 1 :diagnostic-count 0 :exception-count 0
                :present-before 0 :present-after (if publish? 1 0) :missing-count (if publish? 0 1)}
               (select-keys receipt [:status :outcome :requested-count :checked-count
                                     :diagnostic-count :exception-count :present-before :present-after :missing-count])))
        (is (<= 0 (:admitted-after-ms receipt) (:elapsed-ms receipt)))))))


(deftest diagnostic-and-exception-outcomes-override-old-presence
  (doseq [failure [:diagnostic :exception]]
    (testing (name failure)
      (let [ids (vec (repeatedly 12 random-uuid))
            ctx (context (zipmap ids (repeat {:return :int})))
            private-payload "must-never-appear-in-recheck-receipt"
            checker (fn [& _]
                      (if (= failure :diagnostic)
                        {:reason private-payload :diagnostic {:value private-payload}}
                        (throw (ex-info private-payload {:private private-payload}))))
            {:keys [receipt lines]} (run-targeted! ctx ids checker (fn [callback] (callback)))]
        (is (= :checked (:status receipt)))
        (is (= (if (= failure :diagnostic) :diagnostic :failed) (:outcome receipt)))
        (is (= 12 (:checked-count receipt) (:present-before receipt) (:present-after receipt)))
        (is (zero? (:missing-count receipt)))
        (is (= 12 (get receipt (if (= failure :diagnostic) :diagnostic-count :exception-count))))
        (is (= 10 (count (:issues receipt))) "examples are capped independently of complete counts")
        (is (every? #(and (contains? (set ids) (:fn-id %))
                          (= (if (= failure :diagnostic) #{:fn-id :outcome}
                                 #{:fn-id :outcome :exception-class}) (set (keys %))))
                    (:issues receipt)))
        (is (not (str/includes? (pr-str lines) private-payload)) "no diagnostic body, exception message or ex-data")))))


(deftest admission-failure-and-generation-cancellation-are-terminal
  (doseq [failure [:exception :cancelled]]
    (testing (name failure)
      (let [ctx (context {})
            calls (atom 0)
            {:keys [receipt lines]}
            (run-targeted! ctx [(random-uuid)] (fn [& _] (swap! calls inc))
                           (fn [callback]
                             (if (= failure :exception)
                               (throw (ex-info "private-admission-error" {:private "private-admission-error"}))
                               (do (swap! (:type-recheck-state ctx) update :generation inc)
                                   (callback)))))]
        (is (zero? @calls) "no checker runs after an admission failure or generation change")
        (is (= (if (= failure :exception) :failed :cancelled) (:status receipt) (:outcome receipt)))
        (is (not (str/includes? (pr-str lines) "private-admission-error")))
        (when (= failure :exception)
          (is (= "clojure.lang.ExceptionInfo" (:exception-class receipt)))
          (is (= :warn (:level (last lines)))))))))
