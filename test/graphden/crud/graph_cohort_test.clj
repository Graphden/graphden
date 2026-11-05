(ns ^:serial graphden.crud.graph-cohort-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.entities.list :as listing]
    [graphden.tenancy.context :as tenancy]))


(defn- with-env
  [env f]
  (with-redefs-fn {(ns-resolve 'graphden.crud.entities.list 'env) (constantly env)} f))


(deftest cohort-is-data-only-and-annotations-use-captured-rows
  (let [id (random-uuid)
        hidden (random-uuid)
        denied-namespace (random-uuid)
        row {:id id :name "before" :parent-ids [(random-uuid)] :impl (fn [])}
        annotations (atom [])
        metadata-read (atom [])
        env {:base {:fns [row {:id hidden :name "hidden"}] :bindings [{:secret true}]}
             :namespaces (delay [{:id denied-namespace :name "denied"}]) :rev-index (delay {})
             :rich-snapshot (delay (swap! metadata-read conj :rich))
             :diag-counts (delay (swap! metadata-read conj :diagnostics))
             :role-of (fn [f] (swap! annotations conj f) (assoc f :role :composed))}]
    (with-redefs [tenancy/apply-graph-read-filter
                  #(-> %
                       (update :fns (fn [rows] (filterv (fn [r] (= id (:id r))) rows)))
                       (assoc :namespaces []))]
      (with-env env
        #(let [out (listing/project-graph-cohort
                     {} (fn [cohort]
                          (is (= #{:rows :paths :limit} (set (keys cohort))))
                          (is (= [{:id id :name "before" :namespace-id nil :description nil}]
                                 (:rows cohort)))
                          (is (empty? (:paths cohort)))
                          (is (= [:rich :diagnostics] @metadata-read))
                          {:ids [id] :truncated? false}) true true)]
           (is (= [row] @annotations))
           (is (= "before" (:name (first (:fns out)))))
           (is (not (contains? (first (:fns out)) :impl))))))))


(deftest callback-cannot-forge-or-expand-authorized-cohort
  (let [id (random-uuid)
        env {:base {:fns [{:id id :name "safe"}]} :namespaces (delay [])
             :rev-index (delay {}) :rich-snapshot (delay {}) :diag-counts (delay {})
             :role-of (fn [_] (throw (ex-info "Invalid IDs must not reach annotation" {})))}]
    (doseq [selection [{:ids [(random-uuid)] :truncated? false}
                       {:ids [id id] :truncated? false}
                       {:ids [id] :truncated? nil}]]
      (with-env env
        #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid graph selection"
               (listing/project-graph-cohort {} (constantly selection) false true)))))))


(deftest selection-and-projection-share-one-acquisition
  (let [id (random-uuid)
        row {:id id :name "original"}
        source (atom {:fns [row]})
        acquisitions (atom 0)]
    (with-redefs-fn
      {(ns-resolve 'graphden.crud.entities.list 'env)
       (fn [_]
         (swap! acquisitions inc)
         {:base @source :rev-index (delay {}) :rich-snapshot (delay {})
          :diag-counts (delay {}) :role-of #(assoc % :role :composed)})}
      #(let [out (listing/project-graph-cohort
                   {} (fn [cohort]
                        (is (= "original" (:name (first (:rows cohort)))))
                        (reset! source {:fns [(assoc row :name "replacement")]})
                        {:ids [id] :truncated? false}) false true)]
         (is (= 1 @acquisitions))
         (is (= "original" (:name (first (:fns out)))))))))
