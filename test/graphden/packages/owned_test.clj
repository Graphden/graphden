(ns graphden.packages.owned-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.packages.owned :as owned]))


(deftest trusted-implementation-requires-object-identity
  (let [id (random-uuid)
        trusted (proxy [clojure.lang.AFn clojure.lang.Fn] []
                  (invoke [_ _] :trusted)

                  (equals [_] true)

                  (hashCode [] 7))
        equal-impostor (proxy [clojure.lang.AFn clojure.lang.Fn] []
                         (invoke [_ _] :forged)

                         (equals [_] true)

                         (hashCode [] 7))]
    (is (= trusted equal-impostor))
    (owned/record-owned-impls! {id trusted})
    (is (owned/owned-impl? id trusted))
    (is (not (owned/owned-impl? id equal-impostor)))))
