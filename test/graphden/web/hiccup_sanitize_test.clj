(ns graphden.web.hiccup-sanitize-test
  "Allowlist sanitizer for repr hiccup — the security boundary between
   graph-authored representations and the editor DOM. Every rejection
   class gets a regression sentinel: script/frame tags, event-handler
   and htmx attributes, URL-bearing attributes, hostile paint values,
   raw-HTML smuggling, and the recursion/size bounds."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.web.hiccup-sanitize :as sanitize]
    [hiccup.util :as hutil]))


(defn- in-tree?
  [form x]
  (boolean (some #(= x %) (tree-seq coll? seq form))))


(deftest allowed-content-passes-test
  (testing "plain presentation tree passes with attrs normalized to strings"
    (is (= ["div" {"class" "box"} "hi" 42]
           (sanitize/sanitize-hiccup [:div {:class "box"} "hi" 42]))))

  (testing "string tags (the JSONB-roundtripped form) pass"
    (is (= ["span" {} "x"]
           (sanitize/sanitize-hiccup ["span" {} "x"]))))

  (testing "svg sparkline shape passes intact"
    (let [tree [:svg {:viewBox "0 0 240 48" :class "repr-sparkline"
                      :role "img" :aria-label "spark"}
                [:polyline {:points "0,48 120,0 240,24"
                            :fill "none" :stroke "currentColor"}]]
          out  (sanitize/sanitize-hiccup tree)]
      (is (= ["svg" {"viewbox" "0 0 240 48" "class" "repr-sparkline"
                     "role" "img" "aria-label" "spark"}
              ["polyline" {"points" "0,48 120,0 240,24"
                           "fill" "none" "stroke" "currentColor"}]]
             out))))

  (testing "keyword children collapse to their name; nil children vanish"
    (is (= ["div" {} "ok"]
           (sanitize/sanitize-hiccup [:div {} :ok nil]))))

  (testing "a non-vector child seq is walked, not treated as an element"
    (is (= ["ul" {} [["li" {} "a"] ["li" {} "b"]]]
           (sanitize/sanitize-hiccup [:ul {} (list [:li {} "a"] [:li {} "b"])])))))


(deftest disallowed-tags-drop-test
  (testing "script/iframe/img/a/form/foreignObject/use drop entirely"
    (doseq [tag [:script :iframe :img :a :form :input :button
                 :foreignObject :use :animate :style :link :meta :base
                 :object :embed :video :audio :source :math]]
      (is (nil? (sanitize/sanitize-hiccup [tag {} "x"]))
          (str tag " must not survive"))))

  (testing "a disallowed child drops while the rest of the tree survives"
    (is (= ["div" {} "before" "after"]
           (sanitize/sanitize-hiccup
             [:div {} "before" [:script {} "alert(1)"] "after"]))))

  (testing "the id/class tag shorthand is stripped down to the bare tag"
    (is (= ["div" {} "x"]
           (sanitize/sanitize-hiccup [:div#evil.klass {} "x"])))
    (is (nil? (sanitize/sanitize-hiccup [:script#x {} "y"]))
        "shorthand can't smuggle a disallowed tag either")))


(deftest disallowed-attrs-drop-test
  (testing "event handlers, htmx, data-*, style, href/src never pass"
    (is (= ["div" {"class" "ok"} "x"]
           (sanitize/sanitize-hiccup
             [:div {:class "ok"
                    :onclick "alert(1)"
                    :onmouseover "x()"
                    :hx-get "/api/secret"
                    :hx-post "/api/x"
                    :data-form-widget "rating"
                    :data-anything "y"
                    :style "position:fixed"
                    :href "https://evil.example"
                    :src "https://evil.example/i.png"
                    :srcdoc "<script>1</script>"
                    :formaction "/x"}
              "x"]))))

  (testing "namespaced attribute keys (xlink:href) are stripped"
    (is (= ["path" {"d" "M0 0"}]
           (sanitize/sanitize-hiccup [:path {:d "M0 0" :xlink/href "#x"}])))))


(deftest paint-value-policy-test
  (testing "color literals and in-document refs pass"
    (doseq [v ["none" "currentColor" "#fff" "#00ff0080"
               "rgb(1, 2, 3)" "hsla(1,2%,3%,0.5)" "red" "url(#grad-1)"]]
      (is (= ["rect" {"fill" v}]
             (sanitize/sanitize-hiccup [:rect {:fill v}]))
          (str v " should pass"))))

  (testing "external / hostile paint values drop"
    (doseq [v ["url(https://evil.example/x.svg#f)"
               "url(//evil)" "javascript:alert(1)"
               "expression(alert(1))" 42 nil]]
      (is (= ["rect" {}]
             (sanitize/sanitize-hiccup [:rect {:fill v}]))
          (str (pr-str v) " should drop")))))


(deftest raw-html-smuggling-test
  (testing "a non-hiccup object (h-raw RawString etc.) collapses to a plain string"
    (let [raw (hutil/raw-string "<script>alert(1)</script>")
          out (sanitize/sanitize-hiccup [:div {} raw])]
      (is (= ["div" {} "<script>alert(1)</script>"] out)
          "collapsed to a PLAIN string — hiccup2 escapes it on render")
      (is (string? (nth out 2))))))


(deftest bounds-test
  (testing "depth beyond the cap drops the deep subtree, keeps the shallow part"
    (let [deep (reduce (fn [acc _] [:div {} acc]) "leaf" (range 60))
          out  (sanitize/sanitize-hiccup deep)]
      (is (vector? out) "the shallow layers survive")
      (is (not (in-tree? out "leaf")) "the beyond-cap leaf is gone")))

  (testing "node budget bounds total work"
    (let [wide (into [:div {}] (repeat 10000 [:span {} "x"]))
          out  (sanitize/sanitize-hiccup wide)]
      (is (vector? out))
      (is (< (count out) 6000) "children beyond the budget are dropped"))))
