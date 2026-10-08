(ns ^:serial graphden.editor.theme-test
  "Exercise the selected-root boundary without global execution or network IO."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.crud.fn-execution :as execution]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.crud.request :as request]
    [graphden.editor.theme :as theme]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.remote.core :as remote]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tenancy]))


(def ^:private fn-id (random-uuid))
(def ^:private payload {:mode "dark" :tokens {"--bg" "#12345678"} :fonts {} :scale 110})


(defn- fixture
  [f]
  (let [calls (atom [])
        epoch (atom 0)
        ctx {:storage (remote/from-bundle {:fn [{:id fn-id :name "my-theme"}]})
             :invalidation-count epoch
             :execute-guard (fn [_ id] (swap! calls conj [:guard id]))}
        outcome (atom {:status :succeeded :result payload})
        signature (atom {:return :map :args {} :effects #{}})
        visibility (atom :plain)]
    (binding [tenancy/*current-org* "team"
              tenancy/*current-principal* {:authenticated? true :user-id "owner-a"}
              runtime/*execute-authorized* true]
      (with-redefs [recheck/call-with-ctx-slices (fn [_ thunk] (thunk))
                    registry/rich-type-of-id (fn [_] @signature)
                    registry/trace-capture-class (fn [_ _] @visibility)
                    lookup/free-arg-slot-map-cached (fn [_ _] {:base {}})
                    execution/apply-execute
                    (fn [exec-ctx parsed row]
                      (swap! calls conj [:run (:id row) (:allowed-effects exec-ctx)
                                         persist/*max-execution-wall-ms*
                                         runtime/*execute-authorized* (:args parsed)
                                         (tenancy/current-org)])
                      @outcome)]
        (f {:ctx ctx :calls calls :epoch epoch :outcome outcome
            :signature signature :visibility visibility
            :input {:fn-id (str fn-id) :org "team" :owner "owner-a" :args {}}})))))


(deftest selected-root-is-authorized-and-runs-with-a-real-deadline
  (fixture
    (fn [{:keys [ctx calls input]}]
      (is (= {:ok true :payload payload} (theme/evaluate ctx input)))
      (is (= [[:guard fn-id] [:run fn-id #{} 750 false {} "team"]] @calls)))))


(deftest scope-and-target-refusals-never-execute
  (fixture
    (fn [{:keys [ctx calls input]}]
      (doseq [bad [(assoc input :org "other") (dissoc input :org)
                   (assoc input :owner "owner-b") (dissoc input :owner)
                   (assoc input :fn-id "malformed") (assoc input :branch "other")
                   (assoc input :args {:base {:ref (str fn-id)}})
                   (assoc input :args {:base 1 "base" 2})
                   (assoc input :args {:base (repeat 1)})]]
        (is (false? (:ok (theme/evaluate ctx bad))))
        (is (empty? @calls)))
      (is (= 403 (:http-status (theme/evaluate ctx (assoc input :fn-id (str (random-uuid)))))))
      (is (empty? @calls))
      (binding [tenancy/*current-principal* {:authenticated? true :user-id "owner-b"}]
        (is (= {:ok false :reason "wrong-user" :http-status 403}
               (theme/evaluate ctx input)))
        (is (empty? @calls)))
      (let [denied (assoc ctx :execute-guard
                          (fn [& _] (throw (ex-info "secret internal target" {:type :authz/forbidden}))))]
        (is (= {:ok false :reason "unavailable" :http-status 403}
               (theme/evaluate denied input)))
        (is (empty? @calls))))))


(deftest impure-secret-and-unknown-signatures-are-not-admitted
  (fixture
    (fn [{:keys [ctx calls input signature visibility]}]
      (doseq [info [nil {:return :map} {:return :map :effects nil}
                    {:return :map :effects #{:db}}]]
        (reset! signature info)
        (is (= "not-plain-pure" (:reason (theme/evaluate ctx input)))))
      (reset! signature {:return :map :effects #{}})
      (doseq [cls [:unknown :secret-input :secret-output]]
        (reset! visibility cls)
        (is (= "not-plain-pure" (:reason (theme/evaluate ctx input)))))
      (is (not-any? #(= :run (first %)) @calls)))))


(deftest no-asynchronous-state-errors-or-hidden-output-leaves-the-boundary
  (fixture
    (fn [{:keys [ctx input outcome]}]
      (doseq [result [{:status :pending :execution-id "private-execution"}
                      {:status :failed :error "private-error" :error-data {:secret "private-data"}}
                      {:status :rejected :http-status 429 :diagnostics ["private-diagnostic"]}]]
        (reset! outcome result)
        (let [response (theme/evaluate ctx input)]
          (is (false? (:ok response)))
          (is (= #{:ok :reason :http-status} (set (keys response))))
          (is (not (str/includes? (pr-str response) "private")))))
      (is (= 429 (:http-status (theme/evaluate ctx input)))))))


(deftest result-refusals-only-classify-an-epoch-only-change-as-retryable
  ;; All nonempty combinations: an epoch change must never mask another veto.
  (doseq [[flags code retryable]
          [[#{:epoch} "graph-changed" true]
           [#{:late} "not-plain-pure" false]
           [#{:late :epoch} "not-plain-pure" false]
           [#{:effect} "runtime-effects" false]
           [#{:effect :epoch} "runtime-effects" false]
           [#{:effect :late} "runtime-effects" false]
           [#{:effect :late :epoch} "runtime-effects" false]
           [#{:taint} "tainted-result" false]
           [#{:taint :epoch} "tainted-result" false]
           [#{:taint :late} "tainted-result" false]
           [#{:taint :late :epoch} "tainted-result" false]
           [#{:taint :effect} "tainted-result" false]
           [#{:taint :effect :epoch} "tainted-result" false]
           [#{:taint :effect :late} "tainted-result" false]
           [#{:taint :effect :late :epoch} "tainted-result" false]]]
    (fixture
      (fn [{:keys [ctx input epoch visibility]}]
        (with-redefs [execution/apply-execute
                      (fn [& _]
                        (when (flags :epoch) (swap! epoch inc))
                        (when (flags :late) (reset! visibility :secret-output))
                        (cond-> {:status :succeeded :result {:private "withheld"}
                                 :execution-id "private-execution"}
                          (flags :taint) (assoc :tainted? true)
                          (flags :effect) (assoc :runtime-effects ["private-effect"])))]
          (is (= {:ok false :reason "result-unavailable" :http-status 422
                  :code code :retryable retryable}
                 (theme/evaluate ctx input))
              (pr-str flags)))))))


(deftest late-unknown-and-effectful-signatures-are-not-retryable
  (doseq [late-signature [nil {:return :map} {:return :map :effects #{:db}}]]
    (fixture
      (fn [{:keys [ctx input epoch signature]}]
        (with-redefs [execution/apply-execute
                      (fn [& _]
                        (swap! epoch inc)
                        (reset! signature late-signature)
                        {:status :succeeded :result payload})]
          (is (= {:ok false :reason "result-unavailable" :http-status 422
                  :code "not-plain-pure" :retryable false}
                 (theme/evaluate ctx input))))))))


(deftest named-data-inputs-do-not-widen-the-execution-surface
  (fixture
    (fn [{:keys [ctx calls input]}]
      (is (= "invalid-arguments" (:reason (theme/evaluate ctx (assoc input :args {:extra 1})))))
      (is (not-any? #(= :run (first %)) @calls))
      (is (:ok (theme/evaluate ctx (assoc input :args {:base {:color "#123"}}))))
      (is (= {:base {:color "#123"}} (nth (last @calls) 5))))))


(deftest graph-payload-is-finite-and-has-no-css-network-or-script-channel
  (is (= payload (theme/validate-payload payload)))
  (is (= {:mode "light" :tokens {"--bg" "#abc"} :fonts {"mono" "'Mono', monospace"} :scale 100}
         (theme/validate-payload {"tokens" {:--bg "#abc"} "fonts" {"mono" "'Mono', monospace"}})))
  (doseq [value [nil [] {:tokens {"--bg" "red"}} {:tokens {"--bg" "#12345"}}
                 {:tokens {"--bg" "url(https://private)"}}
                 {:tokens {"--unknown" "#123"}} {:fonts {:ui "url(private)"}}
                 {:fonts {:ui " "}} {:scale 200} {:scale 100.5} {:mode "unknown"}
                 {:style "private"} {:tokens (repeat ["--bg" "#123"])}
                 {:tokens {"--bg" "#123" :--bg "#456"}}]]
    (is (thrown? clojure.lang.ExceptionInfo (theme/validate-payload value)))))


(deftest backend-token-allowlist-matches-existing-editor-vocabulary
  (let [source (slurp "resources/packages/app/editor/editor-prefs.js")
        vocabulary (second (re-find #"(?s)const THEME_TOKENS = \[(.*?)\n\];" source))
        tokens (set (map second (re-seq #"'(\-\-[a-z0-9-]+)'" vocabulary)))]
    (is (seq tokens))
    (is (= tokens (set (keys (:tokens (theme/validate-payload
                                        {:tokens (zipmap tokens (repeat "#123"))}))))))))


(deftest owner-and-org-assertions-precede-even-the-first-storage-read
  (fixture
    (fn [{:keys [ctx input calls]}]
      (with-redefs [request/require-storage
                    (fn [_] (throw (ex-info "storage must not be reached" {})))]
        (is (= "wrong-user" (:reason (theme/evaluate ctx (dissoc input :owner)))))
        (is (= "wrong-user" (:reason (theme/evaluate ctx (assoc input :owner "owner-b")))))
        (is (= "wrong-organization" (:reason (theme/evaluate ctx (dissoc input :org)))))
        (is (= "wrong-organization" (:reason (theme/evaluate ctx (assoc input :org "other"))))))
      (is (empty? @calls)))))


(deftest namespace-read-projection-is-required-before-execute-authorization
  (fixture
    (fn [{:keys [ctx input calls]}]
      (with-redefs [tenancy/apply-graph-read-filter (constantly {:fns []})]
        (is (= {:ok false :reason "unavailable" :http-status 403}
               (theme/evaluate ctx input)))
        (is (empty? @calls))))))
