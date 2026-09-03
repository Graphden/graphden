(ns graphden.lint.graph-test
  "`graph->fn-defs` over hand-built rows — the shape the branch snapshot
   carries — and the warnings the engine draws from it. No storage:
   the translation is pure, and `lint-branch` is one memoised call
   around it (pinned by the crud graph test)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.lint.core :as lint]
    [graphden.lint.graph :as lg]))


(defn- uid
  [n]
  (java.util.UUID/fromString (format "00000000-0000-0000-0000-%012d" n)))


(def ^:private ns-rows
  [{:id (uid 1) :name "app" :parent-id nil}
   {:id (uid 2) :name "editor" :parent-id (uid 1)}])


;; base-fns: assoc (map/key/value), const (value); type-row: text
(def ^:private assoc-id (uid 10))
(def ^:private const-id (uid 11))
(def ^:private text-id (uid 12))
(def ^:private slot-map (uid 20))
(def ^:private slot-key (uid 21))
(def ^:private slot-value (uid 22))
(def ^:private slot-const (uid 23))


(defn- base-rows
  []
  {:fns [{:id assoc-id :name "assoc" :parent-ids [] :return-type-fn-id text-id}
         {:id const-id :name "const" :parent-ids [] :return-type-fn-id text-id}
         {:id text-id :name "text" :parent-ids []}]
   :slots [{:id slot-map :name "map" :type-fn-id text-id}
           {:id slot-key :name "key" :type-fn-id text-id}
           {:id slot-value :name "value" :type-fn-id text-id}
           {:id slot-const :name "value" :type-fn-id text-id}]
   :fn-slots [{:fn-id assoc-id :slot-id slot-map :position 0}
              {:fn-id assoc-id :slot-id slot-key :position 1}
              {:fn-id assoc-id :slot-id slot-value :position 2}
              {:fn-id const-id :slot-id slot-const :position 0}]
   :bindings []
   :list-items []})


(defn- composed
  "A named child of assoc in ns `editor` binding map/key literally and
   value by ref."
  [n nm value-ref]
  (let [fid (uid n)]
    {:fn {:id fid :name nm :namespace-id (uid 2) :parent-ids [assoc-id]}
     :bindings [{:id (uid (+ 100 n)) :fn-id fid :slot-id slot-map :value {:class "x"} :value-present true}
                {:id (uid (+ 200 n)) :fn-id fid :slot-id slot-key :value :title :value-present true}
                {:id (uid (+ 300 n)) :fn-id fid :slot-id slot-value :ref-fn-id value-ref}]}))


(defn- graph-with
  [& composed-rows]
  (-> (base-rows)
      (update :fns into (map :fn composed-rows))
      (update :bindings into (mapcat :bindings composed-rows))))


(deftest graph->fn-defs-test
  (let [title {:fn {:id (uid 30) :name "title" :namespace-id (uid 1) :parent-ids [const-id]
                    :return-type-fn-id text-id :lambda-params [] :branch-local? true}
               :bindings [{:id (uid 130) :fn-id (uid 30) :slot-id slot-const :value "t" :value-present true}]}
        page (composed 31 "page" (uid 30))
        {:keys [fn-defs base-fn-names]} (lg/graph->fn-defs (graph-with title page) ns-rows)
        by-name (into {} (map (juxt :name identity)) fn-defs)]
    (testing "only composed rows become fn-defs; base-fns and type-rows are the ref vocabulary"
      (is (= #{:title :page} (set (keys by-name))))
      (is (= #{:assoc :const :text} base-fn-names)))
    (testing "the fn-def carries id, dotted namespace, qualified parent + refs, and the declaration fields"
      (is (= {:id (uid 30) :name :title :namespace "app" :parents [:const]
              :args {:value {:value "t"}} :return-type :text :lambda-params [] :branch-local? true}
             (:title by-name)))
      (is (= {:id (uid 31) :name :page :namespace "app.editor" :parents [:assoc]
              :args {:map {:value {:class "x"}} :key {:value :title} :value :app/title}}
             (:page by-name))))))


(deftest graph->fn-defs-shapes-test
  (testing "list bindings become vectors of items; renames fold into {:as}; type pins into {:type}"
    (let [fid (uid 40)
          renamed-slot (uid 41)
          g (-> (base-rows)
                (update :fns conj {:id fid :name "rows" :namespace-id (uid 1) :parent-ids [assoc-id]})
                (update :slots conj {:id renamed-slot :name "row" :type-fn-id text-id :source-slot-id slot-map})
                (update :fn-slots conj {:fn-id fid :slot-id renamed-slot :position 0})
                (update :bindings into [{:id (uid 140) :fn-id fid :slot-id slot-key :list-append true}
                                        {:id (uid 141) :fn-id fid :slot-id slot-value :ref-fn-id const-id
                                         :type-override-fn-id text-id}])
                (update :list-items into [{:binding-id (uid 140) :position 1 :value 2}
                                          {:binding-id (uid 140) :position 0 :ref-fn-id const-id}
                                          {:binding-id (uid 140) :position 2 :value :kw :literal true}]))
          [fd] (:fn-defs (lg/graph->fn-defs g ns-rows))]
      (is (= {:map {:as :row}
              :key [:const {:value 2} {:value :kw :literal? true}]
              :value {:ref :const :type :text}}
             (:args fd)))))
  (testing "a nameless row gets an _anon- label the engine treats as generated"
    (let [g (-> (base-rows) (update :fns conj {:id (uid 50) :name nil :parent-ids [assoc-id]}))
          [fd] (:fn-defs (lg/graph->fn-defs g ns-rows))]
      (is (= (keyword (str "_anon-" (uid 50))) (:name fd))))))


(deftest lint-graph-test
  (let [title {:fn {:id (uid 30) :name "title" :namespace-id (uid 1) :parent-ids [const-id]}
               :bindings [{:id (uid 130) :fn-id (uid 30) :slot-id slot-const :value "t" :value-present true}]}
        a (composed 31 "page-attrs" (uid 30))
        b (composed 32 "row-attrs" (uid 30))
        g (graph-with title a b)]
    (testing "two composed rows with the same structure are one duplicate-definition warning, keyed by id"
      (let [[f :as fs] (lg/lint-graph g ns-rows #{})]
        (is (= 1 (count fs)))
        (is (= :duplicate-definition (:rule f)))
        (is (= [(uid 31) (uid 32)] (:fn-ids f)))
        (is (= [["app.editor" :page-attrs] ["app.editor" :row-attrs]] (:fns f)))))
    (testing "the finding's key suppresses it"
      (let [[f] (lg/lint-graph g ns-rows #{})]
        (is (empty? (lg/lint-graph g ns-rows #{(lint/finding-key f)})))))
    (testing "a suppression entry as the graph stores it (strings) matches the key"
      (is (empty? (lg/lint-graph g ns-rows
                                 #{[:duplicate-definition [(str (uid 31)) (str (uid 32))]]}))))))
