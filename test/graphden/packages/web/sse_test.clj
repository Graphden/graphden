(ns graphden.packages.web.sse-test
  "Unit tests for `web.sse`'s wire format and its stream ceiling — the
   two decisions the SSE primitive makes before any connection exists.

   The frame shape is a protocol contract, not a detail: a multi-line
   fragment MUST be split into one `data:` line per source line, or the
   browser reassembles somebody's HTML into a single line and the panel
   renders wrong. The ceiling is what stops a page from opening streams
   without bound; it reads an env var, so the default has to be pinned
   here rather than assumed."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "web" "sse"))


(defn- priv
  [sym]
  (let [v (ns-resolve 'graphden.packages.web.sse.impls sym)]
    (assert v (str "no such var: " sym))
    @v))


(deftest sse-frame-is-one-data-line-per-source-line
  (let [frame (priv 'sse-frame)]
    (testing "a single-line fragment is one event + one data line"
      (is (= "event: message\ndata: <p>hi</p>\n\n" (frame "<p>hi</p>"))))
    (testing "a multi-line fragment splits — the wire format rejoins it"
      (is (= "event: message\ndata: <div>\ndata:   <p>x</p>\ndata: </div>\n\n"
             (frame "<div>\n  <p>x</p>\n</div>"))))
    (testing "every frame ends with the blank line that terminates an event"
      (is (str/ends-with? (frame "x") "\n\n")))
    (testing "an empty payload still produces a well-formed frame"
      (is (= "event: message\ndata: \n\n" (frame ""))))
    (testing "a non-string payload is stringified, not thrown on"
      (is (= "event: message\ndata: 42\n\n" (frame 42))))))


(deftest max-streams-has-a-default-and-a-test-cap
  (let [f (priv 'max-streams)
        cap (ns-resolve 'graphden.packages.web.sse.impls 'max-streams-test-cap)]
    (testing "the shipped default bounds open streams"
      (is (= 200 (f)) "the default ceiling is what an unconfigured deployment gets"))
    (testing "the test cap seam wins when set"
      (try
        (reset! @cap 3)
        (is (= 3 (f)))
        (finally (reset! @cap nil))))))
