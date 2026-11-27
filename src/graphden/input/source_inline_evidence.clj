(ns graphden.input.source-inline-evidence
  "Read-only evidence for a direct inline declaration at its legacy use-site."
  (:require
    [graphden.input.format :as format]
    [graphden.input.migration :as migration]
    [graphden.input.source :as source]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- reject!
  [code]
  (throw (ex-info "Cannot prove inline source origin"
                  {:type :input/inline-source-evidence :code code})))


(defn- prepared-definition
  [prepared ns-id name]
  (when (simple-keyword? name)
    (get-in prepared [:defs-by-name (keyword (or ns-id "") (clojure.core/name name))])))


(defn- original-argument
  [authored prepared owner path]
  (let [context (assoc prepared :fn-defs [owner])
        entry (first (:definitions (source/plan-module [authored] context)))
        {:keys [records arguments]} (:legacy entry)
        [argument & other-arguments] (filter #(= path (:source-path %)) arguments)
        {:keys [binding-id slot-id]} (:origin argument)
        [row & other-rows] (filter #(and (= :binding (:kind %)) (= binding-id (:id %))) records)]
    (when-not (and argument (empty? other-arguments) (empty? other-rows)
                   (uuid? slot-id) (uuid? binding-id) (uuid? (:inline-fn-id argument))
                   (= (:identity entry) (:fn-id owner) (:fn-id row))
                   (= slot-id (:slot-id row))
                   (= binding-id (ids/binding-id (:fn-id owner) slot-id))
                   (= (:inline-fn-id argument) (:ref-fn-id row)))
      (reject! :inline-argument-ledger-required))
    argument))


(defn verify-child
  "Verify one direct inline body before its owner binding is omitted. The
   caller supplies the original definition from its pinned source file and the
   corresponding prepared package index. The result attests source identity
   and parsed child rows only. Consumers still prove use-site availability and
   bind the provenance to their file revision before writing anything.

   The child declaration can contain literal values. Do not log it."
  [prepared authored path]
  (when-not (and (vector? path) (= 2 (count path)) (= :args (first path))
                 (simple-keyword? (peek path)))
    (reject! :direct-inline-source-path-required))
  (let [{ns-id :namespace owner-name :name} authored
        owner (prepared-definition prepared ns-id owner-name)
        body (format/inline-binding-body (get-in authored path))]
    (when-not (and owner (uuid? (:fn-id owner)) (map? body) (contains? body :args))
      (reject! :inline-source-origin-required))
    (let [{:keys [expression inline-fn-id origin]} (original-argument authored prepared owner path)
          [operator inline-body] expression
          normalized (rebase/rewrite-references body #(parse/normalize-ref % (:name->id prepared) ns-id))
          generated (parse/inline-declaration normalized [owner-name (peek path)] ns-id)
          child (prepared-definition prepared ns-id (:name generated))
          prepared-ref (get-in owner path)
          ref (if (map? prepared-ref) (:ref prepared-ref) prepared-ref)
          child-id (ids/fn-id ns-id (:name generated))]
      (when-not (and (= :ref operator) (= ref (:name generated))
                     (= child-id inline-fn-id (:fn-id child))
                     (migration/same-edn? body (format/inline-origin inline-body)))
        (reject! :inline-declaration-origin-mismatch))
      (let [child-source (merge body (select-keys generated [:name :namespace :description]))
            context (assoc prepared :fn-defs [child])
            proof (first (:definitions (source/verify-module (source/plan-module [child-source] context) context)))]
        (when-not (and (= :authored-source-equivalent (:verification proof))
                       (= child-id (:identity proof) (get-in proof [:v2 :fn-id])))
          (reject! :inline-child-source-unverified))
        {:origin (assoc origin :source-path path
                        :definition-sha256 (ids/digest-hex "SHA-256" (migration/stable-edn authored)))
         :child (:v2 proof)}))))
