(ns graphden.crud.type-check-extension-test
  "The rich-types entry of a fn EXTENDED FROM THE EDITOR (a bare
   `POST /api/entities/fn` with a parent, no bindings) — what the
   inspector's Bindings tab and the fn picker's signature check read.
   It must agree with the free-arg surface the Run form lists: the
   parent's own bound slots are not free, and nothing that is not a
   slot of the composition appears."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.type-check :as tc]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.golden-app :as ga :refer [*bootstrap*]]
    [graphden.types.check :as tcheck]
    [graphden.types.core :as types]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(deftest extension-registry-args-match-the-free-surface-test
  (let [{:keys [ctx all-name->id]} *bootstrap*
        extend! (fn [nm parent]
                  (let [row (entities/create-entity "fn" {:name nm :parent-ids [(get all-name->id parent)]} ctx)]
                    (tc/type-check-fn-after-mutation! (:storage ctx) (:id row))
                    (registry/rich-type-of-id (:id row))))]
    (testing "the package template itself: service-get's frees"
      (is (= #{:service :path :headers :body :auth-value :timeout-ms}
             (set (keys (:args (registry/rich-type-of :service-get)))))
          "status — a field of :http-request's RESULT — is not a slot")
      (is (not (contains? (:args (registry/rich-type-of :encode-stringify-wrap)) :status))
          "…nor is it one of a Ring wrap's (its parent returns a response record)"))
    (testing "an editor extension of service-get has the SAME frees — url is bound in the parent, status is no slot at all"
      (let [info (extend! "tce-fetch" :service-get)]
        (is (= #{:service :path :headers :body :auth-value :timeout-ms}
               (set (keys (:args info)))))
        (is (= :_service-url-join (get-in info [:resolved-bindings :url :bound-ref]))
            "url is bound (with a :type pin) in the parent — the Bindings tab shows the ref, not 'free'")))
    (testing "an editor extension of a Ring wrap fits the listener's handler slot once its base-handler is bound"
      (let [handler-slot [:fn {:request :ring-request-shape} :ring-response-shape]
            ring (extend! "tce-ring" :encode-stringify-wrap)]
        (is (contains? (:args ring) :base-handler))
        (is (not (types/subtype? (tcheck/assemble-fn-type :tce-ring) handler-slot))
            "with base-handler still free it does NOT fit — an honest no")
        (testing "…and a plain response fn extended in the editor (body bound) DOES fit"
          (let [parent-id (get all-name->id :json-ok-response)
                storage (:storage ctx)
                ;; `body` is declared up the chain — walk parent-ids.
                chain (loop [todo [parent-id] seen []]
                        (if-let [f (first todo)]
                          (recur (into (subvec todo 1) (:parent-ids (sp/read-entity storage :fn f)))
                                 (conj seen f))
                          seen))
                body-slot (->> (sp/query-entities storage :fn-slot {:fn-id chain})
                               (map :slot-id)
                               (map #(sp/read-entity storage :slot %))
                               (some #(when (= "body" (:name %)) %)))
                row (entities/create-entity "fn" {:name "tce-ok" :parent-ids [parent-id]} ctx)
                _ (entities/create-entity "binding" {:fn-id (:id row) :slot-id (:id body-slot)
                                                     :value "{\"ok\":true}"} ctx)
                _ (tc/type-check-fn-after-mutation! (:storage ctx) (:id row))
                info (registry/rich-type-of-id (:id row))]
            (is (empty? (:args info)) "nothing left free")
            (is (types/subtype? (tcheck/assemble-fn-type (:name info)) handler-slot)
                "a 0-free-arg response producer fits a `request → response` slot")))))))
