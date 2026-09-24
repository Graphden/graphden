(ns ^:integration graphden.crud.secret-path-write-test
  "A TENANT cannot store a secret binding whose vault path lies outside its
   own `org/<org-id>/` prefix, or is not in normal form — on any write
   route: the entity guard (`validation/write-rej`), the Clojure entity
   API (create + a value-only PUT), the graph HTTP handler, a bundle sync
   and the MCP `upsert-fn-defs` tool (docs/SECRETS.md § Per-org vault
   paths). The path is read later by code outside the tenant's scope (the
   tombstone GC's vault reclaim), so the write is where it is refused. The
   platform tier is unrestricted, as before."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.validation :as validation]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tctx]
    [graphden.test-infra.golden-app :as ga]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (ga/fixture (ns-name *ns*) ["core" "web" "app" "registry" "mcp"]))


(defn- storage
  []
  (:storage ga/*bootstrap*))


(defn- ctx
  []
  (:ctx ga/*bootstrap*))


(defn- base-fn-id
  [n]
  (:id (first (filter :return-type-fn-id (sp/query-entities (storage) :fn {:name n})))))


(defn- secret-slot
  "`{:fn-id :slot-id}` — a fresh platform child of `:secret-leaf` and its
   `[:secret :text]` `:in` slot: the target a secret binding is written to."
  []
  (let [n (keyword (str "spw-leaf-" (subs (str (random-uuid)) 0 8)))
        _ (pkg-sync/sync-bundle! (storage) [{:name n :namespace "spw" :parent :secret-leaf}])
        leaf (base-fn-id "secret-leaf")]
    {:fn-id (:id (first (sp/query-entities (storage) :fn {:name (name n)})))
     :slot-id (:slot-id (first (sp/query-entities (storage) :fn-slot {:fn-id leaf})))}))


(defn- secret-binding
  [target path]
  (assoc target :value path :resolver-fn-id (base-fn-id "vault-get")))


(defn- rej-type
  [org data]
  (:type (tctx/with-org org (validation/write-rej (storage) :binding data))))


(defn- thrown-type
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(deftest the-entity-guard-confines-a-tenants-secret-path
  (let [target (secret-slot)]
    (testing "another org's path is forbidden"
      (is (= :vault/path-forbidden (rej-type "acme" (secret-binding target "org/other/db")))))
    (testing "a platform path is forbidden"
      (is (= :vault/path-forbidden (rej-type "acme" (secret-binding target "stripe/api-key")))))
    (testing "a leading `/` is refused even on the tenant's own prefix — the stored form is the normal one"
      (is (= :vault/invalid-path (rej-type "acme" (secret-binding target "/org/acme/db")))))
    (testing "a malformed path is refused"
      (is (= :vault/invalid-path (rej-type "acme" (secret-binding target "org/acme/../other/db")))))
    (testing "the tenant's own normal-form path passes"
      (is (nil? (rej-type "acme" (secret-binding target "org/acme/db")))))
    (testing "the platform tier is unrestricted"
      (is (nil? (rej-type nil (secret-binding target "/org/other/db")))))))


(deftest the-entity-api-refuses-a-foreign-path-on-create-and-on-a-value-only-put
  (let [target (secret-slot)]
    (testing "create"
      (is (= :vault/path-forbidden
             (thrown-type #(tctx/with-org "acme"
                                          (entities/create-entity :binding (secret-binding target "org/other/db") (ctx)))))))
    (testing "a PUT carrying only `:value` is checked against the stored resolver"
      (let [row (tctx/with-org "acme"
                               (entities/create-entity :binding (secret-binding target "org/acme/db") (ctx)))]
        (is (= :vault/path-forbidden
               (thrown-type #(tctx/with-org "acme"
                                            (entities/update-entity :binding (:id row) {:value "org/other/db"} (ctx))))))
        (is (= "org/acme/db" (:value (sp/read-entity (storage) :binding (:id row))))
            "the row is unchanged")))))


(deftest the-http-entity-route-refuses-re-pointing-a-secret-binding
  ;; The form route carries no resolver field, so it cannot MINT a secret
  ;; binding — but a PUT of `value` re-points an existing one.
  (let [target (secret-slot)
        row (tctx/with-org "acme"
                           (entities/create-entity :binding (secret-binding target "org/acme/db") (ctx)))
        resp (tctx/with-org "acme"
                            (setup/via-graph ga/*bootstrap* :process-update-entity
                                             {:uri (str "/api/entities/binding/" (:id row))
                                              :request-method :put
                                              :headers {"content-type" "application/x-www-form-urlencoded"}
                                              ;; The form's `value` field is JSON — a string travels quoted.
                                              :body (str "value=" (java.net.URLEncoder/encode "\"org/other/db\"" "UTF-8"))}))]
    (is (not= 200 (:status resp)) (pr-str resp))
    (is (str/includes? (str (:body resp)) "outside this organization") (pr-str resp))
    (is (= "org/acme/db" (:value (sp/read-entity (storage) :binding (:id row)))))))


(defn- bundle-refusal
  [org fn-defs]
  (thrown-type #(tctx/with-org org (pkg-sync/sync-bundle! (storage) fn-defs))))


(deftest a-bundle-sync-refuses-a-foreign-secret-path
  (testing "the `{:secret-path …}` sugar"
    (is (= :vault/path-forbidden
           (bundle-refusal "acme" [{:name :spw-sugar :namespace "spw" :parent :secret-leaf
                                    :args {:in {:secret-path "org/other/db"}}}]))))
  (testing "the generic resolver form"
    (is (= :vault/path-forbidden
           (bundle-refusal "acme" [{:name :spw-res :namespace "spw" :parent :secret-leaf
                                    :args {:in {:resolver :vault-get :value "stripe/api-key"}}}]))))
  (testing "a resolver that only INHERITS from :vault-get, defined in the same bundle"
    (is (= :vault/path-forbidden
           (bundle-refusal "acme" [{:name :spw-my-get :namespace "spw" :parent :vault-get}
                                   {:name :spw-inh :namespace "spw" :parent :secret-leaf
                                    :args {:in {:resolver :spw-my-get :value "org/other/db"}}}]))))
  (testing "the tenant's own path lands"
    (is (nil? (bundle-refusal "acme" [{:name :spw-own :namespace "spw" :parent :secret-leaf
                                       :args {:in {:secret-path "org/acme/db"}}}]))))
  (testing "the platform tier is unrestricted"
    (is (nil? (bundle-refusal nil [{:name :spw-platform :namespace "spw" :parent :secret-leaf
                                    :args {:in {:secret-path "stripe/api-key"}}}])))))


(defn- call-tool!
  [tool-name args]
  (let [resp (ga/exec-handler :_mcp-dispatch
                              {:headers {"content-type" "application/json"}
                               :body (json/generate-string
                                       {:jsonrpc "2.0" :id 1 :method "tools/call"
                                        :params {:name tool-name :arguments args}})})]
    (json/parse-string (:body resp) true)))


(deftest mcp-upsert-fn-defs-refuses-a-foreign-secret-path
  (let [branch (str "ai/spw-" (subs (str (random-uuid)) 0 8))]
    (tctx/with-org "acme"
                   (call-tool! "create-branch" {:name branch})
                   (let [rpc (call-tool! "upsert-fn-defs"
                                         {:branch branch
                                          :fn-defs "[{:name :spw-mcp :parent :secret-leaf :args {:in {:secret-path \"org/other/db\"}}}]"})
                         text (str (get-in rpc [:error :message]) (-> rpc :result :content first :text))]
                     (is (str/includes? text "outside this organization") (pr-str rpc))
                     (is (empty? (sp/query-entities (vs/unwrap (storage)) :fn {:name "spw-mcp"}))
                         "no identity row on any branch")))))
