(ns graphden.editor.root-migration-templates
  "Deterministic graph declarations for migrating the finite pre-root
   component selection. Old callback identities remain referenced; their
   bodies and inherited edits are never copied into the new HOF cohort."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [graphden.editor.component-config :as config]
    [graphden.editor.root-legacy :as legacy]
    [graphden.packages.rebase :as rebase]))


(defn- refuse!
  []
  (throw (ex-info "Legacy root migration is unavailable"
                  {:type :browser-plan/unsupported :reason :invalid-configuration})))


(defn overrides
  "Return only authored role identities that differ from shipped callbacks.
   Missing roles inherit the shared root."
  [configuration]
  (when-not (map? configuration) (refuse!))
  ;; The ordinary configuration also contains components outside this root
  ;; bridge. Keep their checked selection intact for the later migration.
  (when-not (every? (set (keys config/component-contracts)) (keys configuration))
    (refuse!))
  (into (sorted-map)
        (keep (fn [[component {:keys [roles]}]]
                (let [selected (get configuration component)
                      _ (when (and (some? selected)
                                   (or (not (map? selected))
                                       (not-every? (set roles) (keys selected))))
                          (refuse!))
                      changed (into (sorted-map)
                                    (keep (fn [role]
                                            (let [id (get selected role)]
                                              (when (some? id)
                                                (when-not (uuid? id) (refuse!))
                                                (when-not (= id (legacy/default-role-id component role))
                                                  [role id])))))
                                    roles)]
                  (when (seq changed) [component changed]))))
        legacy/components))


(defn- packaged-definitions
  [path]
  (let [resource (str "packages/" (str/replace path "." "/") "/fns.edn")]
    (:fns (edn/read-string (slurp (io/resource resource))))))


(defn- local-name
  [component role suffix]
  (keyword (str "_migration-" (name component) "-" (name role) "-" suffix)))


(defn bridge-name
  "The editable child of a preserved callback in a migrated root."
  [component role]
  (local-name component role (if (and (= component :account-menu) (= role :view))
                               "base" "wrapper")))


(defn role-assoc-parents
  "Find each legacy role's assoc-fn from the shipped root component chain."
  [component]
  (let [{path :namespace :keys [roles root-component]} (get legacy/components component)
        definitions (into {} (map (juxt :name identity)) (packaged-definitions path))
        role-set (set roles)]
    (loop [current (get definitions root-component) seen #{} found {}]
      (if-not current
        (if (= role-set (set (keys found))) found (refuse!))
        (let [current-name (:name current)
              role (get-in current [:args :key :value])
              parent (:parent current)
              local-parent (when (and (keyword? parent)
                                      (or (nil? (namespace parent)) (= path (namespace parent))))
                             (keyword (name parent)))
              parent-definition (get definitions local-parent)
              map-argument (get-in current [:args :map])
              map-reference (if (map? map-argument) (:ref map-argument) map-argument)
              next-name (when (and (keyword? map-reference)
                                   (or (nil? (namespace map-reference)) (= path (namespace map-reference))))
                          (keyword (name map-reference)))]
          (when (contains? seen current-name) (refuse!))
          (when (and (contains? role-set role)
                     (or (nil? parent-definition)
                         (not= "assoc-fn" (some-> parent-definition :parent name))
                         (contains? found role)))
            (refuse!))
          (recur (get definitions next-name)
                 (conj seen current-name)
                 (cond-> found (contains? role-set role)
                         (assoc role (keyword path (name local-parent))))))))))


(defn root-component-return-type
  "Use the shipped component value's declared result type as migration ABI."
  [component]
  (let [{path :namespace :keys [root-component]} (get legacy/components component)
        definition (some #(when (= root-component (:name %)) %)
                         (packaged-definitions path))
        result (:return-type definition)]
    (if (and (keyword? result)
             (or (nil? (namespace result)) (= path (namespace result))))
      (keyword path (name result))
      (refuse!))))


(defn- wrapper-definitions
  [root component role]
  (let [{path :namespace wrappers :wrapper} (get legacy/components component)
        definitions (packaged-definitions path)
        local-names (set (map :name definitions))
        template (some #(when (= (get wrappers role) (:name %)) %) definitions)
        full-view (when (and (= component :account-menu) (= role :view))
                    (some #(when (= :root-view (:name %)) %) definitions))
        qualify (fn [reference]
                  (if (and (keyword? reference) (nil? (namespace reference))
                           (contains? local-names reference))
                    (keyword path (name reference))
                    reference))]
    (when-not (and template (or (not (and (= component :account-menu) (= role :view))) full-view))
      (refuse!))
    (let [bridge (assoc (rebase/rewrite-references template qualify)
                        :name (bridge-name component role)
                        :namespace root
                        :parent (keyword root (name (local-name component role "source"))))]
      (if full-view
        ;; Keep the selected callback's authored common-tree when present;
        ;; the shipped server tree fills a callback that omits that field.
        (let [tree-name (local-name component role "common-tree")
              tree {:name tree-name :namespace root :parent :get
                    :args {:coll {:ref (keyword root (name (:name bridge)))
                                  :type (:return-type full-view)}
                           :key (get-in full-view [:args :key])
                           :default (qualify (get-in full-view [:args :value]))}}]
          [bridge tree
           (-> full-view
               (rebase/rewrite-references qualify)
               (assoc :name (local-name component role "wrapper") :namespace root
                      :args {:map (keyword root (name (:name bridge)))
                             :key (get-in full-view [:args :key])
                             :value (keyword root (name tree-name))}))])
        [bridge]))))


(defn- component-definitions
  [root component changed]
  (let [{path :namespace base :root-component} (get legacy/components component)
        assoc-parents (role-assoc-parents component)
        roles (vec (keys changed))]
    (mapv (fn [index role]
            {:name (local-name component role "component")
             :namespace root
             :parent (get assoc-parents role)
             :return-type (root-component-return-type component)
             :args {:map (if (zero? index)
                           (keyword path (name base))
                           (keyword root (name (local-name component
                                                           (nth roles (dec index))
                                                           "component"))))
                    :key {:value role}
                    :value {:ref (keyword root (name (local-name component role "wrapper")))}}})
          (range (count roles)) roles)))


(defn- root-definitions
  [root changed]
  (let [components (vec (keys changed))
        steps (mapv (fn [index component]
                      (let [last-role (last (keys (get changed component)))]
                        {:name (keyword (str "_migration-root-" (name component)))
                         :namespace root
                         :parent :core.collections/assoc
                         :return-type :app.ui-config/_editor-bootstrap-result
                         :args {:map (if (zero? index)
                                       :app.ui-config/component-editor-root
                                       (keyword root (str "_migration-root-"
                                                          (name (nth components (dec index))))))
                                :key {:value component}
                                :value (keyword root (name (local-name component last-role "component")))}}))
                    (range (count components)) components)
        result (if (seq components)
                 (keyword root (name (:name (last steps))))
                 :app.ui-config/component-editor-root)]
    (conj steps
          {:name :editor-root :namespace root :parent :call
           :return-type :app.ui-config/_editor-bootstrap-result
           :args {:func {:ref result} :arg {:as :inputs :type :keyword-map}}})))


(defn definitions
  "Build a fresh personal HOF root from a checked legacy configuration.
   The returned aliases map to original role UUIDs only inside the parser."
  [root configuration]
  (when-not (and (string? root) (not (str/blank? root))) (refuse!))
  (let [changed (overrides configuration)
        wrappers (mapcat (fn [[component roles]]
                           (mapcat #(wrapper-definitions root component %) (keys roles))) changed)
        components (mapcat (fn [[component roles]]
                             (component-definitions root component roles)) changed)
        references (into {}
                         (mapcat (fn [[component roles]]
                                   (map (fn [[role id]]
                                          [(keyword root (name (local-name component role "source"))) id]) roles)))
                         changed)]
    {:definitions (vec (concat wrappers components (root-definitions root changed)))
     :references references :overrides changed}))
