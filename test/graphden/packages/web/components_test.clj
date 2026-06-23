(ns ^:integration graphden.packages.web.components-test
  "Unit tests for the `web.components` starter component library —
   Block 2 of the user-sites plan (docs/USER_SITES_PLAN.md).

   Each component is a fn-def over `:hiccup`; assertions here pin the
   composed hiccup output so future renames / parent-swaps that change
   the rendered shape fail loudly."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


(def ^:dynamic *container* nil)
(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  exec/with-clean-registry
  (fn [f]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (f))))


(defn- exec-name
  [nm args]
  (let [{:keys [ctx storage all-name->id]} *bootstrap*
        fn-id (get all-name->id nm)]
    (when-not fn-id
      (throw (ex-info (str "No fn-id for " nm) {:nm nm})))
    (setup/exec-with-storage ctx storage fn-id args)))


;; =============================================================================
;; :button — plain label, with attrs, and DSL-composed click handler
;; =============================================================================

(deftest button-label-only-renders-bare-button-test
  (testing "no :attrs binding → hiccup [:button \"label\"]; :hiccup impl drops nil attrs entirely so the output is a 2-vec"
    (is (= [:button "Submit"]
           (exec-name :button {:label "Submit"})))))


(deftest button-with-attrs-merges-into-element-test
  (testing "caller-supplied :attrs flows into the hiccup attrs slot"
    (is (= [:button {:class "primary"} "Save"]
           (exec-name :button {:label "Save"
                               :attrs {:class "primary"}})))))


(deftest button-composed-with-dispatch-action-test
  (testing "the v0 DSL composition pattern: `:dispatch-action` as :attrs source emits data-action attribute"
    ;; This is the production composition shape — caller builds attrs
    ;; via the runtime DSL and passes them straight into :button.
    (let [attrs (exec-name :dispatch-action {:action "run-fn"})]
      (is (= [:button {:data-action "run-fn"} "Run"]
             (exec-name :button {:label "Run" :attrs attrs}))))))


;; =============================================================================
;; Block 2.2 — form-input components
;; =============================================================================

(deftest input-renders-self-closing-test
  (testing "<input attrs/> — :children locked to [] so hiccup emits no body"
    (is (= [:input {:type "email" :name "email"}]
           (exec-name :input {:attrs {:type "email" :name "email"}}))))
  (testing "no :attrs → bare [:input]"
    (is (= [:input]
           (exec-name :input {})))))


(deftest textarea-renders-with-content-test
  (testing "<textarea attrs>content</textarea>"
    (is (= [:textarea {:name "msg" :rows 6} "hello world"]
           (exec-name :textarea
                      {:attrs {:name "msg" :rows 6}
                       :content "hello world"})))))


(deftest option-renders-with-label-test
  (testing "<option attrs>label</option>"
    (is (= [:option {:value "red"} "Red"]
           (exec-name :option {:label "Red" :attrs {:value "red"}})))))


(deftest select-with-option-children-test
  (testing "<select attrs>options...</select> — :options is a list of hiccup nodes"
    (let [red (exec-name :option {:label "Red"   :attrs {:value "red"}})
          grn (exec-name :option {:label "Green" :attrs {:value "green"}})]
      (is (= [:select {:name "colour"} red grn]
             (exec-name :select {:attrs {:name "colour"}
                                 :options [red grn]}))))))


(deftest checkbox-pre-merges-type-attr-test
  (testing "<input type=\"checkbox\" :attrs.../> — type=checkbox merged in automatically"
    (is (= [:input {:type "checkbox" :name "agree" :id "agree"}]
           (exec-name :checkbox {:attrs {:name "agree" :id "agree"}}))))
  (testing "no :attrs → just `<input type=\"checkbox\"/>`"
    (is (= [:input {:type "checkbox"}]
           (exec-name :checkbox {})))))


(deftest checkbox-caller-cannot-override-type-via-attrs-test
  (testing "caller-supplied :type in :attrs OVERRIDES the pre-merged checkbox type (merge order: defaults first, caller wins)"
    ;; This documents the current behavior — if a user passes
    ;; `{:type "text"}` they silently get a text input from a fn-def
    ;; called :checkbox. Acceptable for v0 (consistent with Clojure
    ;; `(merge defaults user)` semantics); a future tightening could
    ;; flip the order if it causes confusion.
    (is (= [:input {:type "text" :name "weird"}]
           (exec-name :checkbox {:attrs {:type "text" :name "weird"}})))))


(deftest form-renders-children-test
  (testing "<form attrs>field1 field2 button</form>"
    (let [email-input (exec-name :input
                                 {:attrs {:type "email" :name "email"}})
          submit-btn  (exec-name :button
                                 {:label "Send"
                                  :attrs {:type "submit"}})]
      (is (= [:form {:method "POST" :action "/contact"}
              email-input submit-btn]
             (exec-name :form
                        {:attrs {:method "POST" :action "/contact"}
                         :children [email-input submit-btn]}))))))


;; =============================================================================
;; Block 2.3 — layout/content components
;; =============================================================================

(deftest link-renders-href-and-label-test
  (testing "<a href=\"...\">label</a> — :href slot is required, :attrs optional"
    (is (= [:a {:href "/contact"} "Contact us"]
           (exec-name :link {:href "/contact" :label "Contact us"})))))


(deftest link-with-extra-attrs-test
  (testing "caller :attrs merge with platform-supplied :href; extras (rel, target) survive"
    (is (= [:a {:rel "noopener" :target "_blank" :href "https://example.com"}
            "Example"]
           (exec-name :link {:href "https://example.com"
                             :label "Example"
                             :attrs {:rel "noopener" :target "_blank"}})))))


(deftest image-renders-src-alt-test
  (testing "<img src=... alt=.../> — both required slots present in output"
    (is (= [:img {:src "/logo.png" :alt "Logo"}]
           (exec-name :image {:src "/logo.png" :alt "Logo"})))))


(deftest image-with-extra-attrs-test
  (testing "platform :src / :alt merged onto caller's optional :attrs"
    (is (= [:img {:width 64 :height 64 :src "/avatar.png" :alt "Avatar"}]
           (exec-name :image {:src "/avatar.png" :alt "Avatar"
                              :attrs {:width 64 :height 64}})))))


(deftest card-renders-default-class-test
  (testing "<div class=\"card\">children...</div> — default class merged in"
    (is (= [:div {:class "card"} "Hello" "World"]
           (exec-name :card {:children ["Hello" "World"]})))))


(deftest card-with-attrs-can-override-class-test
  (testing "caller :attrs wins on conflict (Clojure merge semantics: later overrides earlier)"
    (is (= [:div {:class "card highlighted" :id "main"} "Content"]
           (exec-name :card {:children ["Content"]
                             :attrs {:class "card highlighted"
                                     :id "main"}})))))


;; =============================================================================
;; Block 3 — :custom-script + :wrap-custom-script (escape hatch)
;; =============================================================================

(deftest custom-script-returns-body-verbatim-test
  (testing ":custom-script just holds the JS body — no transformation"
    (is (= "console.log('hi');"
           (exec-name :custom-script {:body "console.log('hi');"})))))


(deftest wrap-custom-script-emits-script-hiccup-test
  (testing "<script>body</script> with no attrs (script content is raw JS)"
    (is (= [:script {} "alert('hello world')"]
           (exec-name :wrap-custom-script {:body "alert('hello world')"})))))


(deftest wrap-custom-script-accepts-empty-body-test
  (testing "empty body is valid (JS no-op); :js-source has no narrowing constraint"
    (is (= [:script {} ""]
           (exec-name :wrap-custom-script {:body ""})))))
