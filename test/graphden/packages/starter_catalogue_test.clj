(ns graphden.packages.starter-catalogue-test
  "The starter catalogue (docs/MARKETPLACE.md § 11): the shipped file is
   well-formed against the editor's vocabularies, `seed!` publishes it
   once as public + approved rows the cards / storefront list, every
   fns entry installs and runs, and a name another org holds publicly
   is never squatted."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.starter-catalogue :as starter]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [t]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!
                            "starter-catalogue-test" ["core" "web" "app" "registry" "mcp"])]
      (t))))


(defn- storage
  []
  (:storage *bootstrap*))


(defn- run-named
  [fn-name args]
  (let [{:keys [ctx all-name->id]} *bootstrap*]
    (exec/execute-with-named-args ctx (get all-name->id fn-name) args)))


(defn- editor-file
  [basename]
  (slurp (io/resource (str "packages/app/editor/" basename))))


(defn- theme-token-names
  "The allow-listed custom properties `gdThemeTokens` names — parsed from
   the `THEME_TOKENS` table in editor-prefs.js, the same list the apply
   path keeps and Settings → Appearance edits."
  []
  (->> (re-seq #"\['[^']+', '(--[a-z0-9-]+)', '[^']*'\]" (editor-file "editor-prefs.js"))
       (map second)
       set))


(defn- shortcut-ids
  "The binding ids `editor-shortcuts.js` registers — what a keymap may
   override."
  []
  (->> (re-seq #"id: '([a-z-]+)'" (editor-file "editor-shortcuts.js"))
       (map second)
       set))


(defn- category-vocab
  "`:listing-categories` for `kind` — the const's map keys arrive
   keywordised through jsonb, so look both ways."
  [kind]
  (let [v (run-named :listing-categories {})]
    (set (or (get v kind) (get v (keyword kind))))))


(def ^:private colour-re #"^(#[0-9a-fA-F]{3,8}|rgba?\(.*\)|hsla?\(.*\))$")


(deftest catalogue-is-well-formed
  (let [catalogue (starter/read-catalogue)
        tokens (theme-token-names)
        ids (shortcut-ids)]
    (is (>= (count catalogue) 3) "a catalogue with something in it")
    (is (pos? (count tokens)) "the token allow-list was found in editor-prefs.js")
    (is (pos? (count ids)) "the shortcut registry was found in editor-shortcuts.js")
    (testing "every kind is listed, each (name, version) once, listing fields present"
      (is (= #{"theme" "keymap" "fns"} (set (map :kind catalogue))))
      (is (apply distinct? (map (juxt :name :version) catalogue)))
      (doseq [{:keys [kind name version description category tags]} catalogue]
        (is (re-matches #"[a-z0-9.-]+" name) (str name ": a registry name"))
        (is (re-matches #"\d+\.\d+\.\d+" version) (str name ": semver"))
        (is (seq description) (str name ": described"))
        (is (contains? (category-vocab kind) category)
            (str name ": category " category " is in the " kind " vocabulary"))
        (is (and (vector? tags) (every? #(re-matches #"[a-z0-9-]+" %) tags) (<= (count tags) 10))
            (str name ": tags are normalised"))))
    (testing "themes use allow-listed tokens with colour literals, a mode and a scale in range"
      (doseq [{:keys [name payload]} (filter #(= "theme" (:kind %)) catalogue)]
        (is (contains? #{"light" "dark"} (:mode payload)) (str name ": mode"))
        (is (<= 70 (:scale payload 100) 160) (str name ": scale"))
        (doseq [[token value] (:tokens payload)]
          (is (contains? tokens token) (str name ": token " token " is allow-listed"))
          (is (re-matches colour-re value) (str name ": " token " is a colour literal")))
        (is (<= 20 (count (:tokens payload))) (str name ": a whole palette, not a tint"))))
    (testing "keymaps override registered bindings only, each with a key sequence"
      (doseq [{:keys [name payload]} (filter #(= "keymap" (:kind %)) catalogue)]
        (is (seq (:bindings payload)) (str name ": has overrides"))
        (doseq [[id {key-seq :keys leader :leader}] (:bindings payload)]
          (is (contains? ids id) (str name ": binding " id " is registered"))
          (is (re-matches #"\S+( \S+){0,2}" key-seq) (str name ": " id " keys are 1-3 tokens"))
          (is (boolean? leader) (str name ": " id " says leader or bare")))
        (is (apply distinct? (map (fn [[_ b]] [(:keys b) (:leader b)]) (:bindings payload)))
            (str name ": no two commands on one sequence"))))
    (testing "fns entries carry a root, defs and declared dependencies"
      (doseq [{:keys [name ns-root fns dependencies]} (filter #(= "fns" (:kind %)) catalogue)]
        (is (= ns-root name) (str name ": the package is rooted at its own name"))
        (is (seq fns) (str name ": has fn-defs"))
        (is (every? keyword? dependencies) (str name ": dependencies are fn names"))))))


(deftest seed-publishes-the-catalogue-once
  (let [catalogue (starter/read-catalogue)
        names (set (map :name catalogue))
        first-run (starter/seed! (storage))]
    (testing "every entry is in the registry after a run (published now, or by a sibling test's run)"
      (is (= (count catalogue) (+ (count (:published first-run)) (count (:exists first-run)))))
      (is (empty? (:held first-run))))
    (testing "the rows are the platform's public, approved listings"
      (doseq [{:keys [kind name version category tags payload]} catalogue]
        (let [row (first (sp/query-entities (storage) :package-version {:name name :version version}))]
          (is (some? row) (str name "@" version " is in the registry"))
          (is (true? (:public? row)))
          (is (= "approved" (:status row)))
          (is (= kind (:kind row)))
          (is (= category (:category row)))
          (is (= tags (:tags row)))
          (is (= tc/public-org (:org-id row)) "published in the platform tier")
          (when payload
            (is (= (:mode payload) (:mode (:payload row))))))))
    (testing "a second run adds nothing"
      (let [again (starter/seed! (storage))]
        (is (empty? (:published again)))
        (is (= (count catalogue) (count (:exists again))))
        (doseq [{:keys [name version]} catalogue]
          (is (= 1 (count (sp/query-entities (storage) :package-version {:name name :version version})))))))
    (testing "the marketplace cards list them, per kind"
      (doseq [kind ["theme" "keymap" "fns"]]
        (let [resp (setup/via-graph *bootstrap* :_mk-index-handler
                                    {:request-method :get :query-params {"kind" kind} :headers {}})
              cards (json/parse-string (:body resp) true)
              expected (set (map :name (filter #(= kind (:kind %)) catalogue)))]
          (is (= 200 (:status resp)))
          (is (= expected (set (filter expected (map :name cards))))
              (str "every " kind " entry has a card")))))
    (testing "the anonymous storefront lists them too (public + approved)"
      (is (= names (set (filter names (map :name (run-named :storefront-rows {})))))))))


(def ^:private smoke
  "What each shipped fns package must do once installed — keyed by
   package name; `catalogue-fns-packages-install-and-run` refuses a
   package without an entry here. `:fn` is executed with `:args` under
   the version-qualified namespace; `:check` sees the result."
  {"starter.text"
   [{:fn :slugify :args {:text "Hello, World!  Again"} :check #(= "hello-world-again" %)}
    {:fn :slugify :args {:text "--Already--slug--"} :check #(= "already-slug" %)}
    {:fn :word-count :args {:text "  the quick brown\tfox "} :check #(= 4 %)}
    {:fn :word-count :args {:text "   "} :check zero?}]
   "starter.numbers"
   [{:fn :mean :args {:values [2 4 9]} :check #(== 5 %)}
    {:fn :percent-of :args {:part 1 :whole 3} :check #(== 33.3 %)}]
   "starter.hello-api"
   [{:fn :_hello-body :args {:request {:query-params {"name" "graphden"}}}
     :check #(= {"hello" "graphden"} (into {} (map (fn [[k v]] [(name k) v])) %))}
    {:fn :_hello-body :args {:request {:query-params {}}}
     :check #(= {"hello" "world"} (into {} (map (fn [[k v]] [(name k) v])) %))}
    {:fn :hello-route :args {} :check #(= "/hello" (first %))}]})


(deftest catalogue-fns-packages-install-and-run
  (starter/seed! (storage))
  (let [{:keys [ctx all-name->id]} *bootstrap*
        install-id (get all-name->id :install-package)]
    (doseq [{:keys [name version]} (filter #(= "fns" (:kind %)) (starter/read-catalogue))]
      (testing (str name " installs by reference")
        (let [r (exec/execute-with-named-args ctx install-id {:pkg-name name :pkg-version version})]
          (is (true? (:ok r)) (pr-str r))))
      (testing (str name " runs")
        (let [checks (get smoke name)
              vns (str name "@" (str/replace version "." "-"))]
          (is (seq checks) (str "no smoke checks for " name " — add them to `smoke`"))
          (doseq [{fn-name :fn :keys [args check]} checks]
            (let [result (exec/execute-with-named-args ctx (ids/fn-id vns fn-name) args)]
              (is (check result) (str name "/" (clojure.core/name fn-name) " " (pr-str args) " → " (pr-str result))))))))))


(deftest seed-leaves-a-name-another-org-holds-publicly
  (let [other-org (str (random-uuid))
        theme (first (filter #(= "theme" (:kind %)) (starter/read-catalogue)))
        entry (assoc theme :name "graphden.held-by-a-tenant" :version "1.0.0")]
    (tc/with-org other-org
                 (sp/create-entity (storage) :package-version
                                   {:name "graphden.held-by-a-tenant" :version "0.9.0" :ns-root "" :fns []
                                    :dependencies [] :kind "theme" :public? true :status "approved"
                                    :content-hash "held" :org-id other-org}))
    (let [outcome (starter/seed! (storage) [entry])]
      (is (= ["graphden.held-by-a-tenant@1.0.0"] (:held outcome)))
      (is (empty? (:published outcome)))
      (is (empty? (sp/query-entities (storage) :package-version
                                     {:name "graphden.held-by-a-tenant" :version "1.0.0"}))
          "the platform did not publish over a tenant's public name"))))
