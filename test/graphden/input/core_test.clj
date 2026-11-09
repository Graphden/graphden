(ns graphden.input.core-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.input.core :as input]))


(def ^:private user-fields
  [{:id :id :name :id :type :uuid}
   {:id :name :name :name :type :text}
   {:id :email :name :email :type :text :required false}])


(def ^:private contract
  (input/input-contract
    {:type {:sender :user :recipient :user :options [:map :keyword :any]}
     :fields [{:id :sender :name :sender :type :user :fields user-fields}
              {:id :recipient :name :recipient :type :user :required false :fields user-fields}
              {:id :options :name :options :type [:map :keyword :any] :required false}]}))


(defn- partial-record
  [fields]
  {:kind :partial :fields fields})


(defn- literal
  [id value]
  {:kind :value :binding-id id :value value})


(defn- reference
  [id fn-id]
  {:kind :ref :binding-id id :fn-id fn-id})


(defn- error-code
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))


(deftest record-occurrences-and-scopes-have-independent-addresses-test
  (is (= {:scope :root :path [:sender :name] :type :text}
         (input/resolve-address contract :root [:sender :name])))
  (is (= {:scope :root :path [:recipient :name] :type :text}
         (input/resolve-address contract :root [:recipient :name])))
  (is (= {:scope :another-input :path [:sender :name] :type :text}
         (input/resolve-address contract :another-input [:sender :name]))))


(deftest atomic-dictionaries-cannot-be-opened-as-records-test
  (is (= :atomic-field
         (error-code #(input/resolve-address contract :root [:options :new-key]))))
  (is (= :partial-record-required
         (error-code #(input/bind-input contract {} :root
                                        (partial-record {:options (partial-record {})}))))))


(deftest independent-bindings-commute-test
  (let [deltas [(partial-record {:sender (partial-record {:name (literal :a "Alice")})})
                (partial-record {:sender (partial-record {:id (literal :b #uuid "00000000-0000-0000-0000-000000000001")})})
                (partial-record {:recipient (partial-record {:name (literal :c "Bob")})})]]
    (doseq [i (range (count deltas))
            j (range (count deltas))
            :when (not= i j)]
      (let [bind #(input/bind-input contract %1 :root %2)]
        (is (= (bind (bind {} (deltas i)) (deltas j))
               (bind (bind {} (deltas j)) (deltas i))))))))


(deftest repeated-values-and-parent-child-overlaps-are-rejected-test
  (let [name-delta (partial-record {:sender (partial-record {:name (literal :name-binding "Alice")})})
        sender-delta (partial-record {:sender (reference :sender-binding :lookup-user)})
        named (input/bind-input contract {} :root name-delta)
        whole (input/bind-input contract {} :root sender-delta)]
    (testing "An independently equal value still overlaps"
      (is (= :occupied
             (error-code #(input/bind-input contract named :root
                                            (partial-record {:sender (partial-record {:name (literal :another "Alice")})}))))))
    (testing "A whole record cannot replace a child, and a child cannot extend a whole record"
      (is (= :occupied (error-code #(input/bind-input contract named :root sender-delta))))
      (is (= :occupied (error-code #(input/bind-input contract whole :root name-delta)))))
    (testing "Replaying a user bind is not diamond deduplication"
      (is (= :binding-identity-conflict
             (error-code #(input/bind-input contract named :root name-delta)))))))


(deftest inherited-diamond-shares-only-identical-binding-origin-test
  (let [left (input/bind-input contract {} :root
                               (partial-record {:sender (reference :same-origin :lookup-user)}))
        independent (input/bind-input contract {} :root
                                      (partial-record {:sender (reference :different-origin :lookup-user)}))
        moved (input/bind-input contract {} :root
                                (partial-record {:recipient (reference :same-origin :lookup-user)}))]
    (is (= left (input/merge-bindings left left)))
    (is (= :occupied (error-code #(input/merge-bindings left independent))))
    (is (= :binding-identity-conflict (error-code #(input/merge-bindings left moved))))))


(deftest failed-batch-does-not-mutate-previous-bindings-test
  (let [before (input/bind-input contract {} :root
                                 (partial-record {:sender (partial-record {:name (literal :old "Alice")})}))
        failed (partial-record
                 (array-map :options (literal :new {})
                            :sender (partial-record {:name (literal :conflict "Bob")})))]
    (is (= :occupied (error-code #(input/bind-input contract before :root failed))))
    (is (= #{[:root [:sender :name]]} (set (keys before))))))


(deftest optional-record-activation-and-required-descendants-test
  (is (= [[:sender :id] [:sender :name]]
         (:missing (input/remaining-input contract {} :root))))
  (let [bound (input/bind-input contract {} :root
                                (partial-record {:recipient (partial-record {:name (literal :b "Bob")})}))
        remaining (input/remaining-input contract bound :root)
        recipient (get-in remaining [:descriptor :fields 1])]
    (is (= [[:sender :id] [:sender :name] [:recipient :id]] (:missing remaining)))
    (is (= {:state :partial :bindable? false} (select-keys recipient [:state :bindable?])))
    (is (= {:state :free :bindable? true :required-now? false}
           (select-keys (get-in recipient [:fields 2]) [:state :bindable? :required-now?])))))


(deftest optional-fields-remain-bindable-after-local-requirements-are-filled-test
  (let [bound (input/bind-input contract {} :root
                                (partial-record {:sender (partial-record {:id (literal :id-value :test-id)
                                                                          :name (literal :name-value "Alice")})}))
        result (input/remaining-input contract bound :root)]
    (is (= [] (:missing result)))
    (is (= {:state :free :bindable? true :required-now? false}
           (select-keys (get-in result [:descriptor :fields 0 :fields 2])
                        [:state :bindable? :required-now?])))))


(deftest nested-refs-preserve-expected-type-without-evaluation-test
  (let [callback-type [:fn {:item :int} :text #{:io} #{:jvm-server}]
        c (input/input-contract
            {:type {:config :config}
             :fields [{:id :config :name :config :type :config
                       :fields [{:id :text :name :text :type :text}
                                {:id :callback :name :callback :type callback-type}
                                {:id :identity :name :identity :type :fn-ref}]}]})
        bound (input/bind-input c {} :root
                                (partial-record {:config (partial-record
                                                           {:text (reference :text-binding :produce-text)
                                                            :callback (reference :callback-binding :produce-or-wrap-callable)
                                                            :identity (reference :identity-binding :service)})}))
        result (input/remaining-input c bound :root)]
    (is (= {:kind :ref :fn-id :produce-text} (get-in bound [[:root [:config :text]] :expression])))
    (is (= callback-type (get-in bound [[:root [:config :callback]] :expected-type])))
    (is (= :fn-ref (get-in bound [[:root [:config :identity]] :expected-type])))
    (testing "Local occupancy is not a claim that referenced graphs have no free inputs"
      (is (= [] (:missing result)))
      (is (= #{[:config :text] [:config :callback] [:config :identity]}
             (set (keys (:dependencies result))))))))


(deftest whole-values-preserve-presence-and-reserved-data-keys-test
  (doseq [value [nil false {} {:value 1 :ref :ordinary-data :fields {:a 2}}]]
    (let [bound (input/bind-input contract {} :root (partial-record {:options (literal :value-binding value)}))]
      (is (= {:kind :value :value value} (get-in bound [[:root [:options]] :expression])))
      (is (= :bound (get-in (input/remaining-input contract bound :root) [:descriptor :fields 2 :state]))))))


(deftest empty-partial-does-not-activate-an-optional-record-test
  (is (= {} (input/bind-input contract {} :root (partial-record {:recipient (partial-record {})})))))


(deftest required-record-presence-is-not-inferred-from-no-required-children-test
  (doseq [fields [[] [{:id :email :name :email :type :text :required false}]]]
    (let [c (input/input-contract {:type {:required :record :optional :record}
                                   :fields [{:id :required :name :required :type :record :fields fields}
                                            {:id :optional :name :optional :type :record
                                             :fields fields :required false}]})
          noop (input/bind-input c {} :root (partial-record {:required (partial-record {})}))
          present (input/bind-input c {} :root (partial-record {:required (literal :present {})}))]
      (is (= [[:required]] (:missing (input/remaining-input c {} :root))))
      (is (= [[:required]] (:missing (input/remaining-input c noop :root))))
      (is (= [] (:missing (input/remaining-input c present :root)))))))


(deftest projections-and-original-constraints-survive-occupancy-analysis-test
  (let [rich-type [:refine {:start :int :end :int} [:< :start :end]]
        c (input/input-contract {:type rich-type
                                 :fields [{:id :start :name :start :type :int}
                                          {:id :end :name :end :type :int}]})
        bound (input/bind-input c {} :root
                                (partial-record {:start {:kind :projection :binding-id :p
                                                         :scope :outer :path [:source-start]}}))
        remaining (input/remaining-input c bound :root)]
    (is (= rich-type (get-in remaining [:descriptor :type])))
    (is (= [[:end]] (:missing remaining)))
    (is (= {:kind :projection :scope :outer :path [:source-start]}
           (get-in remaining [:dependencies [:start] :expression])))))


(deftest distinct-scopes-do-not-collide-test
  (let [delta (partial-record {:sender (reference :first :lookup-user)})
        left (input/bind-input contract {} :one delta)
        right (input/bind-input contract {} :two delta)]
    (is (= 2 (count (input/merge-bindings left right))))
    (is (= [[:sender :id] [:sender :name]] (:missing (input/remaining-input contract left :two))))))


(deftest malformed-contracts-and-expressions-have-safe-errors-test
  (is (= :invalid-address (error-code #(input/remaining-input contract {} nil))))
  (is (= :duplicate-field
         (error-code #(input/input-contract {:type :record :fields [{:id :a :name :a :type :text}
                                                                    {:id :a :name :b :type :int}]}))))
  (is (= :invalid-expression
         (error-code #(input/bind-input contract {} :root
                                        (partial-record {:sender {:kind :value :binding-id :missing-value}})))))
  (is (= :record-input-required (error-code #(input/input-contract {:type [:map :keyword :any]})))))
