(ns graphden.storage.remote.sse-test
  "Unit coverage for the pure line->event dispatch in the remote SSE source.
   The socket + thread I/O (stream-once!/start-source!/stop-source!) is
   genuinely integration-only — mocking java.net.http.HttpClient's streaming
   InputStream would be a fixture as complex as the code it exercises — and is
   covered via remote.core's ^:integration path + the SSE relay (docs/SCALING.md
   § SSE). This file pins the one piece that is pure: frame classification +
   payload dispatch."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.postgres.notify :as notify]
    [graphden.storage.remote.sse :as sse]))


;; handle-line is intentionally private (an implementation detail of the read
;; loop); reach it via the var so the test doesn't force a visibility change.
(def ^:private handle-line #'sse/handle-line)


(deftest handle-line-dispatches-data-frames
  (testing "a `data:` frame parses via notify/parse-payload and reaches on-event"
    (let [seen (atom [])
          payload (notify/format-payload {:kind :fn :op :update :id "abc-123"})]
      (handle-line #(swap! seen conj %) (str "data: " payload))
      (is (= [{:kind :fn :op :update :id "abc-123"}] @seen)
          "the parsed event map is delivered unchanged")))

  (testing "no space after the colon is fine; branch-id + org-id ride along"
    (let [seen (atom [])
          payload (notify/format-payload
                    {:kind :binding :op :delete :id "x" :branch-id "b1" :org-id "o1"})]
      (handle-line #(swap! seen conj %) (str "data:" payload))
      (is (= [{:kind :binding :op :delete :id "x" :branch-id "b1" :org-id "o1"}] @seen)))))


(deftest handle-line-ignores-non-data-lines
  (let [seen (atom [])
        on-event #(swap! seen conj %)]
    (testing "`:`-comment keepalive lines are ignored"
      (handle-line on-event ": keepalive")
      (handle-line on-event ":")
      (is (empty? @seen)))
    (testing "blank lines are ignored"
      (handle-line on-event "")
      (is (empty? @seen)))
    (testing "a data frame whose payload doesn't parse is dropped — no dispatch, no throw"
      (handle-line on-event "data: not-a-valid-payload")
      (is (empty? @seen)))))


(deftest handle-line-swallows-on-event-throw
  (testing "a throwing on-event is caught — a bad consumer can't kill the read loop"
    (let [payload (notify/format-payload {:kind :fn :op :update :id "id1"})]
      (is (nil? (handle-line (fn [_] (throw (ex-info "boom" {})))
                             (str "data: " payload)))
          "returns nil and does not propagate the exception"))))
