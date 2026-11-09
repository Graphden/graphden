(ns graphden.input.runtime-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.input.runtime :as input])
  (:import
    (java.util
      HashMap)
    (java.util.concurrent
      Callable
      CountDownLatch
      Executors
      Future
      TimeUnit)))


(defn- counted-cell
  [reads key value]
  (input/field-cell (fn [] (swap! reads update key (fnil inc 0)) value)))


(defn- throwing-cell
  [reads key]
  (input/field-cell (fn []
                      (swap! reads update key (fnil inc 0))
                      (throw (ex-info "unselected field was read" {:field key})))))


(defbase read-user-name [user]
  (let [{:keys [name]} user]
    (str name "/" (get user :name))))


(deftest ordinary-base-impl-and-nested-lookup-are-field-lazy
  (let [reads (atom {})
        user (input/record-view {:name (counted-cell reads :name "Ada")
                                 :email (throwing-cell reads :email)})
        root (input/record-view {:user (counted-cell reads :user user)
                                 :unselected (throwing-cell reads :unselected)})]
    (is (= "Ada" (get-in root [:user :name])))
    (is (= "Ada/Ada" (read-user-name {:user user} nil)))
    (is (= "Ada" (let [{{:keys [name]} :user} root] name)))
    (is (= "Ada" (:name (:user root))))
    (is (= "missing" (get-in root [:user :unknown] "missing")))
    (is (= {:user 1 :name 1} @reads))))


(deftest shape-operations-and-map-entry-keys-do-not-force-values
  (let [reads (atom {})
        record (input/record-view (array-map :a (throwing-cell reads :a)
                                             :b (throwing-cell reads :b)))]
    (is (map? record))
    (is (instance? java.util.Map record))
    (is (= 2 (count record)))
    (is (contains? record :a))
    (is (not (contains? record :unknown)))
    (is (= [:a :b] (vec (keys record))))
    (is (= #{:a :b} (set (.keySet ^java.util.Map record))))
    (is (= :a (key (find record :a))))
    (is (= :a (key (first (seq record)))))
    (is (= 2 (count (first (seq record)))))
    (is (= :a (nth (first (seq record)) 0)))
    (is (nil? (find record :unknown)))
    (is (= :missing (record :unknown :missing)))
    (is (= {:kind :input-record :field-count 2} (input/diagnostic-summary record)))
    (is (empty? @reads))))


(deftest nil-false-absence-and-callable-literals-stay-distinct
  (let [calls (atom 0)
        callable (fn [] (swap! calls inc))
        delayed (delay (swap! calls inc))
        opaque {:ref callable :value delayed :nested {:x :keyword}}
        record (input/record-view {:nil (input/value-cell nil)
                                   :false (input/value-cell false)
                                   :callback (input/value-cell callable)
                                   :delay (input/value-cell delayed)
                                   :dict (input/value-cell opaque)})]
    (is (nil? (get record :nil :missing)))
    (is (false? (get record :false :missing)))
    (is (= :missing (get record :absent :missing)))
    (is (contains? record :nil))
    (is (identical? callable (:callback record)))
    (is (identical? delayed (:delay record)))
    (is (identical? opaque (:dict record)))
    (is (identical? opaque (input/materialize-record opaque)))
    (is (identical? opaque (:dict (input/materialize-record record))))
    (is (zero? @calls))
    (is (= 1 ((:callback record))))))


(deftest assoc-dissoc-and-metadata-preserve-unread-shared-cells
  (let [reads (atom {})
        original (input/record-view {:a (counted-cell reads :a 1)
                                     :b (throwing-cell reads :b)})
        with-meta (with-meta original {:source :test})
        replaced (assoc with-meta :b 2)
        removed (dissoc original :b)]
    (is (empty? @reads))
    (is (= {:source :test} (meta replaced)))
    (is (= #{:a :b} (set (keys original))))
    (is (= #{:a} (set (keys removed))))
    (is (= 2 (:b replaced)))
    (is (= 1 (:a replaced) (:a removed) (:a original)))
    (is (= {:a 1} @reads))
    (is (= {} (empty original)))
    (is (= {:source :test} (meta (empty with-meta))))
    (is (= {:a 1 :b 2 :c 3} (conj replaced [:c 3])))
    (is (= {:a 1} @reads))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unselected" (:b original)))))


(deftest seq-and-reduce-consume-only-requested-entry-values
  (let [reads (atom {})
        record (input/record-view (array-map :a (counted-cell reads :a 1)
                                             :b (throwing-cell reads :b)))
        entry (first (seq record))]
    (is (vector? entry))
    (is (empty? @reads))
    (is (= [:a 1] entry))
    (is (= 1 (val entry) (nth entry 1) (.getValue ^java.util.Map$Entry entry)))
    (is (= 1 (first (vals record))))
    (is (= {:a 1} (select-keys record [:a])))
    (is (= [:a 1] (reduce-kv (fn [_ key value] (reduced [key value])) nil record)))
    (is (= {:a 1} @reads))))


(deftest equality-and-hash-are-normal-value-consumption
  (let [reads (atom {})
        record (input/record-view {:a (counted-cell reads :a 1)
                                   :b (counted-cell reads :b false)})
        value {:a 1 :b false}
        java-map (HashMap. value)]
    (is (= value record))
    (is (= record value))
    (is (.equals record java-map))
    (is (.equals java-map record))
    (is (= (hash value) (hash record)))
    (is (= (.hashCode value) (.hashCode record)))
    (is (= value (into {} record)))
    (is (= (set value) (set (seq record))))
    (is (= :found (get {record :found} value)))
    (is (= :found (get {value :found} record)))
    (is (= {:a 1 :b 1} @reads))))


(deftest serialization-and-explicit-materialization-consume-values
  (let [reads (atom {})
        child (input/record-view {:x (counted-cell reads :x 1)})
        record (input/record-view {:child (input/value-cell child)
                                   :flag (counted-cell reads :flag false)})]
    (is (= {:child {:x 1} :flag false} (json/parse-string (json/generate-string record) true)))
    (is (= {:x 1 :flag 1} @reads))
    (is (= {:child {:x 1} :flag false} (input/materialize-record record)))
    (is (not (input/record-view? (:child (input/materialize-record record)))))
    (is (= {:x 1 :flag 1} @reads))))


(deftest token-hashing-and-comparison-do-not-read-record-values
  (let [reads (atom {})
        cell (throwing-cell reads :field)
        record (input/record-view {:field cell})
        same-origin (input/record-view {:field cell})
        independent (input/record-view {:field (throwing-cell reads :other)})
        token (input/cache-token record)
        cache (HashMap.)]
    (.put cache token :cached)
    (is (= :cached (.get cache (input/cache-token same-origin))))
    (is (= token (input/cache-token (with-meta record {:other :metadata}))))
    (is (= (hash token) (hash (input/cache-token same-origin))))
    (is (not= token (input/cache-token independent)))
    (is (not= token (input/cache-token (assoc record :field nil))))
    (is (= {} (input/cache-token (dissoc record :field))))
    (is (empty? @reads))
    (is (thrown? clojure.lang.ExceptionInfo (input/cache-token {:ordinary :map}))))
  (testing "ordinary value-equal arguments retain their existing cache semantics"
    (let [cache (HashMap.)]
      (.put cache [{:x 1}] :cached)
      (is (= :cached (.get cache [(into {} [[:x 1]])]))))))


(deftest two-projections-share-one-producer-cell
  (let [reads (atom {})
        producer (counted-cell reads :producer {:name "Ada" :email "ada@example.test"})
        record (input/record-view {:name (input/field-cell #(get @producer :name))
                                   :email (input/field-cell #(get @producer :email))})]
    (is (= "Ada" (:name record)))
    (is (= "ada@example.test" (:email record)))
    (is (= {:producer 1} @reads))))


(deftest independent-invocations-have-independent-cells
  (let [reads (atom 0)
        invocation #(input/record-view {:value (input/field-cell (fn [] (swap! reads inc)))})
        first-call (invocation)
        next-call (invocation)]
    (is (= 1 (:value first-call) (:value first-call)))
    (is (= 2 (:value next-call) (:value next-call)))
    (is (not= (input/cache-token first-call) (input/cache-token next-call)))))


(deftest invalid-field-key-cannot-make-cache-token-hashing-run-values
  (let [reads (atom {})
        lazy-key (input/record-view {:value (throwing-cell reads :key)})
        record (input/record-view {:a (input/value-cell 1)})]
    (doseq [key ["field" 1 nil lazy-key]]
      (is (thrown? clojure.lang.ExceptionInfo
            (input/record-view (array-map key (input/value-cell 2)))))
      (is (thrown? clojure.lang.ExceptionInfo (assoc record key 2)))
      (is (= :missing (get record key :missing)))
      (is (not (contains? record key))))
    (is (empty? @reads))))


(deftest failures-are-once-values-too
  (let [reads (atom 0)
        failure (ex-info "expected" {})
        record (input/record-view {:fail (input/field-cell (fn [] (swap! reads inc) (throw failure)))})
        read-failure #(try (:fail record) (catch clojure.lang.ExceptionInfo e e))]
    (is (identical? failure (read-failure)))
    (is (identical? failure (read-failure)))
    (is (= 1 @reads))))


(deftest concurrent-readers-compute-one-cell-once
  (let [pool (Executors/newFixedThreadPool 4)
        start (CountDownLatch. 1)
        reads (atom 0)
        record (input/record-view {:value (input/field-cell #(swap! reads inc))})]
    (try
      (let [workers (mapv (fn [_]
                            (.submit pool ^Callable
                                     (fn []
                                       (.await start 5 TimeUnit/SECONDS)
                                       (:value record))))
                          (range 4))]
        (.countDown start)
        (is (= [1 1 1 1] (mapv #(.get ^Future % 5 TimeUnit/SECONDS) workers)))
        (is (= 1 @reads)))
      (finally
        (.shutdownNow pool)
        (.awaitTermination pool 5 TimeUnit/SECONDS)))))
