(ns ^:serial graphden.versioning.storage.diff-view-unit-test
  "Unit tests for `versioning.storage.diff-view` — the pure display
   helpers (value rendering, label resolution, previews, per-entry
   assembly) driven directly with literal row maps, and the full
   `diff-branches-view` grouping / ordering contract driven over a
   stubbed `mrg/diff-branches` + protocol stub (the DB-backed path
   stays covered by the `^:integration` `diff-view-test`).

   `^:serial` — `with-redefs` on `mrg/*` / `bl/*` mutates root vars."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.branch-local :as bl]
    [graphden.versioning.storage.diff-view :as dv]
    [graphden.versioning.storage.merge :as mrg]))


(def ^:private truncate #'dv/truncate)
(def ^:private short-id #'dv/short-id)
(def ^:private fn-label #'dv/fn-label)
(def ^:private display-value #'dv/display-value)
(def ^:private changed-fields #'dv/changed-fields)
(def ^:private owner-fn-id #'dv/owner-fn-id)
(def ^:private entry-slot-id #'dv/entry-slot-id)
(def ^:private binding-preview #'dv/binding-preview)
(def ^:private entry-preview #'dv/entry-preview)
(def ^:private entry #'dv/entry)
(def ^:private resolve-fn-names #'dv/resolve-fn-names)


;; === string massaging =======================================================

(deftest truncate-bounds-test
  (testing "short strings pass through untouched"
    (is (= "abc" (truncate "abc" 5))))
  (testing "long strings end in an ellipsis at exactly n chars"
    (is (= "abcd…" (truncate "abcdef" 5))))
  (testing "non-string input is coerced first"
    (is (= "12…" (truncate 12345 3)))))


(deftest short-id-test
  (is (= "12345678"
         (short-id #uuid "12345678-9abc-4def-8123-456789abcdef")))
  (is (nil? (short-id nil))))


(deftest fn-label-resolved-vs-fallback-test
  (let [id #uuid "12345678-9abc-4def-8123-456789abcdef"]
    (testing "a resolved name renders as a keyword-style label"
      (is (= ":web-server" (fn-label {id "web-server"} id))))
    (testing "an unresolved id falls back to a short-id hash"
      (is (= "#12345678" (fn-label {} id))))))


;; === display-value ==========================================================

(deftest display-value-shapes-test
  (let [ref-id #uuid "aaaaaaaa-1111-4111-8111-111111111111"]
    (testing "nil renders as the empty-set marker"
      (is (= "∅" (display-value {} :value nil))))
    (testing "a uuid in a ref field renders as the referenced fn's label"
      (is (= ":add" (display-value {ref-id "add"} :ref-fn-id ref-id)))
      (is (= "#aaaaaaaa" (display-value {} :type-override-fn-id ref-id))))
    (testing "a uuid in a NON-ref field is just printed data"
      (is (= (pr-str ref-id) (display-value {ref-id "add"} :value ref-id))))
    (testing "strings stay bare, bounded at 120 chars"
      (is (= "hello" (display-value {} :value "hello")))
      (let [long-s (str/join (repeat 300 "x"))
            out (display-value {} :value long-s)]
        (is (= 120 (count out)))
        (is (str/ends-with? out "…"))))
    (testing "other values pr-str with bounded print depth/length"
      (is (= "{:a 1}" (display-value {} :value {:a 1})))
      (let [out (display-value {} :value (vec (range 100)))]
        (is (<= (count out) 120))
        ;; *print-length* 24 elides the tail rather than printing 100 items.
        (is (str/includes? out "..."))
        (is (not (str/includes? out "99")))))))


;; === changed-fields =========================================================

(deftest changed-fields-symmetric-sorted-test
  (testing "keys from either side count; :created-at is noise; only
            actual differences survive, sorted"
    (is (= [:b :c]
           (changed-fields {:a 1 :b 2 :created-at 1}
                           {:a 1 :b 3 :c 4 :created-at 9}))))
  (testing "identical maps diff to nothing"
    (is (= [] (vec (changed-fields {:a 1} {:a 1}))))))


;; === ownership + slot resolution ============================================

(deftest owner-fn-id-per-entity-type-test
  (let [fid #uuid "bbbbbbbb-2222-4222-8222-222222222222"
        bid #uuid "cccccccc-3333-4333-8333-333333333333"
        bindings {bid {:id bid :fn-id fid :slot-id nil}}]
    (testing "an fn row owns itself"
      (is (= fid (owner-fn-id {:entity-name :fn :entity-id fid} {}))))
    (testing "fn-slot / binding rows carry :fn-id — source side preferred"
      (is (= fid (owner-fn-id {:entity-name :binding
                               :source-version {:fn-id fid}} {}))))
    (testing "target side is the fallback when the source is absent"
      (is (= fid (owner-fn-id {:entity-name :fn-slot
                               :source-version nil
                               :target-version {:fn-id fid}} {}))))
    (testing "list items resolve their owner through the bindings map"
      (is (= fid (owner-fn-id {:entity-name :binding-list-item
                               :source-version {:binding-id bid}}
                              bindings))))
    (testing "ownerless entity types answer nil"
      (is (nil? (owner-fn-id {:entity-name :resource-override} {}))))))


(deftest entry-slot-id-test
  (let [sid #uuid "dddddddd-4444-4444-8444-444444444444"
        bid #uuid "eeeeeeee-5555-4555-8555-555555555555"]
    (is (= sid (entry-slot-id {:entity-name :binding
                               :source-version {:slot-id sid}} {})))
    (is (= sid (entry-slot-id {:entity-name :binding-list-item
                               :source-version {:binding-id bid}}
                              {bid {:slot-id sid}})))
    (is (nil? (entry-slot-id {:entity-name :fn} {})))))


;; === previews ===============================================================

(deftest binding-preview-composes-facets-test
  (let [r #uuid "aaaaaaaa-1111-4111-8111-111111111111"
        t #uuid "bbbbbbbb-2222-4222-8222-222222222222"
        names {r "handler" t "text"}]
    (testing "every present facet joins with a middot"
      (is (= (str "value = 8080 · ref → :handler · type ⇒ :text"
                  " · terminal · list-append · list-closed · “tuned”")
             (binding-preview names {:value 8080
                                     :ref-fn-id r
                                     :type-override-fn-id t
                                     :terminal true
                                     :list-append true
                                     :list-closed true
                                     :description "tuned"}))))
    (testing "false is a VALUE (some?), absent facets vanish, empty map is nil"
      (is (= "value = false" (binding-preview names {:value false})))
      (is (nil? (binding-preview names {}))))))


(deftest entry-preview-per-entity-type-test
  (let [r #uuid "aaaaaaaa-1111-4111-8111-111111111111"]
    (testing ":fn previews its description, when present"
      (is (= "“hello”" (entry-preview {} {:entity-name :fn
                                          :source-version {:description "hello"}})))
      (is (nil? (entry-preview {} {:entity-name :fn :source-version {}}))))
    (testing ":fn-slot previews its position"
      (is (= "at position 2"
             (entry-preview {} {:entity-name :fn-slot
                                :source-version {:position 2}}))))
    (testing ":binding-list-item joins value and ref arrow"
      (is (= "10 → :handler"
             (entry-preview {r "handler"}
                            {:entity-name :binding-list-item
                             :source-version {:value 10 :ref-fn-id r}}))))
    (testing ":resource-override is just its served path"
      (is (= "/editor.css"
             (entry-preview {} {:entity-name :resource-override
                                :source-version {:path "/editor.css"}}))))
    (testing "unknown entity types answer nil"
      (is (nil? (entry-preview {} {:entity-name :mystery
                                   :source-version {:value 1}}))))))


;; === entry assembly =========================================================

(deftest entry-modified-carries-fields-not-preview-test
  (let [eid #uuid "cccccccc-3333-4333-8333-333333333333"
        sid #uuid "dddddddd-4444-4444-8444-444444444444"
        slots {sid {:id sid :name "port"}}
        e (entry {} slots {}
                 {:entity-name :binding :entity-id eid :change :modified
                  :source-version {:slot-id sid :value 9090}
                  :target-version {:slot-id sid :value 8080}})]
    (is (= {:entity-name :binding
            :entity-id (str eid)
            :change :modified
            :slot-name "port"
            :fields [{:field "value" :source "9090" :target "8080"}]}
           e))
    (is (not (contains? e :preview)))))


(deftest entry-one-sided-carries-preview-not-fields-test
  (let [eid #uuid "cccccccc-3333-4333-8333-333333333333"
        bid #uuid "eeeeeeee-5555-4555-8555-555555555555"
        sid #uuid "dddddddd-4444-4444-8444-444444444444"
        e (entry {} {sid {:name "nums"}} {bid {:slot-id sid}}
                 {:entity-name :binding-list-item :entity-id eid
                  :change :added-in-source
                  :source-version {:binding-id bid :position 3 :value 10}
                  :target-version nil})]
    (is (= {:entity-name :binding-list-item
            :entity-id (str eid)
            :change :added-in-source
            :slot-name "nums"
            :position 3
            :preview "10"}
           e))
    (is (not (contains? e :fields)))))


;; === resolve-fn-names =======================================================

(deftest resolve-fn-names-source-first-target-fallback-test
  (let [src-b #uuid "00000000-0000-4000-8000-00000000000a"
        tgt-b #uuid "00000000-0000-4000-8000-00000000000b"
        named #uuid "11111111-1111-4111-8111-111111111111"
        anon #uuid "22222222-2222-4222-8222-222222222222"
        tgt-only #uuid "33333333-3333-4333-8333-333333333333"
        calls (atom [])]
    (with-redefs [mrg/batch-resolve
                  (fn [_ {ids :fn} branch-id]
                    (swap! calls conj [branch-id ids])
                    (cond
                      (= branch-id src-b)
                      ;; `named` resolves with a name; `anon` resolves
                      ;; but IS anonymous; `tgt-only` doesn't resolve.
                      (cond-> {}
                        (ids named) (assoc [:fn named] {:name "alpha"})
                        (ids anon) (assoc [:fn anon] {:name nil}))
                      (= branch-id tgt-b)
                      (cond-> {}
                        (ids tgt-only) (assoc [:fn tgt-only]
                                              {:name "beta"}))))]
      (let [names (resolve-fn-names :stub [named anon tgt-only] src-b tgt-b)]
        (testing "source names win; target fills only what source lacked"
          (is (= {named "alpha" tgt-only "beta"} names)))
        (testing "an fn RESOLVED-but-anonymous on source is not re-queried
                  on target — only the fully-missing id is"
          (is (= [[src-b #{named anon tgt-only}]
                  [tgt-b #{tgt-only}]]
                 @calls)))))
    (testing "no ids means no queries at all"
      (is (= {} (resolve-fn-names :stub [] src-b tgt-b))))))


;; === diff-branches-view =====================================================

(defn- read-only-storage
  "Protocol stub for `diff-branches-view`'s two `sp/read-entities`
   pre-reads. Everything else must not run."
  [tables]
  (reify
    sp/StorageCRUD
    (query-entities
      [_ entity-name where]
      (if (and (= :ns entity-name) (empty? where))
        (vals (get tables :ns {}))
        (throw (AssertionError. (str "query-entities " entity-name)))))

    (create-entity [_ _ _] (throw (AssertionError. "create-entity")))

    (read-entity [_ _ _] (throw (AssertionError. "read-entity")))

    (update-entity [_ _ _ _] (throw (AssertionError. "update-entity")))

    (delete-entity [_ _ _] (throw (AssertionError. "delete-entity")))

    (query-latest-per-group
      [_ _ _ _]
      (throw (AssertionError. "query-latest-per-group")))


    sp/StorageBatchCRUD

    (read-entities
      [_ entity-name ids]
      (select-keys (get tables entity-name) ids))

    (create-entities [_ _ _] (throw (AssertionError. "create-entities")))

    (update-entities [_ _ _] (throw (AssertionError. "update-entities")))

    (upsert-entities [_ _ _] (throw (AssertionError. "upsert-entities")))

    (delete-entities [_ _ _] (throw (AssertionError. "delete-entities")))

    (query-ref-many-owners
      [_ _ _ _]
      (throw (AssertionError. "query-ref-many-owners")))))


(deftest diff-branches-view-groups-labels-and-orders-test
  (let [src-b #uuid "00000000-0000-4000-8000-00000000000a"
        tgt-b #uuid "00000000-0000-4000-8000-00000000000b"
        fn-a #uuid "11111111-1111-4111-8111-111111111111"
        fn-anon #uuid "99999999-9999-4999-8999-999999999999"
        ref-x #uuid "44444444-4444-4444-8444-444444444444"
        s1 #uuid "dddddddd-4444-4444-8444-444444444444"
        b1 #uuid "eeeeeeee-5555-4555-8555-555555555555"
        fs1 #uuid "55555555-5555-4555-8555-555555555555"
        li1 #uuid "66666666-6666-4666-8666-666666666666"
        ro1 #uuid "77777777-7777-4777-8777-777777777777"
        diffs [{:entity-name :binding-list-item :entity-id li1
                :change :added-in-source
                :source-version {:binding-id b1 :position 3 :value 10}
                :target-version nil}
               {:entity-name :binding :entity-id b1 :change :modified
                :source-version {:fn-id fn-a :slot-id s1 :value 9090
                                 :ref-fn-id ref-x}
                :target-version {:fn-id fn-a :slot-id s1 :value 8080
                                 :ref-fn-id nil}}
               {:entity-name :fn-slot :entity-id fs1
                :change :added-in-source
                :source-version {:fn-id fn-a :slot-id s1 :position 2}
                :target-version nil}
               {:entity-name :fn :entity-id fn-a :change :modified
                :source-version {:name "alpha" :description "new"}
                :target-version {:name "alpha" :description "old"}}
               {:entity-name :fn :entity-id fn-anon
                :change :added-in-source
                :source-version {:name nil}
                :target-version nil}
               {:entity-name :resource-override :entity-id ro1
                :change :added-in-source
                :source-version {:path "/editor.css"}
                :target-version nil}]
        ns-web #uuid "0000000a-0000-4000-8000-0000000000aa"
        ns-http #uuid "0000000b-0000-4000-8000-0000000000bb"
        storage (read-only-storage
                  {:binding {b1 {:id b1 :fn-id fn-a :slot-id s1}}
                   :slot {s1 {:id s1 :name "port"}}
                   :fn {fn-a {:id fn-a :name "alpha" :namespace-id ns-http}
                        fn-anon {:id fn-anon :name nil}}
                   :ns {ns-web {:id ns-web :name "web" :parent-id nil}
                        ns-http {:id ns-http :name "http" :parent-id ns-web}}})
        view (with-redefs [mrg/diff-branches
                           (fn [_ s t]
                             (is (= [src-b tgt-b] [s t]))
                             {:source-branch-id s :target-branch-id t
                              :diffs diffs})

                           mrg/batch-resolve
                           (fn [_ {ids :fn} branch-id]
                             (into {}
                                   (keep (fn [id]
                                           (cond
                                             (and (= id fn-a) (= branch-id src-b))
                                             [[:fn id] {:name "alpha"}]
                                             ;; ref-x resolves only on target.
                                             (and (= id ref-x) (= branch-id tgt-b))
                                             [[:fn id] {:name "handler"}]
                                             ;; anon fn resolves namelessly.
                                             (= id fn-anon)
                                             [[:fn id] {:name nil}])))
                                   ids))

                           bl/effective-branch-local?
                           (fn [_ fn-id] (= fn-id fn-a))]
               (dv/diff-branches-view storage src-b tgt-b))
        [alpha-g assets-g anon-g :as groups] (:groups view)]
    (testing "envelope: branch ids + total row count"
      (is (= src-b (:source-branch-id view)))
      (is (= tgt-b (:target-branch-id view)))
      (is (= 6 (:count view))))

    (testing "group order: named fns first, then ownerless before
              anonymous (sort key name-presence / name / id)"
      (is (= 3 (count groups)))
      (is (= [(str fn-a) nil (str fn-anon)] (mapv :fn-id groups))))

    (testing "the group carries its owning fn's namespace PATH — the
              Explorer can't derive it for compared-branch-only fns"
      (is (= "web.http" (:ns-path alpha-g)))
      (is (nil? (:ns-path anon-g)) "no fn row → no path"))

    (testing "named owner group: label, own change, branch-local flag"
      (is (= "alpha" (:fn-name alpha-g)))
      (is (= ":alpha" (:fn-label alpha-g)))
      (is (= :modified (:change alpha-g)))
      (is (true? (:branch-local? alpha-g))))

    (testing "entries rank fn-row → fn-slot → binding → list-item"
      (is (= [:fn :fn-slot :binding :binding-list-item]
             (mapv :entity-name (:entries alpha-g)))))

    (testing "modified fn row pairs before/after per changed field"
      (is (= [{:field "description" :source "new" :target "old"}]
             (:fields (first (:entries alpha-g))))))

    (testing "slot names resolve through the pre-read slot rows, and a
              ref-fn-id resolved only on the TARGET branch still names"
      (let [binding-e (nth (:entries alpha-g) 2)]
        (is (= "port" (:slot-name binding-e)))
        (is (= [{:field "ref-fn-id" :source ":handler" :target "∅"}
                {:field "value" :source "9090" :target "8080"}]
               (:fields binding-e)))))

    (testing "list item resolves its slot through the binding pre-read"
      (let [li-e (nth (:entries alpha-g) 3)]
        (is (= "port" (:slot-name li-e)))
        (is (= 3 (:position li-e)))
        (is (= "10" (:preview li-e)))))

    (testing "ownerless asset overrides group under the (assets) label,
              defaulting to :modified (no fn row of their own)"
      (is (= "(assets)" (:fn-label assets-g)))
      (is (nil? (:fn-name assets-g)))
      (is (= :modified (:change assets-g)))
      (is (false? (:branch-local? assets-g)))
      (is (= ["/editor.css"] (mapv :preview (:entries assets-g)))))

    (testing "anonymous fn group falls back to a short-id label and takes
              the fn row's own change"
      (is (nil? (:fn-name anon-g)))
      (is (= "#99999999" (:fn-label anon-g)))
      (is (= :added-in-source (:change anon-g)))
      (is (false? (:branch-local? anon-g))))))
