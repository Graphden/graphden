(ns ^:integration graphden.packages.web.htmx-test
  "Tests for the `web.htmx` attrs vocabulary + the app.page fragment
   templates + the vendored htmx asset — the server-driven
   interactivity layer for tenant pages. Pins the composed shapes so
   renames / parent-swaps fail loudly."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.test-infra.graph-harness :as gh]))


(def ^:dynamic *container* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  (gh/graph-fixture (str (ns-name *ns*))))


(defn- header
  [r k kw]
  (or (get-in r [:headers k]) (get-in r [:headers kw])))


;; =============================================================================
;; hx-* attrs builders
;; =============================================================================

(defn- live-attrs
  "Attrs with the nil-valued (unbound-option) keys removed — what the
   renderers actually emit (both drop nil attributes)."
  [m]
  (into {} (remove (comp nil? val)) m))


(deftest hx-get-attrs-minimal-test
  (testing "url alone → just the verb attr survives rendering"
    (is (= {:hx-get "/fragments/stats"}
           (live-attrs (gh/exec-name :hx-get-attrs {:url "/fragments/stats"}))))))


(deftest hx-get-attrs-full-test
  (testing "target / swap / trigger fold in when bound"
    (is (= {:hx-get "/f" :hx-target "#out" :hx-swap "outerHTML"
            :hx-trigger "every 10s"}
           (live-attrs (gh/exec-name :hx-get-attrs {:url "/f"
                                                    :target "#out"
                                                    :swap "outerHTML"
                                                    :trigger "every 10s"}))))))


(deftest hx-post-attrs-test
  (is (= {:hx-post "/f" :hx-target "#panel"}
         (live-attrs (gh/exec-name :hx-post-attrs {:url "/f" :target "#panel"})))))


(deftest hx-attrs-nil-options-vanish-at-render-test
  (testing "the server renderer drops the unbound-option nil attrs"
    (let [html (gh/exec-name :render-hiccup
                             {:hiccup [:button
                                       (gh/exec-name :hx-get-attrs {:url "/f"})
                                       "Go"]})]
      (is (= "<button hx-get=\"/f\">Go</button>" (str html))))))


(deftest sse-connect-attrs-test
  (testing "subscribes the element to a stream — ext + connect + default event"
    (is (= {:hx-ext "sse" :sse-connect "/streams/clock" :sse-swap "message"}
           (gh/exec-name :sse-connect-attrs {:url "/streams/clock"}))))
  (testing "a custom event name overrides the default"
    (is (= {:hx-ext "sse" :sse-connect "/s" :sse-swap "tick"}
           (gh/exec-name :sse-connect-attrs {:url "/s" :event "tick"})))))


(deftest hx-button-test
  (testing "the htmx twin of :submit-button"
    (let [[tag attrs label] (gh/exec-name :hx-button {:label "Vote" :url "/vote"
                                                      :target "#result"})]
      (is (= :button tag))
      (is (= {:hx-post "/vote" :hx-target "#result"} (live-attrs attrs)))
      (is (= "Vote" label))))
  (testing ":extras merge last — caller wins"
    (let [[_ attrs] (gh/exec-name :hx-button {:label "Vote" :url "/vote"
                                              :extras {:class "primary"}})]
      (is (= {:hx-post "/vote" :class "primary"} (live-attrs attrs))))))


;; =============================================================================
;; app.page fragment templates
;; =============================================================================

(deftest html-fragment-handler-test
  (testing "renders the fragment hiccup as a text/html 200 — no page shell"
    (let [r (gh/exec-name :html-fragment-handler
                          {:fragment ["p" {} "hi"]})]
      (is (= 200 (:status r)))
      (is (= "text/html; charset=utf-8" (header r "Content-Type" :Content-Type)))
      (is (= "<p>hi</p>" (str (:body r))))
      (is (not (str/includes? (str (:body r)) "<html"))))))


;; =============================================================================
;; vendored htmx asset + with-htmx
;; =============================================================================

(deftest htmx-asset-handler-test
  (testing "GET /assets/htmx.min.js — the vendored bundle, immutable-cached"
    (let [r (gh/exec-name :_htmx-js-handler {})]
      (is (= 200 (:status r)))
      (is (str/includes? (str (header r "Cache-Control" :Cache-Control))
                         "immutable"))
      (is (str/starts-with? (str (:body r)) "var htmx")
          "the body IS the vendored htmx source"))))


;; `:with-htmx` / `:htmx-script-tag` need `:build-hash-frontend-short`,
;; which reads the BUILD-time `graphden-build-hashes.json` resource —
;; absent in the test classpath. Their URL chain is covered live: the
;; editor page itself loads `/assets/htmx.min.js?v=<hash>` (e2e), and
;; the asset handler above proves the served body.


;; =============================================================================
;; Lesson 24 pins — the tutorial's fn-defs, synced VERBATIM through the
;; real declarative sync and executed. If a vocabulary rename breaks
;; the lesson, this fails before the lesson goes stale.
;; =============================================================================

(def ^:private lesson-24-defs
  [{:name :clock-fragment
    :parent :wrap-element
    :args {:tag "p"
           :content {:parent :to-str
                     :args {:value {:parent :current-time-ms
                                    :args {}}}}}}
   {:name :clock-fragment-route
    :parent :fragment-route
    :args {:path "/fragments/clock"
           :fragment :clock-fragment}}
   {:name :clock-page-body
    :parent :stack
    :args {:children
           [{:parent :heading
             :args {:level 2 :content "Server clock"}}
            {:parent :button
             :args {:label "Refresh"
                    :attrs {:parent :hx-get-attrs
                            :args {:url "/fragments/clock"
                                   :target "#clock-out"}}}}
            {:parent :card
             :args {:children ["press Refresh"]
                    :attrs {:value {:id "clock-out"}}}}]}}
   {:name :vote-fragment
    :parent :wrap-element
    :args {:tag "p"
           :content {:parent :str
                     :args {:parts
                            ["you voted: "
                             {:parent :get
                              :args {:coll {:parent :parse-form-body
                                            :args {:request {:as :request}}}
                                     :key "choice"
                                     :default "nothing"}}]}}}}
   {:name :vote-fragment-handler
    :lambda-params [:request]
    :parent :html-fragment-handler
    :args {:fragment :vote-fragment}}
   {:name :vote-fragment-route
    :parent :post-route
    :args {:path "/fragments/vote"
           :handler :vote-fragment-handler}}
   ;; — the lesson's SSE section —
   {:name :sse-clock-handler
    :lambda-params [:request]
    :parent :sse-fragment-handler
    :args {:fragment :clock-fragment
           :interval-ms 1000}}
   {:name :sse-clock-route
    :parent :get-route
    :args {:path "/streams/clock"
           :handler :sse-clock-handler}}
   {:name :sse-clock-panel
    :parent :card
    :args {:children ["connecting…"]
           :attrs {:parent :sse-connect-attrs
                   :args {:url "/streams/clock"}}}}])


(deftest lesson-24-defs-sync-verbatim-test
  ;; The bootstrap ctx snapshots its compiled registry, so post-sync
  ;; fns can't execute HERE — runtime behaviour is pinned by the
  ;; template-level tests above (each lesson composition is those
  ;; templates with execute-time args). What THIS pins is the lesson's
  ;; literal EDN: names, refs, types and lambda-params all resolve
  ;; through the real declarative sync — the paste-correctness bar.
  (let [{:keys [storage]} gh/*graph*
        ids (fn-composition/sync-fns-to-storage! storage lesson-24-defs)]
    (is (every? ids [:clock-fragment :clock-fragment-route :clock-page-body
                     :vote-fragment :vote-fragment-handler
                     :vote-fragment-route :sse-clock-handler :sse-clock-route
                     :sse-clock-panel])
        "every lesson fn-def synced and got an id")))


(deftest lesson-24-compositions-behave-test
  ;; The lesson's runtime claims, exercised through the SAME templates
  ;; with execute-time args (the harness path page-test uses).
  (testing "the refresh button — :button over :hx-get-attrs"
    (let [[tag attrs label] (gh/exec-name :button
                                          {:label "Refresh"
                                           :attrs (gh/exec-name :hx-get-attrs
                                                                {:url "/fragments/clock"
                                                                 :target "#clock-out"})})]
      (is (= :button tag))
      (is (= {:hx-get "/fragments/clock" :hx-target "#clock-out"}
             (live-attrs attrs)))
      (is (= "Refresh" label))))
  (testing "the POST-fragment form read — :parse-form-body over the ring request"
    (is (= "htmx"
           (get (gh/exec-name :parse-form-body
                              {:request (gh/form-req "/fragments/vote"
                                                     "choice=htmx")})
                "choice")))))
