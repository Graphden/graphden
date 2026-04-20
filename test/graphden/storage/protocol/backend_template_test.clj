(ns ^:integration graphden.storage.protocol.backend-template-test
  "Tests for backend-template helper functions."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.backend-template :as tpl]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]))


;; Container for PostgreSQL tests
(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- create-test-storage
  "Creates a PostgreSQL storage from the current test container. Relies
   on the :each fixture to have cleaned the schema already."
  []
  (pg/create-storage (th/get-container-config *container*)))


;; === wrap-crud-operation tests ===

(deftest wrap-crud-operation-test
  (testing "returns result of successful operation"
    (is (= {:id 1 :name "test"}
           (tpl/wrap-crud-operation :create-entity
                                    {:entity-name :user}
                                    #(do {:id 1 :name "test"})))))

  (testing "wraps exception with context"
    (try
      (tpl/wrap-crud-operation :update-entity
                               {:entity-name :user :id 123}
                               #(throw (ex-info "Database error" {:cause :timeout})))
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :storage-error (:type (ex-data e))))
        (is (= :update-entity (:operation (ex-data e))))
        (is (= :user (:entity-name (ex-data e))))
        (is (= 123 (:id (ex-data e))))
        (is (some? (ex-cause e))))))

  (testing "handles nil result from operation"
    (is (nil? (tpl/wrap-crud-operation :read-entity
                                       {:entity-name :user}
                                       #(identity nil))))))


;; === create-rw-lock tests ===

(deftest create-rw-lock-test
  (testing "creates ReentrantReadWriteLock"
    (let [lock (tpl/create-rw-lock)]
      (is (instance? java.util.concurrent.locks.ReentrantReadWriteLock lock))))

  (testing "lock can be used for read operations"
    (let [lock (tpl/create-rw-lock)]
      (is (= 42 (sp/with-read-lock lock #(+ 40 2))))))

  (testing "lock can be used for write operations"
    (let [lock (tpl/create-rw-lock)]
      (is (= "written" (sp/with-write-lock lock #(identity "written")))))))


;; === validate-config! tests ===

(deftest validate-config!-test
  (testing "passes for valid config with all required keys"
    (is (nil? (tpl/validate-config! {:host "localhost" :port 6379}
                                    #{:host :port}
                                    {}))))

  (testing "throws for missing required key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing required config key: :port"
          (tpl/validate-config! {:host "localhost"}
                                #{:host :port}
                                {}))))

  (testing "passes validator function"
    (is (nil? (tpl/validate-config! {:port 6379}
                                    #{:port}
                                    {:port #(and (integer? %) (pos? %))}))))

  (testing "throws when validator fails"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid config value for: :port"
          (tpl/validate-config! {:port -1}
                                #{:port}
                                {:port pos?}))))

  (testing "validator only runs on present keys"
    (is (nil? (tpl/validate-config! {:host "localhost"}
                                    #{:host}
                                    {:port pos?}))))  ; port not required, not present

  (testing "error includes redacted config"
    (try
      (tpl/validate-config! {:host "localhost" :password "secret123"}
                            #{:host :port}
                            {})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-config (:type (ex-data e))))
        (is (= :port (:missing-key (ex-data e))))
        ;; Password should be redacted
        (is (= "[REDACTED]" (get-in (ex-data e) [:config :password]))))))

  (testing "passes with multiple required keys all present"
    ;; Exercises full doseq iteration over required-keys without short-circuit
    (is (nil? (tpl/validate-config! {:host "localhost" :port 6379 :db 0}
                                    #{:host :port :db}
                                    {}))))

  (testing "passes with multiple validators all passing"
    ;; Exercises full doseq iteration over validators without short-circuit
    (is (nil? (tpl/validate-config! {:host "localhost" :port 6379 :db 0}
                                    #{:host :port :db}
                                    {:port pos?
                                     :db #(>= % 0)
                                     :host string?}))))

  (testing "throws when second validator fails"
    ;; Exercises validator doseq with mixed pass/fail across multiple entries
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid config value for:"
          (tpl/validate-config! {:host "localhost" :port -1 :db 0}
                                #{:host :port :db}
                                {:host string?
                                 :port pos?
                                 :db #(>= % 0)}))))

  (testing "error from failed validator includes redacted config"
    (try
      (tpl/validate-config! {:host "localhost" :port -1 :password "secret"}
                            #{:host :port}
                            {:port pos?})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-config (:type (ex-data e))))
        (is (= :port (:key (ex-data e))))
        (is (= "[REDACTED]" (get-in (ex-data e) [:config :password])))))))


;; === kebab->snake tests ===

(deftest kebab->snake-test
  (testing "converts single hyphen"
    (is (= "user_name" (tpl/kebab->snake :user-name))))

  (testing "converts multiple hyphens"
    (is (= "first_middle_last" (tpl/kebab->snake :first-middle-last))))

  (testing "handles no hyphens"
    (is (= "name" (tpl/kebab->snake :name))))

  (testing "handles string input"
    (is (= "foo_bar" (tpl/kebab->snake "foo-bar")))))


;; === snake->kebab tests ===

(deftest snake->kebab-test
  (testing "converts single underscore"
    (is (= :user-name (tpl/snake->kebab "user_name"))))

  (testing "converts multiple underscores"
    (is (= :first-middle-last (tpl/snake->kebab "first_middle_last"))))

  (testing "handles no underscores"
    (is (= :name (tpl/snake->kebab "name")))))


;; === convert-keys tests ===

(deftest convert-keys-test
  (testing "converts all keys with function"
    (is (= {:user-name "John" :user-age 30}
           (tpl/convert-keys {"user_name" "John" "user_age" 30}
                             tpl/snake->kebab))))

  (testing "handles empty map"
    (is (= {} (tpl/convert-keys {} tpl/snake->kebab))))

  (testing "works with identity function"
    (is (= {:a 1 :b 2}
           (tpl/convert-keys {:a 1 :b 2} identity)))))


;; === infer-type-category tests ===

(deftest infer-type-category-test
  (testing "infers string type"
    (is (= :text (tpl/infer-type-category "hello"))))

  (testing "infers integer type"
    (is (= :int (tpl/infer-type-category 42)))
    (is (= :int (tpl/infer-type-category (long 42)))))

  (testing "infers float/numeric type"
    (is (= :numeric (tpl/infer-type-category 3.14)))
    (is (= :numeric (tpl/infer-type-category (float 3.14)))))

  (testing "infers boolean type"
    (is (= :bool (tpl/infer-type-category true)))
    (is (= :bool (tpl/infer-type-category false))))

  (testing "infers UUID type"
    (is (= :uuid (tpl/infer-type-category (random-uuid)))))

  (testing "infers keyword/enum type"
    (is (= :enum (tpl/infer-type-category :active))))

  (testing "infers map/jsonb type"
    (is (= :jsonb (tpl/infer-type-category {:a 1}))))

  (testing "infers vector/jsonb type"
    (is (= :jsonb (tpl/infer-type-category [1 2 3]))))

  (testing "infers bytes type"
    (is (= :bytes (tpl/infer-type-category (byte-array [1 2 3])))))

  (testing "infers instant/timestamptz type"
    (is (= :timestamptz (tpl/infer-type-category (java.util.Date.))))
    (is (= :timestamptz (tpl/infer-type-category (java.time.Instant/now)))))

  (testing "returns :unknown for nil"
    (is (= :unknown (tpl/infer-type-category nil))))

  (testing "returns :unknown for unrecognized types"
    (is (= :unknown (tpl/infer-type-category (Object.))))))


;; === clojure-type->category tests ===

(deftest clojure-type->category-test
  (testing "contains expected type mappings"
    (is (= :text (get tpl/clojure-type->category String)))
    (is (= :int (get tpl/clojure-type->category Long)))
    (is (= :int (get tpl/clojure-type->category Integer)))
    (is (= :numeric (get tpl/clojure-type->category Double)))
    (is (= :bool (get tpl/clojure-type->category Boolean)))
    (is (= :uuid (get tpl/clojure-type->category java.util.UUID)))
    (is (= :timestamptz (get tpl/clojure-type->category java.util.Date)))
    (is (= :enum (get tpl/clojure-type->category clojure.lang.Keyword)))
    (is (= :jsonb (get tpl/clojure-type->category clojure.lang.IPersistentMap)))
    (is (= :jsonb (get tpl/clojure-type->category clojure.lang.IPersistentVector)))
    (is (= :bytes (get tpl/clojure-type->category (Class/forName "[B"))))))


;; === classify-error tests ===

(deftest classify-error-test
  (testing "classifies error using provided classifiers"
    (let [classifiers {:unique-violation #(= "unique" (ex-message %))
                       :foreign-key-violation #(= "fk" (ex-message %))}]
      (is (= :unique-violation
             (tpl/classify-error (ex-info "unique" {}) classifiers)))
      (is (= :foreign-key-violation
             (tpl/classify-error (ex-info "fk" {}) classifiers)))))

  (testing "returns :unknown-error when no classifier matches"
    (let [classifiers {:unique-violation #(= "unique" (ex-message %))}]
      (is (= :unknown-error
             (tpl/classify-error (ex-info "other" {}) classifiers)))))

  (testing "handles empty classifiers"
    (is (= :unknown-error
           (tpl/classify-error (ex-info "error" {}) {})))))


;; === make-storage-exception tests ===

(deftest make-storage-exception-test
  (testing "creates exception with type and context"
    (let [ex (tpl/make-storage-exception :unique-violation
                                         "Duplicate key"
                                         {:entity-name :user :id 123})]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= "Duplicate key" (ex-message ex)))
      (is (= :unique-violation (:type (ex-data ex))))
      (is (= :user (:entity-name (ex-data ex))))
      (is (= 123 (:id (ex-data ex))))
      (is (nil? (ex-cause ex)))))

  (testing "creates exception with cause"
    (let [cause (ex-info "Original error" {})
          ex (tpl/make-storage-exception :connection-error
                                         "Connection failed"
                                         {:host "localhost"}
                                         cause)]
      (is (= "Connection failed" (ex-message ex)))
      (is (= :connection-error (:type (ex-data ex))))
      (is (= cause (ex-cause ex))))))


;; === compute-schema-diff tests ===

(deftest compute-schema-diff-test
  (testing "identifies added entities"
    (let [old-schema {:user {}}
          new-schema {:user {} :order {}}
          diff (tpl/compute-schema-diff old-schema new-schema)]
      (is (= #{:order} (:added diff)))
      (is (= #{} (:removed diff)))
      (is (= #{:user} (:modified diff)))))

  (testing "identifies removed entities"
    (let [old-schema {:user {} :order {}}
          new-schema {:user {}}
          diff (tpl/compute-schema-diff old-schema new-schema)]
      (is (= #{} (:added diff)))
      (is (= #{:order} (:removed diff)))
      (is (= #{:user} (:modified diff)))))

  (testing "identifies modified entities (present in both)"
    (let [old-schema {:user {:name {:type :text}}}
          new-schema {:user {:name {:type :text} :email {:type :text}}}
          diff (tpl/compute-schema-diff old-schema new-schema)]
      (is (= #{} (:added diff)))
      (is (= #{} (:removed diff)))
      (is (= #{:user} (:modified diff)))))

  (testing "handles empty schemas"
    (let [diff (tpl/compute-schema-diff {} {})]
      (is (= #{} (:added diff)))
      (is (= #{} (:removed diff)))
      (is (= #{} (:modified diff)))))

  (testing "handles completely new schema"
    (let [diff (tpl/compute-schema-diff {} {:user {} :order {}})]
      (is (= #{:user :order} (:added diff)))
      (is (= #{} (:removed diff)))
      (is (= #{} (:modified diff))))))


;; === create-entity-with-validation tests ===

(deftest create-entity-with-validation-test
  (testing "calls create-fn with entity data"
    (let [created-data (atom nil)
          schema-atom (atom nil)
          create-fn (fn [entity-name data _fields]
                      (reset! created-data {:entity entity-name :data data})
                      {:id 1 :name "test"})]
      (is (= {:id 1 :name "test"}
             (tpl/create-entity-with-validation schema-atom :user {:name "test"} create-fn)))
      (is (= {:entity :user :data {:name "test"}} @created-data))))

  (testing "throws for non-map data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (tpl/create-entity-with-validation (atom nil) :user "not a map"
                                             (fn [_ _ _] nil)))))

  (testing "passes nil fields when schema is non-nil"
    ;; Exercises the (when schema ...) branch with a non-nil schema-atom.
    ;; The current implementation always returns nil for fields,
    ;; but the branch is entered when schema is truthy.
    (let [schema-atom (atom {:some "schema"})
          received-fields (atom :not-called)
          create-fn (fn [_entity-name _data fields]
                      (reset! received-fields fields)
                      {:id 2})]
      (is (= {:id 2}
             (tpl/create-entity-with-validation schema-atom :user {:name "test"} create-fn)))
      ;; Fields is still nil because the when body returns nil
      (is (nil? @received-fields)))))


;; === run-basic-contract-tests tests ===

(deftest run-basic-contract-tests-test
  (testing "runs basic tests on storage"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity
                       :test-entity
                       (random-uuid)
                       {:name {:uuid (random-uuid) :type :text}})
                     ds/build)
          result (tpl/run-basic-contract-tests storage schema)]
      (is (map? result))
      (is (contains? result :passed))
      (is (contains? result :failed))
      (is (contains? result :errors))
      (is (>= (:passed result) 1))
      (is (zero? (count (:errors result))))))

  (testing "catches errors from invalid storage"
    (let [storage (reify
                    sp/Storage
                    (initialize [_ _] (throw (ex-info "Init failed" {})))

                    (close [_] nil)


                    sp/StorageIntrospection

                    (schema-metadata [_] nil)

                    (current-entities [_] #{})

                    (current-fields [_ _] {})

                    (current-enums [_] #{})

                    (current-enum-values [_ _] #{}))
          schema {}
          result (tpl/run-basic-contract-tests storage schema)]
      (is (>= (count (:errors result)) 1))))

  (testing "fails when schema-metadata returns nil"
    ;; This tests the branch where initialize succeeds but schema-metadata is nil
    (let [storage (reify
                    sp/Storage
                    (initialize [this _] this)  ; succeed initialization

                    (close [_] nil)


                    sp/StorageIntrospection

                    (schema-metadata [_] nil)  ; return nil - triggers failure branch

                    (current-entities [_] #{})

                    (current-fields [_ _] {})

                    (current-enums [_] #{})

                    (current-enum-values [_ _] #{}))
          schema {}
          result (tpl/run-basic-contract-tests storage schema)]
      ;; Should have 1 pass (for initialize) and 1 fail (for schema-metadata check)
      (is (= 1 (:passed result)))
      (is (= 1 (:failed result)))
      (is (zero? (count (:errors result)))))))
