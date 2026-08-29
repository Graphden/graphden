(ns graphden.packages.app.feedback-routes-test
  "Regression sentinels for the feedback intake (`app.feedback`).

   POST /api/feedback is an OPEN write route — the abuse posture lives
   in the composition, so these tests pin it structurally: the env
   arm-gate guards the ladder, the honeypot precedes validation, every
   stored field is clipped, the response carries the CORS header the
   cross-origin form depends on, and both routes stay on the OPEN
   templates (flipping them to auth-required would silently break
   reporting from other instances' editors).

   Also pins the baked-twin invariant: the default intake URL exists in
   two places by design (the server-side :_fb-config-url default and
   editor-feedback.js's dead-backend fallback) — they must never drift."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.loader :as loader]))


(def ^:private defs
  (delay (into {}
               (keep (fn [fd] (when (:name fd) [(:name fd) fd])))
               (:fn-defs (loader/load-packages ["app"])))))


(deftest feedback-routes-stay-open
  (testing "the intake POST is an open route (reporting must not need an account here)"
    (is (= :post-route (:parent (get @defs :api-feedback)))))
  (testing "the config probe is an open route (anonymous/demo sessions load the form)"
    (is (= :get-route (:parent (get @defs :api-feedback-config))))))


(deftest intake-response-carries-cors
  (let [headers (get-in @defs [:_fb-cors-json-content-type :args :headers])]
    (testing "the form posts cross-origin — without ACAO the browser refuses the response"
      (is (= "*" (get headers "Access-Control-Allow-Origin")))
      (is (str/starts-with? (str (get headers "Content-Type")) "application/json")))
    (testing "the handler actually wears that content-type axis"
      (is (some #{:_fb-cors-json-content-type} (:parents (get @defs :feedback-handler)))))))


(deftest intake-decision-ladder-posture
  (let [clauses (get-in @defs [:_fb-result :args :clauses])]
    (testing "the env arm-gate is the FIRST clause — an unarmed instance touches nothing"
      (is (= :_fb-intake-enabled?
             (get-in (first clauses) [:args :value])))
      (is (= "disabled" (get-in (second clauses) [:value :error]))))
    (testing "the honeypot precedes validation and answers a fake success"
      (is (= :_fb-honeypot? (nth clauses 2)))
      (is (= {:ok true} (:value (nth clauses 3)))))))


(deftest stored-fields-are-clipped
  (testing "every stored field rides :str-clip with its cap"
    (is (= 10000 (get-in @defs [:_fb-text :args :limit])))
    (is (= 200 (get-in @defs [:_fb-email :args :limit])))
    (is (= 32768 (get-in @defs [:_fb-context-json :args :limit])))
    (doseq [n [:_fb-text :_fb-email :_fb-context-json]]
      (is (= :str-clip (:parent (get @defs n)))
          (str n " must be clipped")))))


(deftest rate-caps-present
  (let [vals (get-in @defs [:_fb-rate-limited? :args :values])
        caps (set (map #(get-in % [:args :nums 1 :value]) vals))]
    (testing "both fixed-window caps are wired (global + per-IP)"
      (is (= #{200 20} caps)))))


(deftest default-intake-url-baked-twins-agree
  (let [server-default (get-in @defs [:_fb-config-url :args :clauses 1 :value])
        js (slurp (io/resource "packages/app/editor/editor-feedback.js"))
        [_ js-default] (re-find #"FEEDBACK_DEFAULT_URL = '([^']+)'" js)]
    (testing "server config default == the JS dead-backend fallback"
      (is (string? server-default))
      (is (= server-default js-default)))))
