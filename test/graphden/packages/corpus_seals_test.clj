(ns ^:integration graphden.packages.corpus-seals-test
  "No first-party fn-def breaks a seal the API would refuse. The boot
   package sync writes without `write-rej`, so a corpus fn-def that
   re-binds an ancestor's value, binds a `:terminal` slot or appends to a
   `:closed` list would land silently and teach every reader of that fn
   the opposite of lesson 07. Run over the golden graph: every binding
   and list-item row, the three seal rejections only (the other
   write-time rules — cycles, MI, route shape — have their own guards)."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.validation :as v]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*) ["core" "web" "app" "registry" "mcp"]))


(deftest every-corpus-binding-respects-the-seals
  (let [storage (:storage ga/*bootstrap*)
        fn-row (fn [fid] (sp/read-entity storage :fn fid))
        fn-name (fn [fid] (:name (fn-row fid)))
        bad (for [b (sp/query-entities storage :binding {})
                  :let [rej (or (v/value-override-rej storage :binding b)
                                (v/terminal-rej storage :binding b)
                                (v/list-closed-rej storage :binding b))]
                  :when rej]
              ;; Who, on what, binding what, under whom — an anonymous fn is
              ;; found by its parent and its value.
              {:fn (fn-name (:fn-id b))
               :parents (mapv fn-name (:parent-ids (fn-row (:fn-id b))))
               :slot (:name (sp/read-entity storage :slot (:slot-id b)))
               :value (:value b)
               :ref (some-> (:ref-fn-id b) fn-name)
               :rule (:type rej)})]
    (is (empty? bad) (str "corpus bindings that break a seal: " (pr-str (vec bad)))))
  (let [storage (:storage ga/*bootstrap*)
        bad (for [i (sp/query-entities storage :binding-list-item {})
                  :let [rej (v/list-closed-rej storage :binding-list-item i)]
                  :when rej]
              [(:binding-id i) (:reason rej)])]
    (is (empty? bad) (str "corpus list items on a closed list: " (pr-str (vec bad))))))
