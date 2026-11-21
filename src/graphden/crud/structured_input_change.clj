(ns graphden.crud.structured-input-change
  "Edit or unlink an exact owner-authored structured expression."
  (:require
    [graphden.crud.request :as request]
    [graphden.crud.structured-input :as structured]
    [graphden.crud.structured-input-bind :as bind]
    [graphden.crud.structured-run :as run]
    [graphden.input.access :as access]
    [graphden.input.context :as input]
    [graphden.input.storage :as input-storage]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.util.abort-shield :as shield]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.input :as invariant]))


(defn- reject!
  [kind message]
  (throw (ex-info message {:type kind})))


(defn- command-id
  [value]
  (or (cond (uuid? value) value (string? value) (parse-uuid value))
      (reject! :input/invalid-command "Supply a valid function, binding and version identity")))


(defn- normalize-command
  [action phase body]
  (let [reference? (and (map? body) (contains? body :ref-fn-id))
        fields (cond-> #{:fn-id :binding-id}
                 (= action :edit) (conj (if reference? :ref-fn-id :value))
                 (= phase :apply) (conj :expected-version :expected-state))]
    (when-not (and (#{:edit :remove} action) (map? body) (= fields (set (keys body))))
      (reject! :input/invalid-command "Supply exactly the fields for this input change"))
    (cond-> (-> body (update :fn-id command-id) (update :binding-id command-id))
      reference? (update :ref-fn-id command-id)
      (= phase :apply) (update :expected-version command-id))))


(defn- open-path
  [ctx owner row]
  (let [opened (structured/analyze ctx owner)
        address [[owner []] (:target-path row)]
        path (some #(when (= address (:address (val %))) (key %))
                   (get-in opened [:public-input :routes]))]
    (bind/visible-leaf opened path)
    path))


(defn- check-detachable!
  "A scoped row anywhere on this branch may depend on the use-site UUID.
   Rehearse a non-reference at the same address under the normal invariant
   checker. No storage row or version is written or returned."
  [storage row version]
  (when (= "ref" (:expression-kind row))
    (let [vacant (assoc row :expression-kind nil :ref-fn-id nil)]
      (try
        (input-storage/authorized-call storage {:operation :detach :owner (:fn-id row) :id (:id row) :version version}
                                       #(invariant/check-collisions! (vs/unwrap storage) (vs/current-branch-id storage) :input-binding [vacant]))
        nil
        (catch clojure.lang.ExceptionInfo error
          (case (:type (ex-data error))
            (:input/invalid-row :input/invalid-binding)
            (reject! :input/leaf-unavailable "This reference is in use")
            (throw error)))))))


(defn- checked-path
  [exec-ctx ctx action {:keys [fn-id binding-id]} row]
  (input/call-with-types
    ctx
    (fn []
      (when (= action :edit)
        (let [before (if (:ref-fn-id row)
                       (:analysis (run/runnable-input exec-ctx ctx fn-id))
                       (structured/analyze ctx fn-id))]
          (when-not (structured/editable-binding? ctx before binding-id)
            (reject! :input/leaf-unavailable "This input binding is unavailable for editing"))))
      (open-path (update ctx :bindings dissoc binding-id) fn-id row))))


(defn- replacement
  [exec-ctx ctx row {:keys [binding-id value ref-fn-id] :as command} path]
  (when (and (= ref-fn-id (:ref-fn-id row)) (= value (:value row)))
    (reject! :input/no-change "Supply a different expression"))
  (let [opened (update ctx :bindings dissoc binding-id)
        new-row (bind/checked-row exec-ctx opened (assoc command :path path) binding-id)]
    (when-not (= (:target-path new-row) (:target-path row))
      (reject! :input/leaf-unavailable "This input field changed"))
    (merge {:value nil :ref-fn-id nil} (select-keys new-row [:expression-kind :ref-fn-id :value :value-present]))))


(defn- plan
  [exec-ctx storage action {:keys [fn-id binding-id ref-fn-id expected-state expected-version] :as command}]
  (let [exec-ctx (assoc exec-ctx :storage storage)
        base (bind/writable-context exec-ctx storage fn-id (when (= :remove action) binding-id))
        raw (or (:detached-binding base) (get-in base [:bindings binding-id]))
        row (or (structured/own-command-row fn-id raw)
                (reject! :input/leaf-unavailable "This input binding is unavailable for editing"))
        _ (input-storage/binding-call storage :check fn-id binding-id #(writer/assert-write-authorized! storage :input-binding {} binding-id))
        path (checked-path exec-ctx base action command row)
        ctx (if ref-fn-id (bind/reference-context exec-ctx storage fn-id ref-fn-id) base)
        version (get (input-storage/binding-versions storage {binding-id fn-id}) binding-id)
        _ (when-not (and version (or (nil? expected-version) (= expected-version version)))
            (reject! :input/stale-version "Refresh the structured input before editing"))
        basis (bind/preview-basis storage (assoc-in ctx [:bindings binding-id] row)
                                  (-> command (dissoc :expected-version) (assoc :action action)))]
    (when (and expected-version (not= expected-state basis))
      (reject! :input/stale-preview "Refresh the structured input before editing"))
    (check-detachable! storage row version)
    (let [patch (when (= action :edit) (replacement exec-ctx ctx row command path))]
      {:owner (get-in ctx [:fns fn-id]) :expected-state basis :expected-version version
       :patch patch :proof (input-storage/binding-proof storage action (merge row patch) version)})))


(defn preview
  "Issue a branch- and action-bound preview for editing or removing an own expression."
  [ctx action request]
  (let [storage (request/require-storage ctx)
        {:keys [fn-id binding-id] :as command} (normalize-command action :preview request)]
    (bind/with-input-write storage
                           (fn [snapshot]
                             (merge {:ok true :action action :fn-id fn-id :binding-id binding-id}
                                    (select-keys (plan ctx snapshot action command) [:expected-state :expected-version]))))))


(defn- apply-plan!
  [ctx storage action {:keys [fn-id binding-id] :as command}]
  (let [{:keys [owner patch proof]} (plan ctx storage action command)
        result (input-storage/binding-call storage :write fn-id binding-id proof
                                           #(access/with-binding-write fn-id binding-id
                                                                       (fn []
                                                                         (case action
                                                                           :edit (sp/update-entity storage :input-binding binding-id patch)
                                                                           :remove (binding [vs/*tombstone-delete?* true]
                                                                                     (sp/delete-entity storage :input-binding binding-id))))))]
    (when-not result
      (reject! :input/write-refused "The input binding was not changed"))
    owner))


(defn apply!
  "Reauthorize and commit the exact edit or removal approved by preview."
  [ctx action request]
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            {:keys [fn-id binding-id] :as command} (normalize-command action :apply request)
            owner (bind/with-input-write storage
                                         (fn [snapshot]
                                           (apply-plan! ctx snapshot action command)))]
        (bind/publish! ctx storage owner
                       {:ok true :committed true :action action :fn-id fn-id :binding-id binding-id})))))
