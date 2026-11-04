(ns graphden.crud.graph-search-projection-contract-test
  "Retained native cohort contracts: role annotation follows selection on
   one environment. Eager roled facts turn a capped one-row search or root
   namespace lookup into a whole-graph role walk. No global seams are changed."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.entities.list :as entity-list]))


(defn- project-search
  [env q descriptions?]
  ((ns-resolve 'graphden.crud.entities.list 'list-scope-search)
   env q descriptions?))


(defn- project-namespace
  [env namespace-id]
  ((ns-resolve 'graphden.crud.entities.list 'list-scope-namespace)
   env namespace-id))


(defn- environment
  [rows namespaces annotated]
  {:base {:fns rows}
   :namespaces namespaces
   :rev-index (delay {})
   :role-of (fn [row]
              (swap! annotated conj (:id row))
              (assoc row :role :composed))})


(deftest search-annotates-only-the-ranked-capped-cohort
  (let [exact {:id (random-uuid) :name "target"}
        rows (conj (mapv (fn [n] {:id n :name (str "target-helper-" n)})
                         (range 9999))
                   exact)
        annotated (atom [])
        env (environment rows (delay []) annotated)]
    (binding [entity-list/*default-search-limit* 1]
      (let [out (project-search env " TaRgEt " false)]
        (is (= [(:id exact)] (mapv :id (:fns out))))
        (is (true? (:truncated? out)))
        (is (= [(:id exact)] @annotated)
            "10,000 matches require one role annotation, after exact-name ranking")))))


(deftest blank-search-does-not-load-namespaces-or-annotate-roles
  (doseq [q [nil "" "  "]]
    (let [annotated (atom [])
          namespaces (delay (throw (ex-info "Blank query must not load namespaces" {})))
          env (environment [{:id (random-uuid) :name "target"}] namespaces annotated)]
      (is (= {:fns [] :truncated? false} (project-search env q true)))
      (is (empty? @annotated))
      (is (not (realized? namespaces))))))


(deftest search-keeps-four-tiers-and-qualified-slash-spelling
  (let [nid (random-uuid)
        rows [{:id 4 :name "concept" :description "TARGET concept"}
              {:id 3 :name "other" :namespace-id nid}
              {:id 2 :name "target-helper"}
              {:id 1 :name "target"}]
        env (environment rows (delay [{:id nid :name "targetspace"}]) (atom []))]
    (testing "description matches follow all name tiers"
      (is (= [1 2 3 4] (mapv :id (:fns (project-search env "target" true)))))
      (is (= [1 2 3] (mapv :id (:fns (project-search env "target" false))))))
    (testing "canonical namespace/name spelling is accepted"
      (is (= [3] (mapv :id (:fns (project-search env "TARGETSPACE/other" false))))))))


(deftest namespace-selection-precedes-annotation-and-nil-selects-root
  (let [nid (random-uuid)
        rows (into [{:id :root :name "root" :namespace-id nil}
                    {:id :anonymous :name nil :namespace-id nil}]
                   (map (fn [n] {:id n :name (str "other-" n) :namespace-id nid}))
                   (range 9998))
        annotated (atom [])
        namespaces (delay (throw (ex-info "Namespace projection does not load namespace rows" {})))
        env (environment rows namespaces annotated)]
    (is (= [:root] (mapv :id (:fns (project-namespace env nil)))))
    (is (= [:root] @annotated)
        "nil is the root bucket, not an omitted namespace filter")
    (is (not (realized? namespaces)))
    (reset! annotated [])
    (is (= {:fns []} (project-namespace env (random-uuid))))
    (is (empty? @annotated))
    (is (not (realized? namespaces)))))
