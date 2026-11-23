(ns graphden.packages.arithmetic-input
  "In-place registration of the exact staged core arithmetic module."
  (:require
    [clojure.java.io :as io]
    [graphden.input.migration :as migration]
    [graphden.input.module :as module]
    [graphden.input.source-context :as context]
    [graphden.input.staged-module :as staged]
    [graphden.packages.implementation :as impl]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.util.hex :as hex])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)))


(def ^:private source-sha "8f2959ace71a4b308e3f4201d158eb1e735330711eeedca0bcd8528a17d8ebd4")
(def ^:private impl-sha "2786a9d51dcb15ae4c08245583330918612b62f191f54e2eab18a20489c28e55")
(def ^:private names {:non-negative-int (ids/fn-id "core.refinements" :non-negative-int)})
(def ^:private package-path "packages/core/arithmetic/")


(defn- reject!
  []
  (throw (ex-info "In-place arithmetic module admission failed" {:type :package-error/primitive-admission})))


(defn- resource-bytes
  [file]
  (if-let [resource (io/resource (str package-path file))]
    (with-open [stream (io/input-stream resource)] (.readAllBytes stream))
    (reject!)))


(defn- declaration-context
  [definition]
  (let [fields (:input definition)
        rows (parse/base-declaration-records definition fields names)
        rows (mapv (fn [row]
                     (if (= :slot (:kind row))
                       (let [type (get-in fields [(keyword (:name row)) :type])]
                         (if (vector? type)
                           (assoc row :type-fn-id (migration/derived-id :list-type (second type)))
                           row))
                       row))
                   rows)]
    (context/declaration-context (context/environment {:name->id names}) definition rows [])))


(defonce ^:private seal (Object.))


(deftype ^:private PinnedModule
  [token declarations code])


(deftype ^:private Registration
  [token parsed definition])


(defn read-module
  [package module-name admission]
  (when-not (and (= ["core" "arithmetic"] [package module-name])
                 (= {:module [package module-name] :in-place-abi :core.arithmetic :sha256 source-sha} admission))
    (reject!))
  (let [source (staged/read-pinned (resource-bytes "fns.edn") source-sha)
        impl (resource-bytes "impls.clj")
        _ (when-not (= impl-sha (hex/encode (.digest (MessageDigest/getInstance "SHA-256") impl))) (reject!))
        parsed (:definitions (module/parse-module-v2 source {:context-for declaration-context}))
        by-name (into {} (map (juxt (comp :name :source) identity)) parsed)
        ;; Each list descriptor is emitted once for the whole ledger.
        extra (for [element [:numeric :any]]
                {:kind :fn :id (migration/derived-id :list-type element) :parent-ids []
                 :element-fn-id (ids/primitive-fn-id element)})]
    (when-not (= 20 (count by-name) (count (:fns source))) (reject!))
    {:ns-path (:namespace source) :ns-description (:description source)
     :fns (mapv #(assoc % :source-file (str package-path "fns.edn")) (:fns source))
     :in-place (PinnedModule. seal (update-in by-name [:add :declaration-records] into extra)
                              (String. ^bytes impl StandardCharsets/UTF_8))}))


(defn- require-module!
  [module]
  (when-not (and (instance? PinnedModule module) (identical? seal (.-token ^PinnedModule module))) (reject!)))


(defn register-module
  [^PinnedModule module]
  (require-module! module)
  (let [parsed (.-declarations module)
        impls (impl/evaluate (.-code module) false)
        ;; In this exact implementation these callbacks inspect only :type
        ;; facts for nums, number, dividend and divisor. Preserve the callable
        ;; and record the existing registry's callback-identity proof.
        local #{:add :sub :mul :mod :quot :neg :abs}]
    (when-not (= (set (keys parsed)) (set (keys impls))) (reject!))
    {:impls impls
     :attest (fn [definition name]
               (let [d (get parsed name)
                     entry (get impls name)
                     audited (cond-> definition (local name) (assoc :local-type-rules #{:return-type-rule}))]
                 (when-not (and d (identical? (:impl entry) (:impl definition))
                                (identical? (:return-type-rule entry) (:return-type-rule definition))
                                (= (boolean (local name)) (fn? (:return-type-rule entry)))) (reject!))
                 (with-meta audited {::registration (Registration. seal d audited)})))}))


(defn evidence
  [definition]
  (when-let [receipt (::registration (meta definition))]
    (when-not (and (instance? Registration receipt) (identical? seal (.-token ^Registration receipt)) (= definition (.-definition ^Registration receipt))) (reject!))
    {:parsed (.-parsed ^Registration receipt) :in-place-definition (.-definition ^Registration receipt)}))


(defn assert-ledgers!
  [packages]
  (doseq [^PinnedModule module (::modules (meta packages))]
    (require-module! module)
    (doseq [[name parsed] (.-declarations module)]
      (let [entry (evidence (get-in packages [:base-fn-defs name]))]
        (when-not (identical? parsed (:parsed entry)) (reject!))))))


(defn assert-implementation!
  [source name definition overrides]
  (let [impl (:impl (:in-place-definition source))]
    (when-not (and (fn? impl) (identical? impl (:impl definition)) (identical? impl (get overrides name impl))) (reject!))))


(defn checked-records
  [{:keys [parsed in-place-definition]} impls signatures]
  (let [id (:fn-id parsed)
        name (get-in parsed [:source :name])
        sig (get signatures id)
        args (into {} (map (fn [{:keys [name type]}]
                             [name (if (= :non-negative-int type) [:refine :int [:>= 0]] type)]))
                   (get-in parsed [:contract :fields]))
        impl (:impl in-place-definition)]
    (when-not (and (owned/owned-impl? id impl) (identical? impl (get impls name))
                   (= (:args sig) args)
                   (= (:return sig) (get-in parsed [:source :return-type]))
                   (= #{} (:effects sig)) (contains? (:execution sig) :jvm-server))
      (reject!))
    (:declaration-records parsed)))
