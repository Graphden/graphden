(ns graphden.editor.theme
  "Evaluate a personal theme as a restricted ordinary server-side function.
   The caller's storage, branch, principal and execute guard remain authoritative."
  (:require
    [clojure.string :as str]
    [graphden.crud.fn-execution :as execution]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.context :as context]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tenancy]))


(def ^:private wall-ms 750)


(def ^:private token-names
  #{"--gd-paper" "--gd-paper-2" "--gd-panel"
    "--gd-ink" "--gd-ink-2" "--gd-ink-3" "--gd-ink-4"
    "--gd-line" "--gd-line-2" "--gd-grid"
    "--gd-flow" "--gd-flow-ink" "--gd-flow-wash" "--gd-flow-2"
    "--gd-lit" "--gd-lit-wash" "--gd-ref" "--gd-ref-wash" "--gd-free" "--gd-free-wash"
    "--gd-ok" "--gd-warn" "--gd-crit" "--gd-crit-wash"
    "--bg" "--fg" "--muted-fg" "--border" "--accent"
    "--card-bg" "--card-fg" "--card-border" "--card-header-bg" "--card-header-fg"
    "--hover-bg" "--selected-bg" "--sidebar-bg" "--header-bg" "--header-fg"})


(defn- reject!
  [reason status]
  (throw (ex-info "Theme evaluation refused"
                  {:type ::rejected :reason reason :http-status status})))


(defn- field-name
  [k]
  (cond
    (string? k) k
    (and (keyword? k) (nil? (namespace k))) (name k)
    :else nil))


(defn- fields
  "Normalize JSON/graph map keys without accepting collisions or lazy data."
  [value allowed]
  (when-not (and (map? value) (<= (count value) (count allowed)))
    (reject! "invalid-payload" 422))
  (reduce-kv
    (fn [result k v]
      (let [k (field-name k)]
        (when (or (not (contains? allowed k)) (contains? result k))
          (reject! "invalid-payload" 422))
        (assoc result k v)))
    {} value))


(defn- checked-values
  [value allowed pattern]
  (into {}
        (map (fn [[k v]]
               (when-not (and (string? v) (<= (count v) 160)
                              (re-matches pattern (str/trim v)))
                 (reject! "invalid-payload" 422))
               [k (str/trim v)]))
        (fields value allowed)))


(defn validate-payload
  "Return a bounded theme payload. Only graph-produced colors require HEX;
   the existing marketplace/preferences sanitizer still accepts rgb()/hsl()."
  [value]
  (let [p (fields value #{"mode" "tokens" "fonts" "scale"})
        mode (get p "mode" "light")
        scale (get p "scale" 100)]
    (when-not (and (contains? #{"light" "dark"} mode)
                   (integer? scale) (<= 70 scale 160))
      (reject! "invalid-payload" 422))
    {:mode mode
     :tokens (checked-values (get p "tokens" {}) token-names
                             #"#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
     :fonts (checked-values (get p "fonts" {}) #{"ui" "mono" "body"}
                            #"[A-Za-z0-9 ,'\"-]{1,160}")
     :scale scale}))


(defn- bounded-input?
  "Named inputs are finite data, never executable ref-argument envelopes."
  [value]
  (let [remaining (volatile! 2048)]
    (letfn [(valid?
              [v depth]
              (and (<= depth 16) (not (neg? (vswap! remaining dec)))
                   (cond
                     (or (nil? v) (boolean? v)) true
                     (number? v) (and (integer? v) (<= -9007199254740991 v 9007199254740991))
                     (string? v) (<= (count v) 2048)
                     (map? v) (and (<= (count v) 64) (not (persist/ref-arg? v))
                                   (every? (fn [[k item]]
                                             (and (some? (field-name k))
                                                  (<= (count (field-name k)) 128)
                                                  (valid? item (inc depth)))) v))
                     (vector? v) (and (<= (count v) 128)
                                      (every? #(valid? % (inc depth)) v))
                     :else false)))]
      (valid? value 0))))


(defn- parse-input
  [input]
  (when-not (and (map? input)
                 (every? #{:fn-id :org :owner :args} (keys input)))
    (reject! "invalid-request" 400))
  (when-not (= (:org input) (tenancy/current-org))
    (reject! "wrong-organization" 403))
  (when-not (= (:owner input) (tenancy/current-user-id))
    (reject! "wrong-user" 403))
  (let [id (request/parse-uuid-or-clear (:fn-id input))
        args (get input :args {})]
    (when-not (and id (map? args) (<= (count args) 16) (bounded-input? args))
      (reject! "invalid-request" 400))
    (let [named (into {} (map (fn [[k v]] [(keyword (field-name k)) v])) args)]
      (when-not (= (count args) (count named)) (reject! "invalid-request" 400))
      {:id id :args named})))


(defn- plain-pure?
  [id]
  (let [signature (registry/rich-type-of-id id)]
    (and signature (set? (:effects signature)) (empty? (:effects signature))
         (= :plain (registry/trace-capture-class id nil)))))


(defn- authorized-row
  [ctx id]
  (let [row (sp/read-entity (request/require-storage ctx) :fn id)]
    (when-not (and row (seq (:fns (tenancy/apply-graph-read-filter {:fns [row]}))))
      (reject! "unavailable" 403))
    ;; The surrounding HTTP handler may already have authorized its OWN root.
    ;; This selected function is a separate execution, including token ceilings.
    (when-let [guard (:execute-guard ctx)] (guard ctx id))
    row))


(defn- evaluate-in-scope
  [ctx {:keys [id args]}]
  (let [row (authorized-row ctx id)
        epoch (context/invalidation-epoch ctx)]
    (when-not (plain-pure? id) (reject! "not-plain-pure" 422))
    (let [free (lookup/free-arg-slot-map-cached ctx id)]
      (when-not (every? #(contains? free %) (keys args))
        (reject! "invalid-arguments" 400)))
    (let [result (binding [persist/*max-execution-wall-ms* wall-ms
                           runtime/*execute-authorized* false]
                   (execution/apply-execute
                     (assoc ctx :allowed-effects #{})
                     {:fn-id id :args args :persist? false :timeout-ms wall-ms}
                     row))]
      (when-not (= :succeeded (:status result))
        (reject! (if (= :pending (:status result)) "timeout" "evaluation-failed")
                 (if (contains? #{429 503} (:http-status result)) (:http-status result) 422)))
      (when (or (:tainted? result) (seq (:runtime-effects result))
                (not= epoch (context/invalidation-epoch ctx))
                (not (plain-pure? id)))
        (reject! "result-unavailable" 422))
      {:ok true :payload (validate-payload (:result result))})))


(defn evaluate
  "Evaluate in the request's existing branch/org. Fixed refusals never expose
   exception text, diagnostics, source, traces, or asynchronous execution IDs."
  [ctx input]
  (try
    (let [parsed (parse-input input)]
      (recheck/call-with-ctx-slices ctx #(evaluate-in-scope ctx parsed)))
    (catch Exception error
      (let [data (ex-data error)]
        (if (= ::rejected (:type data))
          (assoc (select-keys data [:reason :http-status]) :ok false)
          {:ok false :reason "unavailable"
           :http-status (if (= :authz/forbidden (:type data)) 403 422)})))))
