(ns graphden.executor.capabilities-test
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.test :refer [deftest is]]
    [graphden.executor.capabilities :as capabilities]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.records.ids :as ids])
  (:import
    (java.io
      File)))


(defbase native-probe [value] value)


(deftest native-facts-need-an-implementation-and-canonical-browser-identity
  (let [add-id (ids/fn-id "core.arithmetic" :add)
        user-add-id (ids/fn-id "user.arithmetic" :add)
        unsupported-id (ids/fn-id "core.hof" :reduce)]
    (is (= :unknown (capabilities/native-execution add-id nil)))
    (is (= #{:jvm-server :browser-main} (capabilities/native-execution add-id native-probe)))
    (is (= #{:jvm-server} (capabilities/native-execution user-add-id native-probe)))
    (is (= #{:jvm-server} (capabilities/native-execution unsupported-id native-probe)))
    (is (nil? (capabilities/browser-op user-add-id)))
    (is (nil? (capabilities/browser-op unsupported-id)))))


(deftest callback-stage-facts-come-from-the-browser-abi
  (let [map-id (ids/fn-id "core.hof" :map)
        const-id (ids/fn-id "core.logic" :const)]
    (is (= #{:func} (capabilities/invoked-slots map-id)))
    (is (= #{} (capabilities/invoked-slots const-id)))
    (is (capabilities/browser-callable-slot? (ids/slot-id map-id :func)))
    (is (not (capabilities/browser-callable-slot? (ids/slot-id const-id :value))))
    (is (nil? (capabilities/invoked-slots (random-uuid))))))


(deftest every-declared-browser-capability-exists-in-the-runtime-abi
  (let [plan {:format 1 :primitiveAbi capabilities/browser-primitive-abi :functions []
              :primitives (mapv (fn [id] {:id (str id) :op (capabilities/browser-op id)})
                                (capabilities/browser-primitive-ids))}
        runtime (str (File/.getCanonicalPath (io/file "resources/packages/app/ui-preview/browser-runtime.js")))
        result (sh/sh "node" "-e"
                      "const runtime = require(process.argv[1]); runtime.createRuntime(JSON.parse(process.argv[2]));"
                      runtime (json/generate-string plan))]
    ;; The two representation operations added by A3 have actual identity
    ;; implementations; the runtime must accept every declared ABI entry.
    (is (every? (set (map :op (:primitives plan))) ["as-ui-view" "encode-view"]))
    (is (zero? (:exit result)) (:err result))))
