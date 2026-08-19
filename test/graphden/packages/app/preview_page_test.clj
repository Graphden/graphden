(ns graphden.packages.app.preview-page-test
  "The interactive component preview — `GET /preview` (devcards L2).
   Drives the production `:_pv-handler` gate cascade through the
   via-graph harness: tenancy 403 (self-host-only), bad/unknown
   fn-id, the non-component rejection, the effect-confirm page
   (fail-closed), and the live render with runtime scripts + htmx."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.test-infra.graph-harness :as h]))


(use-fixtures :once
  ;; page shells render `?v=<build-hash>` asset URLs — the json resource
  ;; is build-generated and absent on a fresh checkout / CI node.
  setup/ensure-build-hashes-fixture
  (h/graph-fixture (str (ns-name *ns*))))


(defn- preview-req
  [query]
  {:uri "/preview" :request-method :get :query-string query :headers {}})


(defn- fn-id-of
  [nm]
  (get (:all-name->id h/*graph*) nm))


(deftest preview-gate-cascade-test
  (testing "malformed fn-id → 400 (a MISSING one serves the gallery — see components-gallery-test)"
    (let [r (h/via :_pv-handler (preview-req "fn-id=zzz"))]
      (is (= 400 (:status r)))
      (is (str/includes? (str (:body r)) "Missing or malformed fn-id"))))

  (testing "unknown fn-id → 404"
    (let [r (h/via :_pv-handler (preview-req (str "fn-id=" (random-uuid))))]
      (is (= 404 (:status r)))
      (is (str/includes? (str (:body r)) "Function not found"))))

  (testing "a non-component fn → 400"
    (let [r (h/via :_pv-handler (preview-req (str "fn-id=" (fn-id-of :add))))]
      (is (= 400 (:status r)))
      (is (str/includes? (str (:body r)) "Not a component"))))

  (testing "an active tenancy addon → 403, before anything else runs"
    (binding [tc/*current-capabilities* ["write" "execute"]]
      (let [r (h/via :_pv-handler (preview-req nil))]
        (is (= 403 (:status r)))
        (is (str/includes? (str (:body r)) "self-host only"))))))


(deftest preview-renders-pure-component-test
  (let [_ (setup/sync-and-invalidate!
            (:ctx h/*graph*) (:storage h/*graph*)
            ;; :return-type declared explicitly: prod records a COMPUTED
            ;; return for wrap-element children via the full type-check
            ;; sweep (verified live — wrap-style previews), but the test
            ;; harness's single check-fn-def! pass under-records it.
            [{:name :pv-comp
              :parent :wrap-element
              :return-type :hiccup-node
              :args {:tag {:value "div"}
                     :content {:value "hello-live-preview"}}}])
        row-id (:id (first (sp/query-entities
                             (:storage h/*graph*) :fn {:name "pv-comp"})))
        r (h/via :_pv-handler (preview-req (str "fn-id=" row-id)))]
    (testing "renders a full page with the component live"
      (is (= 200 (:status r)))
      (let [body (str (:body r))]
        (is (str/includes? body "hello-live-preview"))
        (is (str/includes? body "Interactive preview"))
        (is (str/includes? body "<title>Preview :pv-comp</title>"))
        (is (str/includes? body "/assets/htmx.min.js")
            "htmx is live in the page head")
        (is (str/includes? body "/assets/htmx-ext-sse.min.js")
            "the SSE extension ships too — components may carry sse-connect")
        (is (str/includes? body "/assets/graphden-runtime.js")
            "the runtime dispatcher ships with the page")))))


(deftest component-preview-pane-carries-open-link-test
  ;; The static pane's caption links to the interactive preview (new
  ;; tab, noopener). Single-tenant here — the link is visible; under a
  ;; tenancy addon `:_er-cp-affordance` hides it (mirrored by the
  ;; route's own 403, covered above).
  (let [_ (setup/sync-and-invalidate!
            (:ctx h/*graph*) (:storage h/*graph*)
            [{:name :pv-link-comp
              :parent :wrap-element
              :return-type :hiccup-node
              :args {:tag {:value "div"}
                     :content {:value "x"}}}])
        row-id (:id (first (sp/query-entities
                             (:storage h/*graph*) :fn {:name "pv-link-comp"})))
        f (h/exec-name :_er-succeeded-body
                       {:exec {:status "succeeded"
                               :result ["div" {} "x"]
                               :fn-id (str row-id)}})
        flat (tree-seq coll? seq f)]
    (is (some #(and (string? %) (str/starts-with? % "/preview?fn-id=")) flat)
        "caption carries the /preview link")
    (is (some #(= "_blank" %) flat) "opens in a new tab")
    (is (some #(= "noopener" %) flat) "without window.opener")))


(deftest components-gallery-test
  (let [_ (setup/sync-and-invalidate!
            (:ctx h/*graph*) (:storage h/*graph*)
            ;; declared :return-type / :effects — harness convention
            ;; (prod's sweep computes them; see the notes above).
            [{:name :pvg-live
              :parent :wrap-element
              :return-type :hiccup-node
              :args {:tag {:value "div"}
                     :content {:value "gallery-live-render"}}}
             {:name :_pvg-priv-comp
              :parent :wrap-element
              :return-type :hiccup-node
              :args {:tag {:value "div"}
                     :content {:value "pvg-private-body"}}}
             {:name :pvg-eff
              :parent :wrap-element
              :return-type :hiccup-node
              :effects #{:env}
              :args {:tag {:value "div"}
                     :content {:parent :coalesce
                               :args {:value {:parent :env
                                              :args {:name {:value "GRAPHDEN_PVG_UNSET"}}}
                                      :default {:value "pvg-eff-rendered"}}}}}])
        r (h/via :_pv-handler (preview-req nil))
        body (str (:body r))]
    (testing "no fn-id → the gallery page"
      (is (= 200 (:status r)))
      (is (str/includes? body "Components gallery")))
    (testing "a pure zero-arg component renders LIVE in its card"
      (is (str/includes? body "gallery-live-render"))
      (is (str/includes? body "pvg-live")))
    (testing "an effectful component is listed but NOT executed"
      (is (str/includes? body "pvg-eff"))
      (is (not (str/includes? body "pvg-eff-rendered")))
      (is (str/includes? body "has side effects")))
    (testing "cards link to their single-fn preview"
      (is (str/includes? body "/preview?fn-id=")))
    (testing "_-private components hidden by default, shown with ?all=1"
      (is (not (str/includes? body "_pvg-priv-comp")))
      (let [all-body (str (:body (h/via :_pv-handler (preview-req "all=1"))))]
        (is (str/includes? all-body "_pvg-priv-comp"))))
    (testing "malformed fn-id still 400s (raw-param distinction)"
      (is (= 400 (:status (h/via :_pv-handler (preview-req "fn-id=not-a-uuid"))))))))


(deftest preview-effect-confirm-test
  (let [_ (setup/sync-and-invalidate!
            (:ctx h/*graph*) (:storage h/*graph*)
            ;; :effects declared explicitly — prod computes the
            ;; transitive set in the sync sweep; the harness's per-def
            ;; pass takes the declaration (same note as :return-type).
            [{:name :pv-eff-comp
              :parent :wrap-element
              :return-type :hiccup-node
              :effects #{:env}
              :args {:tag {:value "div"}
                     :content {:parent :coalesce
                               :args {:value {:parent :env
                                              :args {:name {:value "GRAPHDEN_PV_TEST_UNSET"}}}
                                      :default {:value "eff-rendered"}}}}}])
        row-id (:id (first (sp/query-entities
                             (:storage h/*graph*) :fn {:name "pv-eff-comp"})))]
    (testing "an effectful component without confirm → the confirm page, nothing rendered"
      (let [r (h/via :_pv-handler (preview-req (str "fn-id=" row-id)))
            body (str (:body r))]
        (is (= 200 (:status r)))
        (is (str/includes? body "declares side effects"))
        (is (str/includes? body "Render anyway"))
        (is (str/includes? body (str "/preview?fn-id=" row-id "&amp;effects=confirm"))
            "the confirm link round-trips the fn-id (& escaped by the renderer)")
        (is (not (str/includes? body "eff-rendered"))
            "the component did NOT execute")))
    (testing "with effects=confirm the component renders"
      (let [r (h/via :_pv-handler
                     (preview-req (str "fn-id=" row-id "&effects=confirm")))
            body (str (:body r))]
        (is (= 200 (:status r)))
        (is (str/includes? body "eff-rendered"))))))


(deftest component-preview-pane-cloud-button-test
  ;; Under a tenancy addon the caption swaps the direct link for the
  ;; capsule-minting BUTTON (editor-execute.js POSTs /api/preview-token
  ;; on click — an addon route; the apps-domain URL can't be static).
  (let [_ (setup/sync-and-invalidate!
            (:ctx h/*graph*) (:storage h/*graph*)
            [{:name :pv-cloud-comp
              :parent :wrap-element
              :return-type :hiccup-node
              :args {:tag {:value "div"}
                     :content {:value "y"}}}])
        row-id (:id (first (sp/query-entities
                             (:storage h/*graph*) :fn {:name "pv-cloud-comp"})))
        f (binding [tc/*current-capabilities* ["write" "execute"]]
            (h/exec-name :_er-succeeded-body
                         {:exec {:status "succeeded"
                                 :result ["div" {} "y"]
                                 :fn-id (str row-id)}}))
        flat (tree-seq coll? seq f)]
    (is (some #(= "execute-result-open-preview" %) flat)
        "the affordance is present")
    (is (some #(= (str row-id) %) flat)
        "the button carries the fn-id for the mint POST")
    (is (not-any? #(and (string? %) (str/starts-with? % "/preview?fn-id=")) flat)
        "no direct same-origin link on cloud")))
