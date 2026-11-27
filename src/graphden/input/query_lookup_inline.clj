(ns graphden.input.query-lookup-inline
  "Preserve a verified query lookup at its original enclosing reference."
  (:require
    [graphden.executor.compile.surface :as surface]
    [graphden.input.analysis :as analysis]
    [graphden.input.legacy-use :as legacy-use]
    [graphden.input.migration :as migration]
    [graphden.input.query-lookup-package :as query]
    [graphden.input.source :as source-plan]
    [graphden.input.source-legacy-readers :as readers]
    [graphden.input.source-origins :as origins]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- original
  [package id]
  (let [[form & others] (get-in package [:originals id])]
    (when-not (seq others) form)))


(defn- query-site
  [{:keys [args]}]
  (let [[field & others] (keep (fn [[field body]]
                                 (when (and (map? body) (#{:query-param :web.crud/query-param} (:parent body)))
                                   field))
                               args)]
    (when (and (simple-keyword? field) (empty? others))
      [:args field])))


(defn- original-rows
  [{:keys [prepared declarations]} id]
  (when-let [definition (get declarations id)]
    (parse/parse-prepared-module (assoc prepared :fn-defs [definition]))))


(defn- parent-support
  [package parent root-entry]
  (let [parent-source (original package parent)
        rows (original-rows package parent)
        types (into #{} (keep :type-fn-id) rows)
        types (into types (keep :return-type-fn-id) rows)]
    (when (and parent-source (= parent (:fn-id root-entry))
               (map? (:args root-entry)) (= #{} (:effects root-entry))
               (not-any? parent-source [:parent :parents]))
      (cons parent-source (keep (partial original package) (sort types))))))


(defn- literal-siblings?
  [rows child]
  (let [bindings (filter #(= :binding (:kind %)) rows)
        refs (filter :ref-fn-id bindings)]
    (and (= [child] (mapv :ref-fn-id refs))
         (every? #(and (not-any? % [:resolver-fn-id :list-closed :list-append])
                       (or (= child (:ref-fn-id %)) (true? (:value-present %))))
                 bindings))))


(defn- public-subtree?
  [checked prefix]
  (let [routes (get-in checked [:public-input :routes])
        request-path (conj prefix :request)
        depth (count request-path)]
    (and (= :reference-inputs (get-in routes [prefix :kind]))
         (every? (fn [[path {:keys [kind]}]]
                   (or (not= :input-field kind)
                       (and (<= depth (count path)) (= request-path (subvec path 0 depth)))))
                 routes))))


(defn- wrapper-origin
  [graph owner child field prior current]
  (let [{:keys [stored-slot declarations binding]} (legacy-use/select-binding graph owner)
        [native-slot :as path] (:target-path binding)
        old-id (:id binding)
        native-id (when (uuid? native-slot) (ids/binding-id owner native-slot))
        prior-row (some #(when (= old-id (:id %)) %) prior)
        covered (when (and (= [[owner field]] declarations) (= [native-slot] path)
                           (= :input-binding (:kind binding)) (= "ref" (:expression-kind binding))
                           (= child (:ref-fn-id binding) (:ref-fn-id prior-row))
                           (= stored-slot (:slot-id prior-row)) (uuid? old-id) (uuid? native-id))
                  {:ext-name field :slot-id native-slot})]
    (cond
      (migration/same-edn? prior current) (if covered {:covered covered} {})
      (and covered (migration/same-edn? (mapv #(cond-> % (= old-id (:id %)) (assoc :id native-id :slot-id native-slot)) prior)
                                        current))
      {:covered covered :binding binding :native-id native-id :origin-slot stored-slot})))


(defn- retain-wrapper-binding
  [package owner {:keys [binding native-id]}]
  (if-not binding
    package
    (let [address [[owner []] (:target-path binding)]
          path [:closure-baselines owner :v2]
          v2 (get-in package path)
          bindings (:bindings v2)
          [row & others] (filter #(= native-id (:id %)) bindings)]
      (when (and (empty? others) (migration/same-edn? binding (assoc row :id (:id binding)))
                 (= native-id (get-in v2 [:binding-state address :binding-id])))
        (assoc-in package path
                  (-> v2
                      (assoc-in [:binding-state address :binding-id] (:id binding))
                      (assoc :bindings (mapv #(if (= native-id (:id %)) binding %) bindings))))))))


(defn- checked-wrapper
  [normalized owner child prefix proof roots origin]
  (when-let [normalized (retain-wrapper-binding (snapshot/with-certified-owner normalized owner roots) owner origin)]
    (let [entry (get-in normalized [:closure-baselines owner])
          {:keys [context options]} (snapshot/freeze-source-analysis normalized [owner] roots)
          checked (analysis/analyze-effective-input context owner options)
          input (get-in checked [:public-input :routes (conj prefix :request)])]
      (when (and (= :checked (:status checked)) (public-subtree? checked prefix)
                 (= :input-field (:kind input)) (true? (:required input))
                 (= (:request-type-id proof) (get-in context [:fields (:origin-field-id input) :type-fn-id]))
                 (= :authored-source-equivalent (:verification entry))
                 (some (fn [{:keys [from to target-fn-id mode]}]
                         (let [node (get-in checked [:scopes (second to)])]
                           (and (= child target-fn-id (:fn-id node)) (= [owner []] (first from))
                                (= :value mode) (= [:union :null :text] (get-in node [:signature :return])))))
                       (:dependencies checked)))
        {:proposed (:proposed entry) :entry entry :package normalized
         :visibility (set (keys (:declarations normalized)))}))))


(defn- same-public-readers?
  [graph owner {:keys [covered]} proof]
  (let [routes (surface/surface-entries owner graph)
        exposed (when covered (filterv #(not= covered %) routes))
        boundary (:boundary proof)]
    (and (= (:accepted proof) (:accepted (surface/surface-names owner graph)))
         (or (migration/same-edn? boundary routes)
             (and exposed (= 1 (- (count routes) (count exposed)))
                  (migration/same-edn? boundary exposed))))))


(defn- enclosing-expansion
  [package {:keys [source owner site child declaration prior support]} roots]
  (when-let [{child-package :package native :roots :keys [proof] :as expanded}
             (query/prepare (assoc-in package [:originals child] [declaration]) child roots)]
    (let [reference (if (seq (:namespace declaration))
                      (keyword (:namespace declaration) (name (:name declaration)))
                      (:name declaration))
          outer (assoc-in source site reference)
          forms (->> (concat [outer] support (mapcat val (:originals child-package)))
                     distinct (sort-by (juxt :namespace :name)) vec)
          prepared (parse/prepare-module forms {} {})
          normalized (-> (snapshot/package-index prepared forms)
                         (cond-> (simple-keyword? reference)
                           (update :prepared source-plan/with-local-reference child reference))
                         (assoc :legacy-graph (readers/owner-index prepared child))
                         (snapshot/with-certified-owner child native)
                         (assoc :legacy-graph (readers/owner-index prepared owner)))
          graph (:legacy-graph package)
          origin (wrapper-origin graph owner child (peek site) prior (original-rows normalized owner))]
      (when (and origin (same-public-readers? graph owner origin proof))
        (when-let [wrapper (checked-wrapper normalized owner child [(peek site)] proof native origin)]
          (-> (merge expanded wrapper {:original source :expanded outer :wrapper-origin origin})
              (update :donors into (get-in graph [:fn-map owner :parent-ids]))))))))


(defn prepare
  "Give the expanded child its original parser-generated name and prove that
   referring to it preserves every enclosing row. Only an enclosing native
   call with literal siblings and the same public readers qualifies."
  [{:keys [legacy-graph inline-origins] :as package} owner roots]
  (when-let [source (original package owner)]
    (when-let [site (query-site source)]
      (let [legacy-graph (or legacy-graph (readers/owner-index (:prepared package) owner))
            package (assoc package :legacy-graph legacy-graph)
            {child :fn-id declaration :declaration} (origins/child-at @inline-origins owner site)
            [parent & others] (get-in legacy-graph [:fn-map owner :parent-ids])
            prior (original-rows package owner)]
        (when (and (uuid? child) (nil? (original package child)) (empty? others)
                   (literal-siblings? prior child))
          (when-let [support (parent-support package parent (get roots parent))]
            (enclosing-expansion package {:source source :owner owner :site site :child child
                                          :declaration declaration :prior prior :support support} roots)))))))
