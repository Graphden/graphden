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


(deftest package-reload-retains-live-context-implementations
  (let [id (random-uuid)
        other-id (random-uuid)
        original (fn [_ _] :original)
        reloaded (fn [_ _] :reloaded)
        override (fn [_ _] :override)]
    (owned/record-owned-impls! {id original})
    (owned/record-owned-impls! {id reloaded})
    (is (owned/owned-impl? id original))
    (is (owned/owned-impl? id reloaded))
    (is (not (owned/owned-impl? id override)))
    (is (not (owned/owned-impl? other-id original)))))
