(ns graphden.input.memo-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.memo :as memo]))


(defn- frame
  [expressions arguments]
  {:ir {:expressions expressions
        :scopes (update-vals arguments #(hash-map :argument-paths %))}
   :supplied {}
   :present (zipmap (keys expressions) (repeat true))
   ;; Memo construction must never dereference even one of these sentinels.
   :cells (into {} (map (fn [address] [address (delay (throw (Exception. "forced")))]))
                (keys expressions))})


(deftest authored-local-computations-share-across-named-use-scopes
  (let [a [:root [:a]] b [:root [:b]]
        source-a [:root [:a :source]] source-b [:root [:b :source]]
        f (frame {[a [:x]] {:op :invoke :binding-id :local :target-id :producer :type :text :scope source-a}
                  [b [:x]] {:op :invoke :binding-id :local :target-id :producer :type :text :scope source-b}}
                 {a {:x [:x]} b {:x [:x]} source-a {} source-b {}})
        keys (memo/scope-keys f)]
    (is (= (get keys a) (get keys b)))
    (is (number? (hash keys)))
    (is (not-any? realized? (vals (:cells f))))))


(deftest computed-captures-share-within-an-invocation-but-not-across-it
  (let [root [:root []] a [:root [:a]] b [:root [:b]] source [:root [:source]]
        expressions {[root [:source]] {:op :invoke :binding-id :source :target-id :producer :type :text :scope source}
                     [a [:x]] {:op :read :source [root [:source]] :type :text}
                     [b [:x]] {:op :read :source [root [:source]] :type :text}}
        arguments {root {:source [:source]} a {:x [:x]} b {:x [:x]} source {}}
        first-frame (frame expressions arguments)
        first-keys (memo/scope-keys first-frame)
        second-keys (memo/scope-keys (frame expressions arguments))]
    (is (= (get first-keys a) (get first-keys b)))
    (is (not= (get first-keys a) (get second-keys a)))
    (is (not-any? realized? (vals (:cells first-frame))))))


(deftest an-internal-child-capture-is-local-to-the-parent-memo
  (let [a [:root [:a]] b [:root [:b]]
        ca [:root [:a :child]] cb [:root [:b :child]]
        sa [:root [:a :source]] sb [:root [:b :source]]
        f (frame {[a [:source]] {:op :invoke :binding-id :source :target-id :producer :type :text :scope sa}
                  [b [:source]] {:op :invoke :binding-id :source :target-id :producer :type :text :scope sb}
                  [a [:result]] {:op :invoke :binding-id :child :target-id :consumer :type :text :scope ca}
                  [b [:result]] {:op :invoke :binding-id :child :target-id :consumer :type :text :scope cb}
                  [ca [:x]] {:op :read :source [a [:source]] :type :text}
                  [cb [:x]] {:op :read :source [b [:source]] :type :text}}
                 {a {:x [:result]} b {:x [:result]} ca {:x [:x]} cb {:x [:x]} sa {} sb {}})
        keys (memo/scope-keys f)]
    (is (= (get keys a) (get keys b)))
    (is (not= (get keys ca) (get keys cb)))
    (is (not-any? realized? (vals (:cells f))))))


(deftest known-literal-projections-retain-value-equality
  (let [root [:root []] a [:root [:a]] b [:root [:b]]
        f (frame {[root [:source]] {:op :literal :value "same" :type :text}
                  [a [:x]] {:op :read :source [root [:source]] :type :text}
                  [b [:x]] {:op :supplied :type :text}}
                 {root {:source [:source]} a {:x [:x]} b {:x [:x]}})
        keys (memo/scope-keys (assoc-in f [:supplied [b [:x]]] "same"))]
    (is (= (get keys a) (get keys b)))))
