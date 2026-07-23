(ns graphden.packages.web.html-test
  "web/html base-fn wiring. Focus: `:render-hiccup` / `:hiccup` must opt
   into `:secret`-taint propagation via the `:taint-propagate? true`
   registry flag (applied centrally by the checker — see SECRETS.md
   § Propagation). They serialize / assemble a hiccup tree whose
   `[:list :any]` arm (and `:hiccup`'s `:any`-valued attrs) can carry a
   secret, so a secret rendered into HTML must taint the result —
   otherwise it escapes the `/api/execute` redaction gate. `:h-raw`
   needs no flag (its `:string` input can't accept a `[:secret :text]`;
   taint can't be stripped).

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
    (testing ":render-hiccup and :hiccup opt into central taint propagation"
      (doseq [k [:render-hiccup :hiccup]]
        (is (true? (:taint-propagate? (get impls k)))
            (str k " must carry :taint-propagate? true — a secret rendered"
                 " into HTML must taint the result"))))
    (testing ":h-raw is a bare impl — no taint flag (its :string input rejects secrets)"
      (is (not (map? (get impls :h-raw)))))))
