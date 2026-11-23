(ns graphden.input.source-forward-list-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.input.source :as source]
    [graphden.input.source-context :as source-context]
    [graphden.input.source-forward :as forward]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn sources
  [path]
  (let [{ns-id :namespace :keys [fns]} (edn/read-string (slurp (io/resource path)))]
    (mapv #(assoc % :namespace ns-id) fns)))


(defn fixture
  ([] (fixture identity))
  ([edit]
   (let [definitions (edit (into (filterv #(= :list (:name %)) (sources "packages/core/collections/fns.edn"))
                                 (concat (filter #(= :str-upper (:name %)) (sources "packages/core/strings/fns.edn"))
                                         [{:namespace "fixture" :name :list-forward :parent :list
                                           :args {:items {:append [{:value nil} {:value false} :str-upper] :closed true}
                                                  :string {:type :text}}}])))
         prepared (parse/prepare-module definitions {} {})
         entry (first (:definitions (source/plan-module [(peek definitions)] prepared)))]
     {:definitions definitions :prepared prepared :entry entry})))


(defn- failure
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))


(deftest list-forward-origin-retains-parent-binding-and-item-occurrence
  (let [{:keys [prepared entry]} (fixture)
        proof (forward/verify-forwarded-field prepared entry :string)
        owner (ids/fn-id "fixture" :list-forward)
        slot (ids/slot-id (ids/fn-id "core.collections" :list) :items)
        binding (ids/binding-id owner slot)
        item (ids/binding-list-item-id binding 2)
        baseline (source/forwarded-fields-baseline prepared entry [:string])]
    (is (= binding (:root-use-id proof)))
    (is (= item (get-in proof [:list-reference :item-id])))
    (is (= 2 (get-in proof [:list-reference :position])))
    (is (= [[owner [binding item]] [(ids/slot-id (ids/fn-id "core.strings" :str-upper) :string)]] (:target-address proof)))
    (is (= :authored-source-equivalent (get-in baseline [:entry :verification])))
    (is (= [[:value nil] [:value false] [:ref :str-upper]]
           (get-in baseline [:entry :proposed :bind :items 1 :items])))
    (is (= :closed (get-in baseline [:entry :proposed :bind :items 1 :tail])))))


(deftest changed-list-row-identity-position-and-payload-refuse-before-forwarding
  (let [{:keys [prepared entry]} (fixture)
        edit-rows (fn [predicate f]
                    (update-in entry [:legacy :records] #(mapv (fn [row] (if (predicate row) (f row) row)) %)))
        item? #(and (= :binding-list-item (:kind %)) (:ref-fn-id %))
        binding? #(= :binding (:kind %))]
    (doseq [changed [(edit-rows item? #(assoc % :id (random-uuid)))
                     (edit-rows item? #(assoc % :position 0))
                     (edit-rows item? #(assoc % :binding-id (random-uuid)))
                     (edit-rows item? #(assoc % :value false))
                     (edit-rows item? #(assoc % :literal true))
                     (edit-rows binding? #(assoc % :fn-id (random-uuid)))
                     (edit-rows binding? #(assoc % :terminal true))
                     (edit-rows binding? #(assoc % :list-closed false))
                     (update-in entry [:legacy :records] #(into % (filter item? %)))]]
      (is (keyword? (failure #(forward/verify-forwarded-field prepared changed :string)))))))


(deftest repeated-list-reference-and-callable-delivery-stay-blocked
  (let [{:keys [prepared entry]}
        (fixture #(assoc-in % [2 :args :items :append] [:str-upper :str-upper]))]
    (is (= :nonunique-reference-use (failure #(forward/verify-forwarded-field prepared entry :string)))))
  (let [{:keys [prepared entry]}
        (fixture #(assoc-in % [0 :args :items :type] [:list :fn-ref]))]
    (is (keyword? (failure #(forward/verify-forwarded-field prepared entry :string))))))


(deftest actual-route-item-origin-is-exact-but-named-narrowing-is-a-separate-proof
  (let [definitions (vec (concat
                           (filter #(#{:list :assoc :assoc-empty :assoc-fn} (:name %))
                                   (sources "packages/core/collections/fns.edn"))
                           (filter #(= :keyword-or-text (:name %)) (sources "packages/core/refinements/fns.edn"))
                           (filter #(#{:route :route-with-middleware :method-map :assoc-handler :_method-map-with-middleware} (:name %))
                                   (sources "packages/app-base/common/fns.edn"))))
        prepared (parse/prepare-module definitions {} {})]
    (doseq [name [:route :route-with-middleware]
            :let [original (first (filter #(and (= "app.common" (:namespace %)) (= name (:name %))) definitions))
                  entry (first (:definitions (source/plan-module [original] prepared)))
                  target (#'forward/reference-occurrence prepared entry :method)
                  origin (#'forward/checked-reference-binding prepared entry target)
                  owner (:identity entry)
                  list-binding (ids/binding-id owner (ids/slot-id (ids/fn-id "core.collections" :list) :items))]]
      (is (= list-binding (:binding-id origin)))
      (is (= (ids/binding-list-item-id list-binding 1) (get-in origin [:list-reference :item-id])))
      (is (= (:target-id target) (get-in origin [:list-reference :target-fn-id])))
      (is (= :unproved-forwarded-target (failure #(forward/verify-forwarded-field prepared entry :method)))))))


(deftest own-list-source-replay-uses-the-frozen-authored-owner
  (let [{:keys [prepared entry]} (fixture)
        owner (:identity entry)
        proof (forward/verify-forwarded-field prepared entry :string)
        {:keys [binding-id builder-id target-id item-id target-fn-id position] :as origin} (:list-reference proof)
        fragment (assoc (dissoc origin :item-id :target-fn-id :position) :scope-path [] :target-path [target-id])
        link {:from [[owner []] [target-id]] :binding-id binding-id :target-fn-id builder-id :origin-fn-id owner
              :name :items :source-list-fragment fragment}
        operand {:from [[owner [binding-id]] [(ids/slot-id builder-id (str "item-" position))]]
                 :binding-id item-id :target-fn-id target-fn-id :origin-fn-id builder-id :name :item-2
                 :source-list-operand {:binding-id binding-id :position position :item-id item-id :ref-fn-id target-fn-id}}
        certificate {:edges [link operand] :field-owner target-fn-id :public-path [:items :item-2 :string]
                     :row {:target-path [(:target-field-id proof)]}}
        baseline (:entry (source/forwarded-fields-baseline prepared entry [:string]))
        env (source-context/environment prepared)
        [lowered rows] (source-context/lower-declarations env (assoc (:proposed baseline) :namespace "fixture"))
        replay (fn [environment cert]
                 (source-context/scoped-reference-context environment lowered rows [{:scope-certificate cert}]))]
    (is (not-any? #(= :binding (:kind %)) rows))
    (is (= certificate (get-in (replay env certificate) [:scoped-inputs [:items :item-2 :string]])))
    (is (= :reference-use-drift (failure #(replay (update env :definition-by-id dissoc owner) certificate))))
    (doseq [changed [(assoc-in certificate [:edges 1 :source-list-operand :position] 0)
                     (assoc-in certificate [:edges 0 :source-list-fragment :owner-id] target-fn-id)]]
      (is (= :reference-use-drift (failure #(replay env changed)))))
    (let [changed (assoc-in env [:definition-by-id owner :args :items :append 2] {:value false})]
      (is (= :reference-use-drift (failure #(replay changed certificate)))))))
