(ns graphden.editor.root-binding
  "The editor handler's two graph edges: a static root fallback and a
   protected, graph-visible personal-root resolver."
  (:require
    [graphden.editor.config-resolution :as resolution]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.binding-read :as binding-read]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]))


(def ^:private handler-id (ids/fn-id "app.editor" :_editor-handler))
(def ^:private root-slot-id (ids/slot-id (ids/fn-id "app.editor" :client-handler) :root))
(def ^:private selector-slot-id (ids/slot-id (ids/fn-id "app.editor" :client-handler) :root-selector))


(defn- reject!
  [reason]
  (throw (ex-info "Editor root binding is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- static-ref?
  [rows expected]
  (let [row (first rows)]
    (and (= 1 (count rows))
         (uuid? (:ref-fn-id row))
         (or (nil? expected) (= expected (:ref-fn-id row)))
         (nil? (:value row)) (not (:value-present row))
         (nil? (:resolver-fn-id row)) (nil? (:type-override-fn-id row))
         (not (:list-append row)) (not (:list-closed row)) (not (:terminal row)))))


(defn capture!
  "Read and validate both exact handler bindings in the current snapshot."
  [storage]
  (let [handler (sp/read-entity storage :fn handler-id)]
    (when-not (and handler (owned/owned-fn-id? handler-id)
                   (= tenancy/public-org (or (:org-id handler) tenancy/public-org)))
      (reject! :root-owner))
    ;; The bounded query charges all identities owned by the handler before
    ;; applying mutable slot predicates. Read that finite owner set once, then
    ;; require exactly one visible binding for each protected edge.
    (let [bindings (binding-read/owner-bindings storage handler-id)
          roots (binding-read/exact-slot bindings root-slot-id)
          selectors (binding-read/exact-slot bindings selector-slot-id)]
      (when-not (and (static-ref? roots nil)
                     (static-ref? selectors resolution/root-resolver-id))
        (reject! :root-binding))
      {:root (:ref-fn-id (first roots))
       :root-row (first roots)
       :selector-row (first selectors)})))


(defn selected-root
  "Apply the resolver's personal identity to the handler's static fallback."
  [handler-root choice]
  (if (= resolution/default-root-id (:id choice)) handler-root (:id choice)))
