(ns ^:integration graphden.regressions.inline-script-sibling-drop-test
  "Regression test for the 2026-06-23 editor-page bug:

   When the editor page's `:_editor-scripts` list had an inline
   `<script>` (`:_editor-api-routes-script-tag` carrying the
   `window.API = {…}` JS body) BEFORE the `<script src=\"/assets/
   editor.js\">` sibling, the second tag was silently dropped
   from the rendered HTML. The editor never bootstrapped, the
   page came up blank with no console error.

   Standalone hiccup2 + clojure.walk/postwalk on the same shape
   render both siblings correctly (see /tmp/repro.clj scratch
   used during diagnosis). The bug lives somewhere graphden-
   specific between fn-def execution and the hiccup output —
   likely in the executor's compile-runtime handling of `:list`
   items that are fn-refs returning hiccup elements where one
   has raw string children. ROOT CAUSE NOT FULLY ISOLATED — the
   ship workaround was to serve the cached `window.API` JS as a
   separate `/assets/api-routes.js` static asset, sidestepping
   the inline-content path.

   This test pins the SYMPTOM at the editor-page level: the
   rendered HTML must contain BOTH `<script src=\"/assets/
   editor.js\">` AND `<script src=\"/assets/api-routes.js\">`.
   If a future change re-introduces an inline-script-first
   pattern (or otherwise drops these tags), this test fails
   immediately rather than waiting for a browser smoke."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


(def ^:dynamic *container* nil)
(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  ;; A shared precondition belongs in the shared place — every namespace
  ;; that needs the build-hashes file states this fixture itself.
  setup/ensure-build-hashes-fixture
  (pth/create-container-fixture #'*container*)
  exec/with-clean-registry
  (fn [f]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (f))))


(defn- render-editor-page
  "Execute the `:_editor-rendered-page` fn-def and return the
   rendered HTML string. Mirrors the production page-render path
   through the executor (`:_editor-handler` then wraps this string
   in a Ring response, which we skip — the string IS the test
   surface)."
  []
  (let [{:keys [ctx storage all-name->id]} *bootstrap*
        fn-id (get all-name->id :_editor-rendered-page)]
    (when-not fn-id
      (throw (ex-info "No :_editor-rendered-page fn-id in bootstrap"
                      {:available-keys (take 30 (keys all-name->id))})))
    (setup/exec-with-storage ctx storage fn-id {})))


(deftest editor-page-renders-editor-js-script-tag-test
  (testing "editor.js <script src> tag must appear — otherwise the editor never bootstraps and the page is blank"
    (let [html (render-editor-page)]
      (is (str/includes? html "src=\"/assets/editor.js")
          "the editor.js <script src> tag is the single load-bearing line — its absence is the 2026-06-23 blank-page regression"))))


(deftest editor-page-renders-api-routes-script-tag-test
  (testing "api-routes.js <script src> tag must appear — otherwise window.API is undefined and every editor module that resolves URLs through it fails at file-eval time"
    (let [html (render-editor-page)]
      (is (str/includes? html "src=\"/assets/api-routes.js")
          "the api-routes.js <script src> tag carries the boot-cached `window.API`; missing it = silent NPEs in every editor JS file"))))


(deftest editor-page-renders-editor-css-link-test
  (testing "editor.css <link> in <head> — page renders unstyled without it"
    (let [html (render-editor-page)]
      (is (str/includes? html "href=\"/assets/editor.css")
          "the editor.css link is the only stylesheet load"))))


(deftest editor-page-script-order-is-stable-test
  (testing "api-routes.js MUST come before editor.js so window.API is defined before any editor module reads it"
    (let [html (render-editor-page)
          api-routes-idx (str/index-of html "src=\"/assets/api-routes.js")
          editor-js-idx  (str/index-of html "src=\"/assets/editor.js")]
      (is (and api-routes-idx editor-js-idx
               (< api-routes-idx editor-js-idx))
          (str "api-routes.js (idx " api-routes-idx ") must precede editor.js "
               "(idx " editor-js-idx ") — file-eval order matters for window.API access")))))


(deftest editor-page-has-no-double-load-test
  (testing "No accidental duplicate <script src> for the same asset (would re-execute the bundle / re-overwrite window.API)"
    (let [html (render-editor-page)
          editor-js-count (count (re-seq #"src=\"/assets/editor\.js" html))
          api-routes-count (count (re-seq #"src=\"/assets/api-routes\.js" html))]
      (is (= 1 editor-js-count)
          (str "editor.js should appear once, got " editor-js-count))
      (is (= 1 api-routes-count)
          (str "api-routes.js should appear once, got " api-routes-count)))))
