(ns graphden.tenancy.source-replay-policy-test
  "Replay eligibility never substitutes for the current source policy."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.tenancy.context :as context]
    [graphden.test-infra.seams :as seams]))


(use-fixtures :each seams/isolated-seams-fixture)


(def adapter {:authority-fields [:id :org-id :namespace-id :concealed]})


(deftest registration-is-readable-before-factory-but-does-not-seal-another-generation
  (let [called (atom 0)]
    (context/install-browser-source-policy!
      (fn [_] (swap! called inc) (constantly :current)) adapter)
    (let [before (context/source-replay-registration)
          _ (is (zero? @called))
          policy (context/browser-source-policy :fresh-snapshot)]
      (is (identical? before (context/source-replay-policy-info policy)))
      (is (= 1 @called))
      (context/install-browser-source-policy! (constantly (constantly :replacement)) adapter)
      (is (not (identical? before (context/source-replay-registration))))
      (is (nil? (context/source-replay-policy-info policy))))))


(deftest unknown-factory-cannot-borrow-an-old-adapter
  (context/install-org-cap-fn! nil)
  (context/install-browser-source-policy! nil)
  (let [unscoped (context/browser-source-policy nil)]
    (is (= [] (:authority-fields (context/source-replay-policy-info unscoped))))
    (context/install-browser-source-policy! (constantly unscoped))
    (let [unknown (context/browser-source-policy nil)]
      (is (nil? (unknown {:id :probe})))
      (is (nil? (context/source-replay-policy-info unknown)))))
  (context/install-browser-source-policy! nil)
  (context/install-org-cap-fn! (constantly false))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Browser source is unavailable"
        (context/browser-source-policy nil))))


(deftest registered-policies-still-authorize-every-call
  (let [calls (atom [])
        factory (fn [storage]
                  (fn [row & [permissions]]
                    (swap! calls conj [storage row permissions])
                    (when (:concealed row)
                      (throw (ex-info "Denied" {:type :authz/forbidden})))
                    :allowed))]
    (context/install-browser-source-policy! factory adapter)
    (let [policy (context/browser-source-policy :snapshot)
          info (context/source-replay-policy-info policy)]
      (is (= (:authority-fields adapter) (:authority-fields info)))
      (is (nil? (context/source-replay-policy-info (with-meta {} (meta policy)))))
      (is (= :allowed (policy {:id 1})))
      (is (= :allowed (apply policy [{:id 1} {:execute? true}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Denied"
            (policy {:id 1 :concealed true})))
      (is (= [[:snapshot {:id 1} nil]
              [:snapshot {:id 1} {:execute? true}]
              [:snapshot {:id 1 :concealed true} nil]] @calls))
      (context/install-browser-source-policy! factory adapter)
      (is (nil? (context/source-replay-policy-info policy)))
      (is (not (identical? (:generation info)
                           (:generation (context/source-replay-policy-info
                                          (context/browser-source-policy :snapshot))))))
      (context/install-browser-source-policy! nil)
      (is (nil? (context/source-replay-policy-info policy))))))


(deftest factory-and-adapter-are-captured-together
  (let [replacement (constantly (constantly :new))
        previous (fn [_]
                   (context/install-browser-source-policy! replacement adapter)
                   (constantly :old))]
    (context/install-browser-source-policy! previous adapter)
    (let [policy (context/browser-source-policy nil)]
      (is (= :old (policy {})))
      (is (nil? (context/source-replay-policy-info policy)))
      (is (= :new ((context/browser-source-policy nil) {}))))))


(deftest adapter-cannot-declare-source-payload-as-authority-metadata
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid browser source replay adapter"
        (context/install-browser-source-policy!
          (constantly (constantly nil)) {:authority-fields [:id :constraint]}))))


(deftest copied-current-metadata-does-not-certify-another-function
  (context/install-browser-source-policy!
    (constantly (fn [_] (throw (ex-info "Denied" {})))) adapter)
  (let [policy (context/browser-source-policy nil)
        info (context/source-replay-policy-info policy)
        copied (with-meta (constantly :bypass) (meta policy))
        modified (with-meta policy
                   (into {} (map (fn [[key _]] [key (assoc info :authority-fields [])]))
                         (meta policy)))]
    (is (= :bypass (copied {})))
    (is (nil? (context/source-replay-policy-info copied)))
    (is (nil? (context/source-replay-policy-info modified)))
    (is (identical? info (context/source-replay-policy-info policy))
        "Eligibility returns the registered adapter, not caller metadata")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Denied" (policy {})))))


(deftest default-policy-does-not-revive-after-registration-transitions
  (context/install-org-cap-fn! nil)
  (context/install-browser-source-policy! nil)
  (doseq [transition [(fn [] (context/install-browser-source-policy! nil))
                      (fn []
                        (context/install-browser-source-policy! (constantly (constantly nil)))
                        (context/install-browser-source-policy! nil))
                      (fn [] (context/install-org-cap-fn! nil))
                      (fn []
                        (context/install-org-cap-fn! (constantly false))
                        (context/install-org-cap-fn! nil))]]
    (let [old-policy (context/browser-source-policy nil)
          old-info (context/source-replay-policy-info old-policy)]
      (is (= [] (:authority-fields old-info)))
      (transition)
      (is (nil? (context/source-replay-policy-info old-policy)))
      (let [fresh (context/browser-source-policy nil)]
        (is (= [] (:authority-fields (context/source-replay-policy-info fresh)))))
      (is (nil? (old-policy {})) "Old callback behavior is unchanged; only eligibility expires"))))
