(ns graphden.packages.marketplace-test
  "The marketplace over the registry (docs/MARKETPLACE.md): listing
   normalisation, the non-fns publish route, cards (latest / versions /
   rating / installs), reviews, apply-a-theme → preference, the install
   counter, and the two partials — all through the graph handlers."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.loaded :as loaded]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.deploy-config :as deploy-config]
    [graphden.tenancy.context :as tc]))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [t]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!
                            "marketplace-test" ["core" "web" "app" "registry" "mcp"])]
      (t))))


(defn- storage
  []
  (:storage *bootstrap*))


(defn- json-req
  [body]
  {:request-method :post
   :body (json/generate-string body)
   :headers {"content-type" "application/json"}})


(defn- form-req
  [fields]
  {:request-method :post
   :body (str/join "&" (for [[k v] fields] (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8"))))
   :headers {"content-type" "application/x-www-form-urlencoded"}})


(defn- get-req
  [params]
  {:request-method :get :query-params params :headers {}})


(defn- run-named
  [fn-name args]
  (let [{:keys [ctx all-name->id]} *bootstrap*]
    (exec/execute-with-named-args ctx (get all-name->id fn-name) args)))


(defn- body-json
  [resp]
  (json/parse-string (:body resp) true))


(def ^:private notified
  "What the notification seam received — `[event payload]` pairs."
  (atom []))


(def ^:private theme-payload
  {:mode "dark" :tokens {"--gd-paper" "#101214" "--gd-ink" "#e6e9e8"} :fonts {:ui "Inter"} :scale 110})


(deftest listing-normalize-vocabulary
  (testing "kind defaults to fns; tags parse from a comma string — lower-cased, cleaned, distinct, capped"
    (let [r (run-named :listing-normalize
                       {:kind nil :description "  Hello  " :category "Web"
                        :tags "Web, web, Data-Sync, bad tag!, , a,b,c,d,e,f,g,h,i" :payload nil})]
      (is (= "fns" (:kind r)))
      (is (= "Hello" (:description r)))
      (is (= "web" (:category r)))
      (is (= ["web" "data-sync" "badtag" "a" "b" "c" "d" "e" "f" "g"] (:tags r))
          "10 at most, after cleaning + dedupe")
      (is (nil? (:error r)))))
  (testing "a category outside the kind's vocabulary is refused"
    (is (= "bad-category" (:error (run-named :listing-normalize
                                             {:kind "theme" :description nil :category "web"
                                              :tags nil :payload nil})))))
  (testing "an unknown kind is refused"
    (is (= "bad-kind" (:error (run-named :listing-normalize
                                         {:kind "plugin" :description nil :category nil
                                          :tags nil :payload nil})))))
  (testing "a blank description / category are nil, a blank tag list is empty"
    (let [r (run-named :listing-normalize {:kind "keymap" :description "  " :category "" :tags "" :payload nil})]
      (is (nil? (:description r)))
      (is (nil? (:category r)))
      (is (= [] (:tags r))))))


(deftest publish-theme-and-keymap
  (testing "POST /api/marketplace/publish stores a theme with its listing + payload"
    (let [resp (setup/via-graph *bootstrap* :_mkp-handler
                                (json-req {:kind "theme" :name "night-ink" :version "1.0.0"
                                           :description "A dark drafting board" :category "dark"
                                           :tags "Dark, Cozy" :public true :payload theme-payload}))
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (true? (:ok body)) (pr-str body))
      (is (= "theme" (:kind body)))
      (let [row (first (sp/query-entities (storage) :package-version {:name "night-ink"}))]
        (is (= "theme" (:kind row)))
        (is (= "A dark drafting board" (:description row)))
        (is (= "dark" (:category row)))
        (is (= ["dark" "cozy"] (:tags row)))
        (is (= "dark" (get-in row [:payload :mode])))
        (is (= "#101214" (get-in row [:payload :tokens (keyword "--gd-paper")]))
            "the token map round-trips through jsonb (keys keywordised)")
        (is (= [] (:fns row)) "a theme carries no fn-defs")
        (is (= "" (:ns-root row))))))
  (testing "the same (name, version) again is refused — immutable"
    (is (= "version-exists"
           (:reason (body-json (setup/via-graph *bootstrap* :_mkp-handler
                                                (json-req {:kind "theme" :name "night-ink" :version "1.0.0"
                                                           :payload theme-payload})))))))
  (testing "names are registry-wide: another org may not publish under a publicly listed name, public or private"
    (tc/install-org-cap-fn! (fn [cap] (= cap :publish-packages)))
    (try
      (binding [tc/*current-org* "rival-org"]
        (is (= "name-taken"
               (:reason (body-json (setup/via-graph *bootstrap* :_mkp-handler
                                                    (json-req {:kind "theme" :name "night-ink" :version "2.0.0"
                                                               :public true :payload theme-payload}))))))
        (is (= "name-taken"
               (:reason (body-json (setup/via-graph *bootstrap* :_mkp-handler
                                                    (json-req {:kind "theme" :name "night-ink" :version "2.0.0"
                                                               :payload theme-payload})))))
            "a private version under the public name is refused too — one org per name in every catalog")
        (testing "a private name is no claim: another org lists it publicly"
          (is (true? (:ok (body-json (setup/via-graph *bootstrap* :_mkp-handler
                                                      (json-req {:kind "theme" :name "rival.private" :version "1.0.0"
                                                                 :payload theme-payload}))))))))
      ;; (a fresh version: `(name, version)` is checked across the whole
      ;; registry here — the DB-level UNIQUE hardening is the follow-up)
      (is (true? (:ok (body-json (setup/via-graph *bootstrap* :_mkp-handler
                                                  (json-req {:kind "theme" :name "rival.private" :version "2.0.0"
                                                             :public true :payload theme-payload}))))))
      (is (empty? (filter #(= "2.0.0" (:version %)) (sp/query-entities (storage) :package-version {:name "night-ink"})))
          "nothing written under the taken name")
      (finally (tc/install-org-cap-fn! nil))))
  (testing "refusals: fns kind (a namespace export), missing payload, bad category"
    (is (= "unsupported-kind"
           (:reason (body-json (setup/via-graph *bootstrap* :_mkp-handler
                                                (json-req {:kind "fns" :name "x" :version "1.0.0" :payload {:a 1}}))))))
    (is (= "missing-payload"
           (:reason (body-json (setup/via-graph *bootstrap* :_mkp-handler
                                                (json-req {:kind "keymap" :name "x" :version "1.0.0"}))))))
    (is (= "bad-category"
           (:reason (body-json (setup/via-graph *bootstrap* :_mkp-handler
                                                (json-req {:kind "keymap" :name "x" :version "1.0.0"
                                                           :category "dark" :payload {:bindings {}}}))))))
    (is (empty? (sp/query-entities (storage) :package-version {:name "x"})) "nothing written"))
  (testing "a keymap publishes with its bindings"
    (let [body (body-json (setup/via-graph *bootstrap* :_mkp-handler
                                           (json-req {:kind "keymap" :name "vimish" :version "0.1.0"
                                                      :category "vim-like"
                                                      :payload {:bindings {:graph-fit {:keys "z z" :leader true}}}})))]
      (is (true? (:ok body)))
      (is (= "z z" (get-in (first (sp/query-entities (storage) :package-version {:name "vimish"}))
                           [:payload :bindings :graph-fit :keys]))))))


(deftest namespace-publish-carries-listing
  (testing "POST /api/packages/publish with description / category / tags stores them on the fns row"
    (let [body (body-json (setup/via-graph *bootstrap* :publish-package-handler
                                           (json-req {:name "mk.demo" :version "1.0.0" :ns-root "app.contact-demo"
                                                      :description "Contact form demo" :category "examples"
                                                      :tags "Forms, demo"})))]
      (is (true? (:ok body)) (pr-str body))
      (let [row (first (sp/query-entities (storage) :package-version {:name "mk.demo"}))]
        (is (= "fns" (:kind row)) "the normaliser pins the kind explicitly")
        (is (= "examples" (:category row)))
        (is (= ["forms" "demo"] (:tags row)))
        (is (= "Contact form demo" (:description row))))))
  (testing "a fns publish with a theme category is refused before the export is written"
    (let [body (body-json (setup/via-graph *bootstrap* :publish-package-handler
                                           (json-req {:name "mk.demo" :version "1.1.0" :ns-root "app.contact-demo"
                                                      :category "dark"})))]
      (is (= "bad-category" (:reason body)))
      (is (= 1 (count (sp/query-entities (storage) :package-version {:name "mk.demo"})))))))


(defn- cards
  [params]
  (body-json (setup/via-graph *bootstrap* :_mk-index-handler (get-req params))))


(deftest cards-reviews-and-filters
  (doseq [v ["1.0.0" "1.2.0" "1.10.0"]]
    (sp/create-entity (storage) :package-version
                      {:name "cards.pkg" :version v :ns-root "cards.pkg" :fns [{:name :c}]
                       :dependencies [] :content-hash (str "c" v) :kind "fns"
                       :description "Cards under test" :category "data" :tags ["alpha" "beta"]}))
  (sp/create-entity (storage) :package-version
                    {:name "cards.other" :version "2.0.0" :ns-root "cards.other" :fns [{:name :o}]
                     :dependencies [] :content-hash "co" :category "web" :tags ["beta"]})
  (testing "one card per name; versions newest first by semver, not by string"
    (let [card (first (filter #(= "cards.pkg" (:name %)) (cards {"kind" "fns" "sort" "name"})))]
      (is (some? card))
      (is (= "1.10.0" (:latest card)))
      (is (= ["1.10.0" "1.2.0" "1.0.0"] (:versions card)))
      (is (= 3 (:version-count card)))
      (is (= "data" (:category card)))
      (is (= ["alpha" "beta"] (:tags card)))
      (is (nil? (:rating card)) "no reviews yet")
      (is (zero? (:installs card)))))
  (testing "a review posts, aggregates, updates in place (one per author), and deletes"
    (let [resp (setup/via-graph *bootstrap* :_mka-review-handler
                                (form-req {:name "cards.pkg" :rating 4 :body "Solid."}))]
      (is (= 200 (:status resp)))
      (is (re-find #"data-marketplace-item=\"cards.pkg\"" (:body resp)) "the item re-renders")
      (is (re-find #"Solid\." (:body resp)))
      (is (re-find #"Update review" (:body resp)) "the form now offers an update"))
    (let [card (first (filter #(= "cards.pkg" (:name %)) (cards {"kind" "fns"})))]
      (is (= {:count 1 :avg 4.0} (:rating card))))
    (setup/via-graph *bootstrap* :_mka-review-handler (form-req {:name "cards.pkg" :rating 2 :body "Hmm."}))
    (let [rows (sp/query-entities (storage) :package-review {:package-name "cards.pkg"})
          card (first (filter #(= "cards.pkg" (:name %)) (cards {"kind" "fns"})))]
      (is (= 1 (count rows)) "the same author's second review REPLACES the first")
      (is (= 2 (:rating (first rows))))
      (is (= "anonymous" (:author-id (first rows))) "the author is stamped server-side")
      (is (true? (:public? (first rows))))
      (is (= {:count 1 :avg 2.0} (:rating card))))
    (testing "a rating outside 1–5 is refused and writes nothing"
      (let [resp (setup/via-graph *bootstrap* :_mka-review-handler (form-req {:name "cards.pkg" :rating 9}))]
        (is (re-find #"pick a rating" (:body resp)))
        (is (= 1 (count (sp/query-entities (storage) :package-review {:package-name "cards.pkg"}))))))
    (let [resp (setup/via-graph *bootstrap* :_mka-review-delete-handler
                                {:request-method :delete :query-params {"name" "cards.pkg"} :headers {}})]
      (is (re-find #"review is deleted" (:body resp)))
      (is (empty? (sp/query-entities (storage) :package-review {:package-name "cards.pkg"})))))
  (testing "filters: category, tag, search"
    (is (= ["cards.pkg"] (map :name (filter #(str/starts-with? (:name %) "cards.") (cards {"kind" "fns" "category" "data"})))))
    (is (= #{"cards.pkg" "cards.other"}
           (set (map :name (filter #(str/starts-with? (:name %) "cards.") (cards {"kind" "fns" "tag" "beta"}))))))
    (is (= ["cards.pkg"] (map :name (filter #(str/starts-with? (:name %) "cards.") (cards {"kind" "fns" "q" "UNDER test"})))))
    (is (empty? (filter #(str/starts-with? (:name %) "cards.") (cards {"kind" "theme"}))) "kind tabs partition")))


(deftest install-counts-and-apply
  (sp/create-entity (storage) :package-version
                    {:name "cnt.pkg" :version "1.0.0" :ns-root "cnt.demo"
                     :fns [{:name :cnt-greeting :namespace "cnt.demo" :parent :const :args {:value "hi"}}]
                     :dependencies [:const] :content-hash "cnt"})
  (testing "a NEW pin bumps the global install counter; re-pinning the same branch does not"
    (let [resp (setup/via-graph *bootstrap* :_mka-install-handler
                                {:request-method :post :query-params {"name" "cnt.pkg" "version" "1.0.0"} :headers {}})]
      (is (= 200 (:status resp)))
      (is (re-find #"Installed on this branch" (:body resp)))
      (is (re-find #"mk-current" (:body resp)) "the version row is marked installed"))
    (is (= 1 (:installs (first (sp/query-entities (storage) :package-stat {:package-name "cnt.pkg"})))))
    (setup/via-graph *bootstrap* :_mka-install-handler
                     {:request-method :post :query-params {"name" "cnt.pkg" "version" "1.0.0"} :headers {}})
    (is (= 1 (:installs (first (sp/query-entities (storage) :package-stat {:package-name "cnt.pkg"})))))
    (is (= 1 (:installs (first (filter #(= "cnt.pkg" (:name %)) (cards {"kind" "fns"})))))
        "the card reads the counter"))
  (testing "applying a theme writes the user's preference with the payload copied"
    (setup/via-graph *bootstrap* :_mkp-handler
                     (json-req {:kind "theme" :name "apply-me" :version "1.0.0" :payload theme-payload}))
    (let [resp (setup/via-graph *bootstrap* :_mka-apply-handler
                                {:request-method :post :query-params {"name" "apply-me" "version" "1.0.0"} :headers {}})]
      (is (re-find #"Applied" (:body resp)))
      (is (re-find #"mk-current" (:body resp)) "the version row is marked active"))
    (let [row (first (sp/query-entities (storage) :ui-pref {:key "theme"}))]
      (is (= "anonymous" (:owner-id row)))
      (is (= {:name "apply-me" :version "1.0.0"} (get-in row [:value :source])))
      (is (= "dark" (get-in row [:value :payload :mode]))))
    (testing "GET /api/prefs returns it"
      (let [body (body-json (setup/via-graph *bootstrap* :_prefs-get-handler {:request-method :get :headers {}}))]
        (is (= "apply-me" (get-in body [:theme :source :name])))))
    (testing "applying a fns package is refused"
      (is (re-find #"Refused" (:body (setup/via-graph *bootstrap* :_mka-apply-handler
                                                      {:request-method :post :query-params {"name" "cnt.pkg" "version" "1.0.0"} :headers {}})))))))


(deftest prefs-put-route
  (testing "PUT /api/prefs/:key stores a known key and refuses an unknown one"
    (let [resp (setup/via-graph *bootstrap* :_pref-put-handler
                                (assoc (json-req {:value {:bindings {:branches {:keys "x" :leader false}}}})
                                       :request-method :put
                                       :path-params {:key "keymap"}))]
      (is (= 200 (:status resp)))
      (is (true? (:ok (body-json resp))))
      (is (= "x" (get-in (first (sp/query-entities (storage) :ui-pref {:key "keymap"}))
                         [:value :bindings :branches :keys]))))
    (let [resp (setup/via-graph *bootstrap* :_pref-put-handler
                                (assoc (json-req {:value 1}) :request-method :put :path-params {:key "colour"}))]
      (is (= 400 (:status resp)))
      (is (= "unknown-key" (:reason (body-json resp)))))
    (let [resp (setup/via-graph *bootstrap* :_pref-put-handler
                                (assoc (json-req {:value (str/join (repeat 70000 "x"))})
                                       :request-method :put :path-params {:key "theme"}))]
      (is (= 413 (:status resp))))))


(defn- with-fixture-roster
  "Run `f` against a KNOWN loaded-package roster and put the previous one
   back. The roster is a process global that `:app/packages`' halt clears —
   a test that boots and halts a system (there are several) would otherwise
   decide what the Executor tab shows here, depending on run order."
  [f]
  (let [before (loaded/read-roster)]
    (loaded/install! {:packages [{:name "core" :version "1.0.0" :description "Core primitives"
                                  :modules ["arithmetic" "logic"] :dependencies []}
                                 {:name "registry" :version "1.0.0" :description "The registry"
                                  :modules ["registry" "marketplace"] :dependencies ["core"]}]
                      :base-fn-counts {"core" 163}})
    (try (f)
         (finally
           (loaded/install! {:packages (mapv #(select-keys % [:name :version :description :modules :dependencies]) before)
                             :base-fn-counts (into {} (map (juxt :name :base-fn-count)) before)})))))


(deftest partials-render
  (sp/create-entity (storage) :package-version
                    {:name "part.theme" :version "1.0.0" :ns-root "" :fns [] :dependencies []
                     :content-hash "pt" :kind "theme" :category "light" :tags ["airy"]
                     :description "Light and airy" :payload theme-payload})
  (testing "the listing partial: toolbar tabs + a card with its listing"
    (let [resp (setup/via-graph *bootstrap* :_partial-marketplace-handler (get-req {"kind" "theme"}))
          html (:body resp)]
      (is (= 200 (:status resp)))
      (is (re-find #"data-marketplace=\"1\"" html))
      (is (re-find #"data-mk-kind=\"theme\"" html))
      (is (re-find #"<input[^>]*checked[^>]*value=\"theme\"|<input[^>]*value=\"theme\"[^>]*checked" html) "the Themes tab is checked")
      (is (re-find #"data-mk-card=\"part.theme\"" html))
      (is (re-find #"Light and airy" html))
      (is (re-find #"mk-tag[^>]*hx-get=\"/partials/marketplace\?kind=theme&amp;tag=airy\"" html) "tag chip links the filter")
      (is (re-find #"no reviews yet" html))
      (is (re-find #"<option[^>]*selected[^>]*value=\"light\"|<option[^>]*value=\"light\"[^>]*selected" (:body (setup/via-graph *bootstrap* :_partial-marketplace-handler
                                                                                                                                (get-req {"kind" "theme" "category" "light"}))))
          "the category select keeps the selection")))
  (testing "the Executor tab lists the loaded package roster"
    (with-fixture-roster
      #(let [html (:body (setup/via-graph *bootstrap* :_partial-marketplace-handler (get-req {"kind" "executor"})))]
         (is (re-find #"mk-roster" html))
         (is (re-find #"<td>core</td>" html))
         (is (re-find #"<td>registry</td>" html))
         (is (re-find #"impl\+fns" html))
         (is (re-find #"fns-only" html) "a package without impls")
         (is (not (re-find #"mk-search" html)) "no search on a roster"))))
  (testing "the item partial: versions with Apply, the review form"
    (let [html (:body (setup/via-graph *bootstrap* :_partial-marketplace-item-handler (get-req {"name" "part.theme"})))]
      (is (re-find #"data-marketplace-item=\"part.theme\"" html))
      (is (re-find #"hx-post=\"/api/marketplace/apply\?name=part.theme&amp;version=1.0.0\"" html))
      (is (not (re-find #"mk-install\"" html)) "a theme has no Install (the `mk-installs` counter is not it)")
      (is (re-find #"hx-post=\"/api/marketplace/review\"" html))
      (is (re-find #"Post review" html))
      (is (re-find #"hx-get=\"/partials/marketplace\?kind=theme\"" html) "back to the Themes tab")))
  (testing "an unknown package renders the not-found notice, not an error"
    (let [resp (setup/via-graph *bootstrap* :_partial-marketplace-item-handler (get-req {"name" "no.such"}))]
      (is (= 200 (:status resp)))
      (is (re-find #"No such package" (:body resp))))))


(deftest loaded-roster-shape
  (with-fixture-roster
    (fn []
      (let [roster (loaded/read-roster)
            by-name (into {} (map (juxt :name identity)) roster)]
        (is (= ["core" "registry"] (mapv :name roster)) "load order, one row per package")
        (is (= "impl+fns" (:kind (by-name "core"))))
        (is (= "bundled" (:origin (by-name "core"))))
        (is (= 163 (:base-fn-count (by-name "core"))))
        (is (= "fns-only" (:kind (by-name "registry")))))))
  (is (= "fns-only" (:kind (loaded/roster-entry {:name "x" :modules ["m"]} 0 false))))
  (is (= "manifest" (:origin (loaded/roster-entry {:name "x"} 3 true)))))


(deftest mirrors-show-the-origin-and-take-no-review
  ;; docs/MARKETPLACE.md § 7: the origin registry is the one authority for a
  ;; package's reviews. A mirrored copy shows the origin's rating / installs
  ;; (its own local reviews never count) and refuses a local review.
  (sp/create-entity (storage) :package-version
                    {:name "mir.pkg" :version "1.0.0" :ns-root "mir.demo" :fns [{:name :m}]
                     :dependencies [] :content-hash "mh" :description "Mirrored thing"
                     :origin {:url "https://hub.example" :rating {:count 5 :avg 4.2} :installs 40
                              :as-of "2026-09-08T10:00:00Z"}})
  (testing "the card carries the origin's signals"
    (let [card (first (filter #(= "mir.pkg" (:name %)) (cards {"kind" "fns"})))]
      (is (= {:count 5 :avg 4.2} (:rating card)))
      (is (= 40 (:installs card)))
      (is (= "https://hub.example" (get-in card [:origin :url])))))
  (testing "the item links to the origin instead of offering a review form"
    (let [html (:body (setup/via-graph *bootstrap* :_partial-marketplace-item-handler (get-req {"name" "mir.pkg"})))]
      (is (re-find #"mk-origin-note" html))
      (is (re-find #"href=\"https://hub.example\"" html))
      (is (re-find #"as of 2026-09-08" html))
      (is (not (re-find #"mk-review-form" html)) "no local review form on a mirror")
      (is (not (re-find #"mk-listing-form" html)) "no listing edit on a mirror")
      (is (re-find #"mirror of https://hub.example" (:body (setup/via-graph *bootstrap* :_partial-marketplace-handler (get-req {"kind" "fns" "q" "mir.pkg"}))))
          "the card wears the mirror badge")))
  (testing "a local review on a mirror is refused and writes nothing"
    (let [resp (setup/via-graph *bootstrap* :_mka-review-handler (form-req {:name "mir.pkg" :rating 5 :body "nope"}))]
      (is (re-find #"reviews are written there" (:body resp)))
      (is (empty? (sp/query-entities (storage) :package-review {:package-name "mir.pkg"})))))
  (testing "kind=any lists every kind (what a remote mirror asks)"
    (is (some #(= "mir.pkg" (:name %)) (cards {"kind" "any"})))))


(deftest listing-edit-on-own-latest-version
  (doseq [v ["1.0.0" "1.1.0"]]
    (sp/create-entity (storage) :package-version
                      {:name "lst.pkg" :version v :ns-root "lst.demo" :fns [{:name :l}]
                       :dependencies [] :content-hash (str "l" v) :description "old" :category "web"
                       :org-id "public"}))
  (testing "the item shows the publisher's listing form, and a save updates the LATEST version only"
    (let [html (:body (setup/via-graph *bootstrap* :_partial-marketplace-item-handler (get-req {"name" "lst.pkg"})))]
      (is (re-find #"mk-listing-form" html))
      (is (re-find #"<option[^>]*selected[^>]*value=\"web\"|<option[^>]*value=\"web\"[^>]*selected" html) "the current category is selected"))
    (let [resp (setup/via-graph *bootstrap* :_mkl-handler
                                (form-req {:name "lst.pkg" :description "New words" :category "data" :tags "Fresh, tag"}))
          rows (into {} (map (juxt :version identity)) (sp/query-entities (storage) :package-version {:name "lst.pkg"}))]
      (is (re-find #"Listing saved" (:body resp)))
      (is (= "New words" (:description (get rows "1.1.0"))))
      (is (= "data" (:category (get rows "1.1.0"))))
      (is (= ["fresh" "tag"] (:tags (get rows "1.1.0"))))
      (is (= "old" (:description (get rows "1.0.0"))) "older versions keep their listing")
      (is (= "New words" (:description (first (filter #(= "lst.pkg" (:name %)) (cards {"kind" "fns"}))))) "the card shows the latest")))
  (testing "a category outside the vocabulary is refused"
    (let [resp (setup/via-graph *bootstrap* :_mkl-handler (form-req {:name "lst.pkg" :description "x" :category "dark"}))]
      (is (re-find #"pick a category" (:body resp)))
      (is (= "New words" (:description (first (filter #(= "1.1.0" (:version %)) (sp/query-entities (storage) :package-version {:name "lst.pkg"}))))))))
  (testing "another org's package is not editable"
    (binding [tc/*current-org* "someone-else"]
      (let [resp (setup/via-graph *bootstrap* :_mkl-handler (form-req {:name "lst.pkg" :description "hijack"}))]
        (is (re-find #"only the publisher" (:body resp)))))
    (is (= "New words" (:description (first (filter #(= "1.1.0" (:version %)) (sp/query-entities (storage) :package-version {:name "lst.pkg"}))))))))


(deftest moderation-of-public-listings
  ;; docs/MARKETPLACE.md § 8. With GRAPHDEN_MARKETPLACE_MODERATION on, a
  ;; TENANT's public opt-in lands `pending`; the platform's own publish is
  ;; approved outright; the operator approves / rejects from the queue.
  (deploy-config/install! {:marketplace-moderation "1"})
  ;; a tenant org (non-platform tier) holding the publish right, as the
  ;; addon would grant it
  (tc/install-org-cap-fn! (fn [cap] (= cap :publish-packages)))
  ;; the notification seam — the addon's mailer stands here in prod
  (tc/install-notify-fn! (fn [event payload] (swap! notified conj [event payload])))
  (try
    (testing "a tenant's public theme waits for review"
      (let [body (binding [tc/*current-org* "acme-mod"]
                   (body-json (setup/via-graph *bootstrap* :_mkp-handler
                                               (json-req {:kind "theme" :name "mod.theme" :version "1.0.0"
                                                          :public true :payload theme-payload}))))]
        (is (true? (:ok body)))
        (is (= "pending" (:status body)))
        (is (= "pending" (:status (first (sp/query-entities (storage) :package-version {:name "mod.theme"})))))))
    (testing "a private publish and the platform's own publish are approved at once"
      (binding [tc/*current-org* "acme-mod"]
        (setup/via-graph *bootstrap* :_mkp-handler
                         (json-req {:kind "theme" :name "mod.private" :version "1.0.0" :payload theme-payload})))
      (is (= "approved" (:status (first (sp/query-entities (storage) :package-version {:name "mod.private"})))))
      (setup/via-graph *bootstrap* :_mkp-handler
                       (json-req {:kind "theme" :name "mod.platform" :version "1.0.0" :public true :payload theme-payload}))
      (is (= "approved" (:status (first (sp/query-entities (storage) :package-version {:name "mod.platform"}))))))
    (testing "the publisher's card and item carry the pending mark"
      (binding [tc/*current-org* "acme-mod"]
        (is (= "pending" (:status (first (filter #(= "mod.theme" (:name %)) (cards {"kind" "theme"}))))))
        (let [html (:body (setup/via-graph *bootstrap* :_partial-marketplace-handler (get-req {"kind" "theme" "q" "mod.theme"})))]
          (is (re-find #"mk-status-pending" html)))
        (is (re-find #"Awaiting the operator" (:body (setup/via-graph *bootstrap* :_partial-marketplace-item-handler (get-req {"name" "mod.theme"})))))))
    (testing "the queue and the decision are platform-admin only"
      (is (thrown-with-msg? Exception #"platform-admin"
            (run-named :moderation-queue {})))
      (tc/install-platform-admin-fn! (constantly true))
      (try
        (let [queue (run-named :moderation-queue {})]
          (is (= ["mod.theme"] (map :name queue)) "only the pending row is queued")
          (is (= "acme-mod" (:org-id (first queue)))))
        (let [html (:body (setup/via-graph *bootstrap* :_partial-moderation-queue-handler {:request-method :get :headers {}}))]
          (is (re-find #"data-mq-name=\"mod.theme\"" html))
          (is (re-find #"mq-approve" html))
          (is (re-find #"name=\"note\"" html) "the reject form carries a note field"))
        (testing "reject with a note — the publisher sees it"
          (let [resp (setup/via-graph *bootstrap* :_mq-decide-handler
                                      (form-req {:name "mod.theme" :version "1.0.0" :decision "reject" :note "Too dark to read"}))]
            (is (re-find #"Nothing awaiting review" (:body resp)) "the queue empties")
            (let [row (first (sp/query-entities (storage) :package-version {:name "mod.theme"}))]
              (is (= "rejected" (:status row)))
              (is (= "Too dark to read" (:moderation-note row)))
              (is (some? (:moderated-at row))))
            (testing "the decision is raised through the notification seam with the updated row"
              (let [[event row] (last @notified)]
                (is (= :package-moderated event))
                (is (= ["mod.theme" "1.0.0" "rejected" "Too dark to read" "acme-mod"]
                       [(:name row) (:version row) (:status row) (:moderation-note row) (:org-id row)]))))
            (binding [tc/*current-org* "acme-mod"]
              (is (re-find #"declined the public listing: Too dark to read"
                           (:body (setup/via-graph *bootstrap* :_partial-marketplace-item-handler (get-req {"name" "mod.theme"}))))))))
        (testing "approve"
          (binding [tc/*current-org* "acme-mod"]
            (setup/via-graph *bootstrap* :_mkp-handler
                             (json-req {:kind "theme" :name "mod.theme" :version "1.0.1" :public true :payload theme-payload})))
          (setup/via-graph *bootstrap* :_mq-decide-handler
                           (form-req {:name "mod.theme" :version "1.0.1" :decision "approve"}))
          (is (= "approved" (:status (first (filter #(= "1.0.1" (:version %)) (sp/query-entities (storage) :package-version {:name "mod.theme"}))))))
          (is (nil? (:moderation-note (first (filter #(= "1.0.1" (:version %)) (sp/query-entities (storage) :package-version {:name "mod.theme"})))))))
        (testing "an unknown decision changes nothing"
          (setup/via-graph *bootstrap* :_mq-decide-handler
                           (form-req {:name "mod.theme" :version "1.0.1" :decision "maybe"}))
          (is (= "approved" (:status (first (filter #(= "1.0.1" (:version %)) (sp/query-entities (storage) :package-version {:name "mod.theme"})))))))
        (finally (tc/install-platform-admin-fn! nil))))
    (testing "with moderation off, a public publish is approved outright"
      (deploy-config/install! {})
      (binding [tc/*current-org* "acme-mod"]
        (setup/via-graph *bootstrap* :_mkp-handler
                         (json-req {:kind "theme" :name "mod.open" :version "1.0.0" :public true :payload theme-payload})))
      (is (= "approved" (:status (first (sp/query-entities (storage) :package-version {:name "mod.open"}))))))
    (finally
      (deploy-config/install! {})
      (tc/install-notify-fn! nil)
      (tc/install-org-cap-fn! nil))))
