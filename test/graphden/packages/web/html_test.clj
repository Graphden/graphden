(ns graphden.packages.web.html-test
  "web/html base-fn wiring. Focus: `:render-hiccup` / `:hiccup` must carry
   a `:secret`-taint-propagating `:return-type-rule`. They serialize /
   assemble a hiccup tree whose `[:list :any]` arm (and `:hiccup`'s
   `:any`-valued attrs) can carry a secret, so a secret rendered into HTML
   must taint the result — otherwise it escapes the `/api/execute`
   redaction gate. `:h-raw` needs no rule (its `:string` input can't accept
   a `[:secret :text]`; taint can't be stripped).

   Loads the impls map via `load-file` (mirrors `effect-trace-test`) so the
   test doesn't pull the whole package loader."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))


(defn- html-impls
  []
  (let [r (io/resource "packages/web/html/impls.clj")]
    (when r
      (load-file (java.io.File/.getPath (io/file r))))
    @(ns-resolve 'graphden.packages.web.html.impls 'impls)))


(deftest hiccup-serializers-propagate-secret-taint-test
  (let [impls (html-impls)]
    (testing ":render-hiccup and :hiccup wire a taint-propagating return-rule"
      (doseq [k [:render-hiccup :hiccup]]
        (let [rule (:return-type-rule (get impls k))]
          (is (fn? rule) (str k " must wire a :return-type-rule"))
          (is (= [:secret :text]
                 (rule {:x {:type [:list [:secret :text]]}} :text))
              (str k " taints its return when an input carries a secret"))
          (is (= :text (rule {:x {:type :text}} :text))
              (str k " leaves a clean (non-secret) return untainted")))))
    (testing ":h-raw is a bare impl — no taint rule (its :string input rejects secrets)"
      (is (not (map? (get impls :h-raw)))))))
