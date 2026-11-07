(ns ^:serial graphden.crud.inheritance-command-graph-test
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.inheritance :as inheritance]
    [graphden.crud.inheritance.command :as command]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- outcome
  [f input]
  (try {:value (f input)}
       (catch clojure.lang.ExceptionInfo e
         {:error (ex-data e) :message (ex-message e)})))


(deftest graph-parser-matches-command-domain-and-error-order
  (let [a (str (random-uuid)) b (str (random-uuid))
        reparent {:action "reparent" :target-fn-id a :parent-ids [b]}
        variation {:action "variation" :target-fn-id a :source-fn-id b :expected-parent-id b}
        own {:action "variation" :kind "own-ref" :owner-fn-id a :source-fn-id b
             :binding-id a :slot-id b :expected-old-ref-id a}
        cases [nil [] false {} reparent variation own
               (assoc reparent :kind false)
               (assoc reparent :target-fn-id (parse-uuid a))
               (assoc reparent :target-fn-id 1)
               (assoc reparent :parent-ids nil)
               (assoc reparent :parent-ids (repeat 65 b))
               (assoc reparent :parent-ids [b b])
               (assoc reparent :parent-edge-index 0)
               (assoc reparent :occurrence-path [])
               (assoc variation :parent-edge-index 63 :occurrence-path [a b])
               (assoc variation :parent-edge-index 64)
               (assoc variation :parent-edge-index 0.5)
               (assoc variation :proposed-name " ")
               (assoc variation :proposed-name (str/join (repeat 161 "x")))
               (assoc variation :namespace-id nil :expected-state {:opaque true}
                      :accepted-orphan-binding-ids [] :proposed-fn-id false)
               {:action "batch" :commands [reparent]}
               {:action "batch" :commands [reparent reparent]}
               {:action "batch" :commands []}
               {:action "batch" :commands (repeat 17 reparent)}
               {:action "batch" :commands [own]}
               {:action "batch" :kind false :commands [reparent]}
               {:action "batch" :commands [(assoc reparent :expected-state nil)]}
               (assoc variation :target-fn-id nil :parent-edge-index "bad")]]
    (doseq [input cases]
      (is (= (outcome command/parse-command input)
             (outcome #(exec/execute-by-name harness/*context* "parse-inheritance-command" {:body %}) input))
          (pr-str input)))))


(deftest http-preview-and-apply-use-the-graph-parser
  (let [id (str (random-uuid))
        wire {:action "reparent" :target-fn-id id :parent-ids []}
        request {:request-method :post :uri "/api/inheritance/preview"
                 :headers {"content-type" "application/json"}
                 :body (json/generate-string wire)}
        seen (atom [])]
    (with-redefs [inheritance/preview (fn [_ cmd] (swap! seen conj cmd) {:ok true :allowed true})
                  inheritance/apply-operation! (fn [_ cmd] (swap! seen conj cmd) {:ok true :committed true})]
      (doseq [[handler uri] [["_inheritance-preview-handler" "/api/inheritance/preview"]
                             ["_inheritance-apply-handler" "/api/inheritance/apply"]]]
        (let [response (exec/execute-by-name harness/*context* handler {:request (assoc request :uri uri)})]
          (is (= 200 (:status response)))
          (is (true? (:ok (json/parse-string (:body response) true))))))
      (is (= [(command/parse-command wire) (command/parse-command wire)] @seen)))))


(deftest parsed-command-lists-retain-vector-indexing
  (let [target (random-uuid) source (random-uuid) orphan (random-uuid)
        parse #(exec/execute-by-name harness/*context* "parse-inheritance-command" {:body %})
        reparent {:action "reparent" :target-fn-id (str target) :parent-ids [(str source)]}
        variation {:action "variation" :target-fn-id (str target) :source-fn-id (str source)
                   :expected-parent-id (str source) :occurrence-path [(str target) (str source)]
                   :accepted-orphan-binding-ids [(str orphan)]}
        result (parse variation)
        batch (parse {:action "batch" :commands [reparent]})]
    (is (vector? (:occurrence-path result)))
    (is (= source (get-in result [:occurrence-path 1])))
    (is (vector? (:accepted-orphan-binding-ids result)))
    (is (= orphan (get-in result [:accepted-orphan-binding-ids 0])))
    (is (vector? (:commands batch)))
    (is (vector? (get-in batch [:commands 0 :parent-ids])))
    (is (= source (get-in batch [:commands 0 :parent-ids 0])))
    (doseq [[input field] [[(assoc reparent :parent-ids []) :parent-ids]
                           [(assoc variation :occurrence-path []) :occurrence-path]
                           [(assoc variation :accepted-orphan-binding-ids []) :accepted-orphan-binding-ids]]]
      (let [items (get (parse input) field)]
        (is (vector? items))
        (is (empty? items))))))


(deftest parser-reader-probes
  (let [id (random-uuid) text (str id)
        input {:action "reparent" :target-fn-id text :parent-ids []}
        call #(exec/execute-by-name harness/*context* %1 %2)]
    (is (= input (call "_inheritance-wire-body" {:body input})))
    (is (= text (call "_inheritance-wire-value" {:wire-value text})))
    (is (= id (call "_inheritance-wire-uuid" {:wire-value text})))
    (is (= id (call "_inheritance-wire-uuid" {:wire-value id})))
    (is (= id (call "_inheritance-wire-required-uuid" {:wire-value text :wire-field :target-fn-id})))
    (is (= [:target-fn-id id] (vec (call "_inheritance-wire-required-pair" {:body input :item :target-fn-id}))))
    (is (= {:target-fn-id id} (call "_inheritance-wire-single-required" {:body input})))
    (is (= (command/parse-command input) (call "_inheritance-wire-single" {:body input})))))


(deftest preview-presentation-preserves-safe-plan-and-nested-navigation
  (let [target-id (random-uuid)
        source-id (random-uuid)
        raw-target {:id target-id :name "target" :namespace-id (random-uuid)
                    :namespace-path "org.space"}
        raw-source {:id source-id :name "source" :namespace-id (random-uuid)
                    :namespace-path nil}
        raw {:ok true :allowed true :expected-state "opaque-token"
             :orphan-binding-ids [source-id]
             :target raw-target :source raw-source
             :candidates [{:id source-id :name "source" :namespace-path ""
                           :current? true :target raw-target}]
             :targets [{:target raw-target :source raw-source :allowed false
                        :reason "readable refusal"}]}
        expected {:ok true :allowed true :expected-state "opaque-token"
                  :orphan-binding-ids [source-id]
                  :target (-> raw-target (dissoc :namespace-path)
                              (assoc :qualified-name "org.space/target"))
                  :source (-> raw-source (dissoc :namespace-path)
                              (assoc :qualified-name "source"))
                  :candidates [{:id source-id :name "source" :current? true
                                :qualified-name "/source"
                                :target (-> raw-target (dissoc :namespace-path)
                                            (assoc :qualified-name "org.space/target"))}]
                  :targets [{:target (-> raw-target (dissoc :namespace-path)
                                         (assoc :qualified-name "org.space/target"))
                             :source (-> raw-source (dissoc :namespace-path)
                                         (assoc :qualified-name "source"))
                             :allowed false :reason "readable refusal"}]}
        call #(exec/execute-by-name harness/*context* %1 %2)]
    (is (= expected (call "_inheritance-present-result" {:model raw})))
    (with-redefs [inheritance/preview (fn [_ _] raw)]
      (let [result (call "preview-inheritance"
                         {:command {:action "reparent"}
                          :request {:request-method :post :uri "/api/inheritance/preview"}})]
        (is (= expected result))
        (is (vector? (:candidates result)))
        (is (vector? (:targets result)))
        (is (= source-id (get-in result [:candidates 0 :id])))
        (is (= target-id (get-in result [:targets 0 :target :id])))))
    (with-redefs [inheritance/preview (fn [_ _] {:ok true :candidates [] :targets []})]
      (let [result (call "preview-inheritance" {:command {} :request {}})]
        (doseq [field [:candidates :targets]]
          (is (vector? (get result field)))
          (is (empty? (get result field))))))
    (is (= {:name nil :namespace-id nil}
           (call "_inheritance-present-descriptor-projected"
                 {:descriptor {:name nil :namespace-id nil :namespace-path "org.space"}})))))
