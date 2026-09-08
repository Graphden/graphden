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
    [graphden.storage.protocol.core :as sp]))


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
    (let [html (:body (setup/via-graph *bootstrap* :_partial-marketplace-handler (get-req {"kind" "executor"})))]
      (is (re-find #"mk-roster" html))
      (is (re-find #"<td>core</td>" html))
      (is (re-find #"impl\+fns" html))
      (is (not (re-find #"mk-search" html)) "no search on a roster")))
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
  (let [roster (loaded/read-roster)]
    (is (seq roster) "the golden bootstrap loaded packages")
    (let [core-row (first (filter #(= "core" (:name %)) roster))]
      (is (= "impl+fns" (:kind core-row)))
      (is (= "bundled" (:origin core-row)))
      (is (pos? (:base-fn-count core-row))))
    (is (= "fns-only" (:kind (loaded/roster-entry {:name "x" :modules ["m"]} 0 false))))
    (is (= "manifest" (:origin (loaded/roster-entry {:name "x"} 3 true))))))
