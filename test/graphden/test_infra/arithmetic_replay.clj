(ns graphden.test-infra.arithmetic-replay
  "Disposable PostgreSQL half of the pinned arithmetic source replay."
  (:require
    [graphden.crud.structured-run :as run]
    [graphden.executor.interface :as executor]
    [graphden.executor.test-setup :as setup]
    [graphden.input.migration :as migration]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.init.packages]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]))


(defn- expect!
  [expected actual]
  (when-not (= expected actual)
    (throw (ex-info "Stored arithmetic replay changed" {:expected expected :actual actual}))))


(defn check
  [packages samples]
  (let [result (atom nil)]
    ((setup/create-container-fixture)
     (fn []
       (let [storage (setup/create-versioned-test-storage)
             sync! #(ig/init-key :exec/base-fns {:storage storage :packages packages})
             info (sync!)
             _ (ig/init-key :exec/fn-entities {:storage storage :packages packages :base-fns info})
             owners (mapv #(ids/fn-id "core.arithmetic" %) (keys samples))
             inputs (mapv #(migration/derived-id :input-type %) owners)
             versions #(sp/query-entities (vs/unwrap storage) :fn-version {:fn-id (into owners inputs)})
             original (set (versions))
             _ (sync!)
             rows (sp/read-entities storage :fn owners)
             ctx (executor/create-context {:storage storage :base-fns (:base-fns info)})]
         (expect! inputs (mapv #(get-in rows [% :input-type-fn-id]) owners))
         (expect! original (set (versions)))
         (let [attempt (fn [p overrides]
                         (try (ig/init-key :exec/base-fns {:storage storage :packages p :extra-base-fns overrides}) :accepted
                              (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
               stripped (update packages :base-fn-defs dissoc :floor)]
           (expect! :package-error/primitive-admission (attempt stripped {}))
           (expect! :package-error/primitive-admission (attempt packages {:floor (get-in info [:base-fns :ceil])}))
           (expect! original (set (versions))))
         (let [outputs (into (sorted-map)
                             (for [[name args] samples :let [id (ids/fn-id "core.arithmetic" name)]]
                               [name (try (let [plan (run/plan ctx id args)] ((:invoke plan) args ctx))
                                          (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :code :reason])))]))
               expected (into {} (map (fn [[name args]] [name ((get-in info [:base-fns name]) args ctx)])) samples)]
           ;; The public result/argument surface requires a closed data type.
           (expect! (assoc expected :neq {:type :browser-plan/unsupported :reason :server-value}) outputs)
           (reset! result {:stored-owners (count owners) :stable-versions (count original) :named-results outputs})))))
    @result))
