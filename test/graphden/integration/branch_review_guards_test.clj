(ns ^:integration graphden.integration.branch-review-guards-test
  "Audit-3 guard tests for the review feature's integrity, kept in a SMALL
   dedicated ns rather than piled onto the already-heavy
   `branches-lifecycle-test` (whose per-run branch churn already sits near
   the shared-pool/parallel flake threshold — adding these would tip it).

   Two guards:
   1. a merge REJECTED by the review gate must NOT clear the source's
      review-state (the negative of `_merge-clear-review-state`);
   2. the generic `POST /api/entities/:type` route cannot FORGE
      `:branch-approval` / `:branch-comment` rows — the review gate's
      integrity currently rests on the per-type parser omitting these
      types (audit-3 security P3); this pins that boundary."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.test-infra.shared-bootstrap :as sb]))


(def ^:dynamic *router* nil)
(def ^:private test-auth-token "branch-review-guards-token")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types
  (fn [t]
    (exec/with-clean-registry
      ;; pool-size 6 (vs default 2) + inline heal: merges spawn a
      ;; post-commit thread and can trigger a graph-epoch-heal, so several
      ;; threads contend the pool; the extra connections + synchronous heal
      ;; keep this ns off the shared-pool break the heavier
      ;; branches-lifecycle-test occasionally hits.
      #(let [storage (setup/create-versioned-test-storage 6)
             _ (sb/bootstrap-with-cached-sweep! storage ["core" "web" "app"])
             ctx (exec/create-context
                   {:storage storage
                    :auth-provider (auth/single-token-provider test-auth-token)})
             _ (cr/rebuild! ctx)
             router (br/create-router ctx "_app-ring-response")]
         (br/set-active-router! router)
         (try
           (binding [*router* router
                     br/*epoch-heal-sync?* true]
             (t))
           (finally
             (br/clear-active-router!)
             (sp/close storage)))))))


(defn- dispatch
  [{:keys [method path branch body content-type]
    :or {content-type "application/json"}}]
  (br/dispatch
    *router*
    {:request-method method
     :uri path
     :headers (cond-> {"authorization" (str "Bearer " test-auth-token)}
                branch (assoc "x-graphden-branch" branch)
                body (assoc "content-type" content-type))
     :body (when body (if (string? body) body (json/generate-string body)))}))


(defn- parse-json
  [resp]
  (let [b (:body resp)] (when (string? b) (json/parse-string b true))))


(deftest branch-review-blocked-merge-keeps-proposal-marker-test
  ;; The NEGATIVE of merge-clears-review-state: a merge REJECTED by the
  ;; review gate must NOT clear review-state — the clear is stage-2b of the
  ;; merge :do, after the commit that throws, so a blocked merge never
  ;; reaches it. Guards against a refactor moving the clear before the gate.
  (let [run-id (str "-" (System/currentTimeMillis))
        tgt (str "bm-tgt" run-id)
        src (str "bm-src" run-id)]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name tgt :base-branch-id "main"}}))))
    (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" tgt "/review-policy")
                                   :body {:required-approvals 1}}))))
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name src :base-branch-id tgt}}))))
    (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" src "/propose")
                                   :body {:proposed true}}))))
    (testing "merge blocked (0/1 approvals) → 409"
      (is (= 409 (:status (dispatch {:method :post :path (str "/api/branches/" tgt "/merge")
                                     :body {:source src}})))))
    (testing "the blocked merge left the proposal marker intact"
      (is (= "proposed"
             (:review-state (:branch (parse-json (dispatch {:method :get
                                                            :path (str "/api/branches/" src)})))))
          "review-state must stay 'proposed' when the merge was rejected"))))


(deftest generic-entity-route-cannot-forge-review-rows-test
  ;; audit-3 security P3 — the review gate's integrity against FORGED
  ;; :branch-approval / :branch-comment rows via the generic
  ;; POST /api/entities/:type route rests on the per-type parser
  ;; (`_create-entity-data-by-type`) returning nil for these types (no
  ;; allow-list clause). This pins that boundary: a forge attempt through
  ;; the generic route must create NO usable row, so a future parser clause
  ;; that passed raw form-data through would flip this test red before it
  ;; became a P1 review-gate bypass.
  (let [run-id (str "-" (System/currentTimeMillis))
        tgt (str "forge-tgt" run-id)
        src (str "forge-src" run-id)]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name tgt :base-branch-id "main"}}))))
    (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" tgt "/review-policy")
                                   :body {:required-approvals 1}}))))
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name src :base-branch-id tgt}}))))
    (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" src "/propose")
                                   :body {:proposed true}}))))
    (testing "forging a :branch-approval via the generic entity route creates no row"
      (dispatch {:method :post :path "/api/entities/branch-approval"
                 :content-type "application/x-www-form-urlencoded"
                 :body (str "source-branch-id=" src "&approver-id=attacker&content-stamp=forged")})
      (let [st (parse-json (dispatch {:method :get :path (str "/api/branches/" src "/approvals")}))]
        (is (empty? (:approvers st)) "no approval row exists after the generic-route forge")
        (is (zero? (:have st)))
        (is (false? (:satisfied st)))))
    (testing "and the merge stays blocked — the forge bought nothing"
      (is (= 409 (:status (dispatch {:method :post :path (str "/api/branches/" tgt "/merge")
                                     :body {:source src}})))))
    (testing "forging a :branch-comment via the generic route lands no comment"
      (dispatch {:method :post :path "/api/entities/branch-comment"
                 :content-type "application/x-www-form-urlencoded"
                 :body (str "source-branch-id=" src "&author-id=attacker&body=forged")})
      (let [cmts (:comments (parse-json (dispatch {:method :get
                                                   :path (str "/api/branches/" src "/comments")})))]
        (is (empty? cmts) "no comment row exists after the generic-route forge")))))
