(ns ^:integration ^:serial graphden.packages.primitive-input-test
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.interface :as exec]
    [graphden.input.list-builder :as list-builder]
    [graphden.input.migration :as migration]
    [graphden.input.pinned-list :as pinned]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.system.init.packages]
    [graphden.test-infra.golden-app :as golden]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as resolution]
    [integrant.core :as ig]))


(use-fixtures :once (golden/fixture (ns-name *ns*)))


(def ^:private root (ids/fn-id "inputfixture.numeric" :fixture-floor))
(def ^:private child (ids/fn-id "inputfixture.numeric" :fixture-floor-negative))
(def ^:private floor (ids/fn-id "core.arithmetic" :floor))


(def ^:private admission
  {:module ["inputfixture" "numeric"] :fn-id root :abi-fn-id floor
   :sha256 "b9916eb06942752882460e8503147148878736c384bf5bea081236f470e5ecb2"})


(def ^:private power-root (ids/fn-id "inputfixture.power" :fixture-power))
(def ^:private two-cubed (ids/fn-id "inputfixture.power" :two-cubed))


(def ^:private power-admission
  {:module ["inputfixture" "power"] :fn-id power-root :abi-fn-id (ids/fn-id "core.arithmetic" :pow)
   :sha256 "c5c6d98fec6e550c00b86bb0fa9f6a4b1242a713dd303d8acf71735c7292fb2e"})


(deftest unadmitted-source-fails-before-any-package-implementation
  (let [loads (atom 0)]
    (with-redefs-fn {#'loader/load-module-impls (fn [& _] (swap! loads inc))}
      #(doseq [options [nil
                        {:primitive-input (assoc admission :sha256 "wrong")}
                        {:primitive-input (assoc admission :fn-id (random-uuid))}
                        {:primitive-input (assoc admission :module ["inputfixture" "absent"])}]]
         (is (thrown? clojure.lang.ExceptionInfo (loader/load-packages ["inputfixture"] options)))))
    (is (zero? @loads))))


(defn- post-run
  [storage base-fns]
  (let [token (str (random-uuid))
        ctx (exec/create-context {:storage storage :base-fns base-fns
                                  :auth-provider (auth/single-token-provider token)})
        _ (runtime/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")
        post (fn [id args]
               (let [response (router/dispatch app {:request-method :post :uri "/api/execute"
                                                    :headers {"content-type" "application/json"
                                                              "authorization" (str "Bearer " token)}
                                                    :body (json/generate-string {:fn-id id :args args :timeout-ms 30000})})]
                 (json/parse-string (:body response) true)))]
    post))


(defn- partial-power-edition
  []
  (let [resource io/resource
        pin (assoc power-admission :sha256 "15d2b7f0cd504c85d41ae310b3e50e7842ce94f3f3df01e1dbd3deec61210389")]
    (with-redefs [io/resource (fn [path]
                                (resource (if (= path "packages/inputfixture/power/fns.edn")
                                            "packages/inputfixture/power/partial.edn" path)))]
      (ig/init-key :app/packages {:package-names ["core" "web" "app" "inputfixture"]
                                  :primitive-inputs [admission pin]}))))


(deftest pinned-classpath-declaration-syncs-and-executes
  (let [storage (:storage golden/*bootstrap*)
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "inputfixture"]
                                             :primitive-inputs [admission power-admission]})
        sync! #(ig/init-key :exec/base-fns {:storage storage :packages packages})
        {:keys [base-fns] :as base-info} (sync!)
        _ (ig/init-key :exec/fn-entities {:storage storage :packages packages :base-fns base-info})
        input (migration/derived-id :input-type root)
        child-input (migration/derived-id :input-type child)
        number-slot (ids/slot-id root :number)
        binding (ids/binding-id child number-slot)
        power-slots (mapv #(ids/slot-id power-root %) [:base :exponent])
        power-bindings (mapv #(ids/binding-id two-cubed %) power-slots)
        memberships #(sp/query-entities storage :fn-slot {:fn-id input})
        version #(resolution/resolve-version-ids (vs/unwrap storage) :fn [root input child child-input]
                                                 (vs/current-branch-id storage))
        binding-version #(resolution/resolve-version-ids (vs/unwrap storage) :input-binding (conj power-bindings binding)
                                                         (vs/current-branch-id storage))
        before (version)
        before-binding (binding-version)
        member-ids (mapv :id (memberships))
        post (post-run storage base-fns)]
    (is (= input (:input-type-fn-id (sp/read-entity storage :fn root))))
    (is (= child-input (:input-type-fn-id (sp/read-entity storage :fn child))))
    (is (= [root] (:parent-ids (sp/read-entity storage :fn child))))
    (is (nil? (:input-type-fn-id (sp/read-entity storage :fn floor))))
    (is (= [number-slot] (mapv :slot-id (memberships))))
    (is (= [number-slot] (mapv :slot-id (sp/query-entities storage :fn-slot {:fn-id child-input}))))
    (is (identical? (:fixture-floor base-fns) (:floor base-fns)))
    (is (= -4 (:result (post root {:number -3.2}))))
    (is (= -4 (:result (post floor {:number -3.2}))))
    (is (= -8 (:result (post child {}))))
    (is (== 8 (:result (post two-cubed {}))))
    (doseq [field [:base :exponent]]
      (is (= "unknown-input-field" (get-in (post two-cubed {field 1}) [:error-data :code]))))
    (is (= (mapv #(vector (str %)) power-slots)
           (mapv #(:target-path (sp/read-entity storage :input-binding %)) power-bindings)))
    (is (= "unknown-input-field" (get-in (post child {:number 1}) [:error-data :code])))
    (is (= "invalid-structured-arguments" (get-in (post root {:unknown 1}) [:error-data :reason])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"primitive admission failed"
          (ig/init-key :exec/base-fns {:storage storage :packages packages
                                       :extra-base-fns {:fixture-floor (:ceil base-fns)}})))
    (is (= #{root input child child-input} (set (keys before))))
    (is (= (conj (set power-bindings) binding) (set (keys before-binding))))
    (is (= [(str number-slot)] (:target-path (sp/read-entity storage :input-binding binding))))
    (sync!)
    (is (= before (version)))
    (is (= before-binding (binding-version)))
    (is (= member-ids (mapv :id (memberships))))
    (let [partial-packages (partial-power-edition)
          [base exponent] power-bindings]
      (ig/init-key :exec/base-fns {:storage storage :packages partial-packages})
      (is (== 16 (:result (post two-cubed {:exponent 4}))))
      (is (= (get before-binding base) (get (binding-version) base)))
      (is (nil? (sp/read-entity storage :input-binding exponent)))
      (sync!)
      (is (== 8 (:result (post two-cubed {}))))
      (is (some? (sp/read-entity storage :input-binding exponent))))))


(deftest two-list-fragments-boot-run-and-resync
  (let [owner #(ids/fn-id "listfixture.numeric" %)
        base (owner :list-difference)
        prefix (owner :difference-prefix)
        complete (owner :difference-complete)
        slot (ids/slot-id base :nums)
        pin {:module ["listfixture" "numeric"] :fn-id base :abi-fn-id (ids/fn-id "core.arithmetic" :sub)
             :list-contract {:nums [:list :numeric]}
             :sha256 "69a02c2242fc036d23a8b9f030af7035ccc675abe8364712e9ddd002db6db6e6"}
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "listfixture"] :primitive-input pin})
        storage (:storage golden/*bootstrap*)
        sync! (fn []
                (let [info (ig/init-key :exec/base-fns {:storage storage :packages packages})]
                  (ig/init-key :exec/fn-entities {:storage storage :packages packages :base-fns info})
                  (:base-fns info)))
        post (post-run storage (sync!))
        [a b :as fragments] (mapv #(list-builder/origin % slot) [prefix complete])
        versions #(resolution/resolve-version-ids (vs/unwrap storage) %1 %2 (vs/current-branch-id storage))
        bindings (mapcat (fn [{:keys [binding-id]}]
                           [binding-id (ids/binding-list-item-id binding-id 0) (ids/binding-list-item-id binding-id 1)]) fragments)
        snapshot (fn []
                   {:bindings (versions :input-binding (conj (vec bindings) (ids/binding-id (:builder-id b) (:tail-id b))))
                    :owners (versions :fn [base prefix complete (:builder-id a) (:builder-id b)])})
        before (snapshot)]
    (is (= 11 (:result (post complete {}))))
    (is (= 15 (:result (post prefix {:nums {:tail [2]}}))))
    (is (= 7 (:result (post base {:nums [10 3]}))))
    (is (= "invalid-structured-arguments" (get-in (post complete {:nums [0]}) [:error-data :reason])))
    (is (= 7 (count (:bindings before))))
    (is (= 5 (count (:owners before))))
    (doseq [{:keys [builder-id]} fragments]
      (let [input (migration/derived-id :input-type builder-id)
            members (sort-by :position (sp/query-entities storage :fn-slot {:fn-id input}))]
        (is (= (mapv (fn [name pos] [(ids/slot-id builder-id name) pos]) [:item-0 :item-1 :tail] (range 3))
               (mapv (juxt :slot-id :position) members)))))
    (sync!)
    (is (= before (snapshot)))))


(deftest pinned-list-admission-requires-the-declared-parent-tail-and-element
  (let [source (edn/read-string (slurp (io/resource "packages/listfixture/numeric/fns.edn")))
        base (ids/fn-id (:namespace source) :list-difference)
        pin {:nums [:list :numeric]}
        operand [:fns 1 :bind :nums 1 :items 0]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported pinned list"
          (pinned/prepare source base {:nums [:list :text]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported pinned list"
          (pinned/prepare (assoc-in source operand [:ref :list-difference]) base pin)))
    (is (= :closed-list-continuation
           (try (pinned/prepare (assoc-in source [:fns 1 :bind :nums 1 :tail] :closed) base pin) nil
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))
