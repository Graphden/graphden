(ns graphden.test-infra.graph-harness
  "Shared fixture + request builders for the `*_graph_test` family —
   tests that drive production graph handler chains (`:process-*`,
   `:list-*-handler`, …) through `setup/via-graph` over the
   `[core web app]` golden clone. Five files used to carry
   byte-identical copies of the fixture and these helpers.

   `*graph*` is the bootstrap map (`:storage` / `:ctx` /
   `:all-name->id` …) the fixture binds for the NS."
  (:require
    [cheshire.core :as cheshire]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *graph* nil)


(defn graph-fixture
  "`:once` fixture over the `[core web app]` golden clone, wrapped in
   `with-clean-registry` so the base-fn impls registered by the
   bootstrap land in a thread-local override atom instead of the
   process-global registry — sibling NSes on parallel kaocha threads
   keep their own.

   `ns-ident` names the per-NS clone DB — pass `(str (ns-name *ns*))`
   FROM THE TEST FILE. The identity must be per-file: this used to be
   a macro-captured `*ns*` precisely because sibling NSes silently
   sharing one database under the parallel runner is the documented
   trap (see `bootstrap-crud-graph-from-golden!`'s docstring)."
  [ns-ident]
  (fn [t]
    (exec/with-clean-registry
      #(let [graph (setup/bootstrap-crud-graph-from-golden!*
                     ns-ident ["core" "web" "app"])
             storage (:storage graph)]
         (try
           (binding [*graph* graph]
             (t))
           (finally (sp/close storage)))))))


(defn uniq
  "Random-uuid-suffixed name. Storage is shared across deftests in a
   NS; unique names prevent cross-test collisions on `UNIQUE(name)`."
  [stem]
  (str stem "-" (random-uuid)))


(defn form-req
  "Ring-shaped request for a form-encoded POST/PUT body."
  ([uri body] (form-req uri body :post))
  ([uri body method]
   {:uri uri
    :request-method method
    :body body
    :headers {"content-type" "application/x-www-form-urlencoded"}}))


(defn json-req
  "Ring-shaped request for a JSON body."
  ([uri body] (json-req uri body :post))
  ([uri body method]
   {:uri uri
    :request-method method
    :body (cheshire/generate-string body)
    :headers {"content-type" "application/json"}}))


(defn via
  "Dispatch `request` to the named production graph handler."
  [fn-name request]
  (setup/via-graph *graph* fn-name request))


(defn exec-name
  "Execute the named fn from the bootstrap by NAME with `args`
   (auto-injecting `:storage-query` where the fn propagates it)."
  [nm args]
  (let [{:keys [ctx storage all-name->id]} *graph*
        fn-id (get all-name->id nm)]
    (when-not fn-id
      (throw (ex-info (str "No fn-id for " nm) {:nm nm})))
    (setup/exec-with-storage ctx storage fn-id args)))
