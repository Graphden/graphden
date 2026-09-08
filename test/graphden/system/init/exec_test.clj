(ns graphden.system.init.exec-test
  "The auth verdict is logged where the wired provider is known —
   `:exec/context` — not by `:auth/provider`, which cannot see an
   addon's provider and used to shout \"auth is OFF\" on every
   production boot of an instance that answers 401 to everything."
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging.test :refer [logged? with-log]]
    [graphden.system.init.exec :as init-exec]
    [graphden.system.init.storage :as init-storage]))


(deftest warn-if-auth-off-logs-only-without-a-provider
  (testing "no provider → the SECURITY warning, provider returned as-is"
    (with-log
      (is (nil? (init-exec/warn-if-auth-off! nil)))
      (is (logged? 'graphden.system.init.exec :warn #"SECURITY: auth is OFF"))))
  (testing "a wired provider (core's or an addon's) → silence"
    (with-log
      (let [p {:kind :addon}]
        (is (= p (init-exec/warn-if-auth-off! p)))
        (is (not (logged? 'graphden.system.init.exec :warn #"SECURITY")))))))


(deftest warn-if-relay-open-logs-only-without-a-provider
  (testing "a relay with no provider is OPEN — say so"
    (with-log
      (is (nil? (init-storage/warn-if-relay-open! 8081 nil)))
      (is (logged? 'graphden.system.init.storage :warn #"SECURITY: the SSE invalidation relay"))))
  (testing "a provider → silence"
    (with-log
      (is (= {:p 1} (init-storage/warn-if-relay-open! 8081 {:p 1})))
      (is (not (logged? 'graphden.system.init.storage :warn #"SECURITY"))))))
