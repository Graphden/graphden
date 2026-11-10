(ns ^:serial graphden.editor.root-export-test
  "Root export shares its trusted capture; invocation never inherits that proof."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.components :as components]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.root-binding :as root-binding]
    [graphden.editor.root-plan :as root-plan]
    [graphden.executor.browser-plan :as wire]
    [graphden.executor.browser-server :as server]
    [graphden.executor.browser-server-execution :as execution]
    [graphden.executor.browser-server-source :as server-source]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.capabilities :as capabilities]
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.compile.bindings :as bindings]
    [graphden.storage.remote.core :as remote]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tenancy]))


(defn- fixture
  [consume]
  (let [root (random-uuid)
        target (random-uuid)
        root-row {:id root}
        target-row {:id target}
        graph {:fns [root-row target-row]}
        storage (assoc (remote/from-bundle {:fn [root-row target-row]}) :branch-id (random-uuid))
        ctx {:storage storage :invalidation-count (atom 0)}
        counts (atom {})
        selected (atom root)
        allowed? (atom true)
        after-execute (atom (constantly nil))
        tick #(swap! counts update % (fnil inc 0))
        export #(root-plan/export-current ctx {})]
    (binding [tenancy/*current-org* tenancy/public-org
              tenancy/*current-principal* nil]
      (with-redefs-fn
        {#'root-binding/capture! (fn [_]
                                   (tick :binding)
                                   {:root @selected :root-row {:ref-fn-id @selected}
                                    :selector-row {:ref-fn-id resolution/root-resolver-id}})
         #'resolution/root-choice (fn [_ _ _]
                                    (tick :resolver)
                                    (when-not @allowed?
                                      (throw (ex-info "Revoked source" {:type :authz/forbidden})))
                                    {:id resolution/default-root-id})
         #'tenancy/current-personal-namespace (constantly nil)
         #'source/with-snapshot (fn [st f] (f st (fn [& _] nil)))
         #'source/collect-client-closure (fn [& _] graph)
         #'source/collect-namespaces (fn [& _] [])
         #'bindings/collect-bindings (fn [id _]
                                       (when (= root id)
                                         [{:slot-id capabilities/server-call-slot-id
                                           :kind :ref :ref-id target}]))
         #'server-source/capture (fn [& _]
                                   {:signature {} :input-type :text :output-type :text
                                    :fingerprint "unchanged-target" :graph {:fns [target-row]}})
         #'snapshot/capture-context-policy (fn [_] {})
         #'snapshot/export-snapshot (fn [captured _ _ _]
                                      (tick :export)
                                      {:inputs {(str root) {:required []}}
                                       :descriptors (get-in captured [:server-plan-options :server-descriptors])})
         #'components/assert-current-policy! (fn [_] nil)
         #'execution/run (fn [_ thunk] (thunk))
         #'recheck/call-with-ctx-slices (fn [_ thunk] (thunk))
         #'eager/compile-all (fn [_] {})
         #'runtime/execute (fn [& _]
                             (tick :execute)
                             (@after-execute)
                             "read-result")}
        (fn []
          (consume {:export export :counts counts :selected selected :allowed? allowed?
                    :after-execute after-execute
                    :invoke (fn [result]
                              (server/invoke-current
                                ctx {} {:descriptor (get-in result [:plan :descriptors
                                                                    [root capabilities/server-call-slot-id]])
                                        :input (wire/encode-value "input")}))}))))))


(deftest each-root-export-captures-its-anchor-once
  (fixture
    (fn [{:keys [export counts]}]
      (let [first-export (export)
            second-export (export)]
        (is (true? (:ok first-export)))
        (is (true? (:ok second-export)))
        (is (= {:binding 2 :resolver 2 :export 2} @counts))
        (is (not= (get-in first-export [:plan :descriptors])
                  (get-in second-export [:plan :descriptors]))
            "Each export issues new descriptors rather than caching authority")))))


(deftest invocation-recaptures-the-anchor-before-and-after-execution
  (fixture
    (fn [{:keys [export invoke counts]}]
      (let [result (invoke (export))]
        (is (true? (:ok result)))
        (is (= "read-result" (wire/decode-value (:value result))))
        (is (= {:binding 3 :resolver 3 :export 1 :execute 1} @counts))))))


(deftest changed-root-or-revoked-source-never-inherits-export-authority
  (doseq [change [:root :source]]
    (fixture
      (fn [{:keys [export invoke counts selected allowed?]}]
        (let [issued (export)]
          (case change
            :root (reset! selected (random-uuid))
            :source (reset! allowed? false))
          (let [result (invoke issued)]
            (is (false? (:ok result)))
            (is (= (if (= :source change) "forbidden" "unsupported") (:failure-type result)))
            (is (nil? (:value result)))
            (is (= {:binding 2 :resolver 2 :export 1} @counts))))))))


(deftest changed-anchor-during-execution-retires-the-response
  (fixture
    (fn [{:keys [export invoke counts selected after-execute]}]
      (let [issued (export)]
        (reset! after-execute #(reset! selected (random-uuid)))
        (let [result (invoke issued)]
          (is (false? (:ok result)))
          (is (nil? (:value result)))
          (is (= {:binding 3 :resolver 3 :export 1 :execute 1} @counts)))))))


(deftest a-later-export-must-observe-revoked-source
  (fixture
    (fn [{:keys [export allowed? counts]}]
      (is (true? (:ok (export))))
      (reset! allowed? false)
      (let [result (export)]
        (is (= "forbidden" (:failure-type result)))
        (is (false? (:ok result)))
        (is (= {:binding 2 :resolver 2 :export 1} @counts))))))
