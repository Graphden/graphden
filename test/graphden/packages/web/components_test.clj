(ns ^:integration graphden.packages.web.components-test
  "Unit tests for the `web.components` starter component library.

   Each component is a fn-def over `:hiccup`; assertions here pin
   the composed hiccup output so future renames / parent-swaps
   that change the rendered shape fail loudly."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.test-infra.graph-harness :as gh]))


(def ^:dynamic *container* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  (gh/graph-fixture (str (ns-name *ns*))))


;; =============================================================================
;; :button — plain label, with attrs, and DSL-composed click handler
;; =============================================================================

(deftest button-label-only-renders-bare-button-test
  (testing "no :attrs binding → hiccup [:button \"label\"]; :hiccup impl drops nil attrs entirely so the output is a 2-vec"
    (is (= [:button "Submit"]
           (gh/exec-name :button {:label "Submit"})))))


(deftest button-with-attrs-merges-into-element-test
  (testing "caller-supplied :attrs flows into the hiccup attrs slot"
    (is (= [:button {:class "primary"} "Save"]
           (gh/exec-name :button {:label "Save"
                                  :attrs {:class "primary"}})))))


(deftest button-composed-with-dispatch-action-test
  (testing "the v0 DSL composition pattern: `:dispatch-action` as :attrs source emits data-action attribute"
    ;; This is the production composition shape — caller builds attrs
    ;; via the runtime DSL and passes them straight into :button.
    (let [attrs (gh/exec-name :dispatch-action {:action "run-fn"})]
      (is (= [:button {:data-action "run-fn"} "Run"]
             (gh/exec-name :button {:label "Run" :attrs attrs}))))))


;; =============================================================================
;; Form-input components
;; =============================================================================

(deftest input-renders-self-closing-test
  (testing "<input attrs/> — :children locked to [] so hiccup emits no body"
    (is (= [:input {:type "email" :name "email"}]
           (gh/exec-name :input {:attrs {:type "email" :name "email"}}))))
  (testing "no :attrs → bare [:input]"
    (is (= [:input]
           (gh/exec-name :input {})))))


(deftest textarea-renders-with-content-test
  (testing "<textarea attrs>content</textarea>"
    (is (= [:textarea {:name "msg" :rows 6} "hello world"]
           (gh/exec-name :textarea
                         {:attrs {:name "msg" :rows 6}
                          :content "hello world"})))))


(deftest option-renders-with-label-test
  (testing "<option attrs>label</option>"
    (is (= [:option {:value "red"} "Red"]
           (gh/exec-name :option {:label "Red" :attrs {:value "red"}})))))


(deftest select-with-option-children-test
  (testing "<select attrs>options...</select> — :options is a list of hiccup nodes"
    (let [red (gh/exec-name :option {:label "Red"   :attrs {:value "red"}})
          grn (gh/exec-name :option {:label "Green" :attrs {:value "green"}})]
      (is (= [:select {:name "colour"} red grn]
             (gh/exec-name :select {:attrs {:name "colour"}
                                    :options [red grn]}))))))


(deftest checkbox-pre-merges-type-attr-test
  (testing "<input type=\"checkbox\" :attrs.../> — type=checkbox merged in automatically"
    (is (= [:input {:type "checkbox" :name "agree" :id "agree"}]
           (gh/exec-name :checkbox {:attrs {:name "agree" :id "agree"}}))))
  (testing "no :attrs → just `<input type=\"checkbox\"/>`"
    (is (= [:input {:type "checkbox"}]
           (gh/exec-name :checkbox {})))))


(deftest checkbox-caller-cannot-override-type-via-attrs-test
  (testing "caller-supplied :type in :attrs OVERRIDES the pre-merged checkbox type (merge order: defaults first, caller wins)"
    ;; This documents the current behavior — if a user passes
    ;; `{:type "text"}` they silently get a text input from a fn-def
    ;; called :checkbox. Acceptable for v0 (consistent with Clojure
    ;; `(merge defaults user)` semantics); a future tightening could
    ;; flip the order if it causes confusion.
    (is (= [:input {:type "text" :name "weird"}]
           (gh/exec-name :checkbox {:attrs {:type "text" :name "weird"}})))))


(deftest form-renders-children-test
  (testing "<form attrs>field1 field2 button</form>"
    (let [email-input (gh/exec-name :input
                                    {:attrs {:type "email" :name "email"}})
          submit-btn  (gh/exec-name :button
                                    {:label "Send"
                                     :attrs {:type "submit"}})]
      (is (= [:form {:method "POST" :action "/contact"}
              email-input submit-btn]
             (gh/exec-name :form
                           {:attrs {:method "POST" :action "/contact"}
                            :children [email-input submit-btn]}))))))


;; =============================================================================
;; Layout/content components
;; =============================================================================

(deftest link-renders-href-and-label-test
  (testing "<a href=\"...\">label</a> — :href slot is required, :attrs optional"
    (is (= [:a {:href "/contact"} "Contact us"]
           (gh/exec-name :link {:href "/contact" :label "Contact us"})))))


(deftest link-with-extra-attrs-test
  (testing "caller :attrs merge with platform-supplied :href; extras (rel, target) survive"
    (is (= [:a {:rel "noopener" :target "_blank" :href "https://example.com"}
            "Example"]
           (gh/exec-name :link {:href "https://example.com"
                                :label "Example"
                                :attrs {:rel "noopener" :target "_blank"}})))))


(deftest image-renders-src-alt-test
  (testing "<img src=... alt=.../> — both required slots present in output"
    (is (= [:img {:src "/logo.png" :alt "Logo"}]
           (gh/exec-name :image {:src "/logo.png" :alt "Logo"})))))


(deftest image-with-extra-attrs-test
  (testing "platform :src / :alt merged onto caller's optional :attrs"
    (is (= [:img {:width 64 :height 64 :src "/avatar.png" :alt "Avatar"}]
           (gh/exec-name :image {:src "/avatar.png" :alt "Avatar"
                                 :attrs {:width 64 :height 64}})))))


(deftest card-renders-default-class-test
  (testing "<div class=\"card\">children...</div> — default class merged in"
    (is (= [:div {:class "card"} "Hello" "World"]
           (gh/exec-name :card {:children ["Hello" "World"]})))))


(deftest card-with-attrs-can-override-class-test
  (testing "caller :attrs wins on conflict (Clojure merge semantics: later overrides earlier)"
    (is (= [:div {:class "card highlighted" :id "main"} "Content"]
           (gh/exec-name :card {:children ["Content"]
                                :attrs {:class "card highlighted"
                                        :id "main"}})))))


;; =============================================================================
;; :custom-script + :wrap-custom-script (escape hatch)
;; =============================================================================

(deftest custom-script-returns-body-verbatim-test
  (testing ":custom-script just holds the JS body — no transformation"
    (is (= "console.log('hi');"
           (gh/exec-name :custom-script {:body "console.log('hi');"})))))


(deftest wrap-custom-script-emits-script-hiccup-test
  (testing "<script>body</script> with no attrs (script content is raw JS)"
    (is (= [:script {} "alert('hello world')"]
           (gh/exec-name :wrap-custom-script {:body "alert('hello world')"})))))


(deftest wrap-custom-script-accepts-empty-body-test
  (testing "empty body is valid (JS no-op); :js-source has no narrowing constraint"
    (is (= [:script {} ""]
           (gh/exec-name :wrap-custom-script {:body ""})))))


;; =============================================================================
;; Convenience button templates — :submit-button :click-button :navigate-button :custom-button
;; =============================================================================

(deftest submit-button-emits-submit-form-attrs-test
  (testing "submit-button collapses the dispatch-action + type='submit' merge"
    (is (= [:button {:data-action "submit-form" :type "submit"} "Send"]
           (gh/exec-name :submit-button {:label "Send" :extras {}})))))


(deftest submit-button-with-extras-merges-data-target-test
  (testing "caller :extras land on the button; merge order = caller wins on conflict"
    (is (= [:button {:data-action "submit-form" :type "submit" :data-target "#result"} "Save"]
           (gh/exec-name :submit-button
                         {:label "Save"
                          :extras {:data-target "#result"}})))))


(deftest click-button-routes-to-arbitrary-action-test
  (testing "click-button picks the action name from :action"
    (is (= [:button {:data-action "run-fn"} "Run"]
           (gh/exec-name :click-button
                         {:label "Run" :action "run-fn" :extras {}})))))


(deftest navigate-button-emits-data-href-test
  (testing "navigate-button wires the navigate handler with :href"
    (is (= [:button {:data-action "navigate" :data-href "/about"} "About"]
           (gh/exec-name :navigate-button
                         {:label "About" :href "/about" :extras {}})))))


(deftest custom-button-wires-inline-js-body-test
  (testing "custom-button wires the custom handler with the inline JS :body"
    (is (= [:button {:data-action "custom" :data-custom-handler "alert('hi')"} "Greet"]
           (gh/exec-name :custom-button
                         {:label "Greet" :body "alert('hi')" :extras {}})))))


;; =============================================================================
;; Text + layout set — :heading / :paragraph / :stack / :row / :nav-bar /
;; lists / tables / :field-label
;; =============================================================================

(deftest heading-level-picks-tag-test
  (testing "the :level slot computes the h1..h6 tag"
    (is (= [:h1 "Title"] (gh/exec-name :heading {:level 1 :content "Title"})))
    (is (= [:h3 "Sub"] (gh/exec-name :heading {:level 3 :content "Sub"})))))


(deftest paragraph-renders-children-test
  (testing "<p> with mixed string + inline-element children"
    (let [a (gh/exec-name :link {:href "/x" :label "x"})]
      (is (= [:p "before " a " after"]
             (gh/exec-name :paragraph {:children ["before " a " after"]}))))))


(deftest stack-and-row-premerge-their-class-test
  (testing ".stack default class, caller attrs win on conflict"
    (is (= [:div {:class "stack"} "a" "b"]
           (gh/exec-name :stack {:children ["a" "b"]})))
    (is (= [:div {:class "stack wide"} "a"]
           (gh/exec-name :stack {:children ["a"] :attrs {:class "stack wide"}}))))
  (testing ".row default class"
    (is (= [:div {:class "row"} "a" "b"]
           (gh/exec-name :row {:children ["a" "b"]})))))


(deftest nav-bar-and-lists-render-test
  (is (= [:nav "l1" "l2"] (gh/exec-name :nav-bar {:children ["l1" "l2"]})))
  (let [li (gh/exec-name :list-item {:children ["one"]})]
    (is (= [:li "one"] li))
    (is (= [:ul li li] (gh/exec-name :unordered-list {:children [li li]})))))


(deftest table-composes-from-rows-and-cells-test
  (let [th (gh/exec-name :table-header-cell {:children ["Name"]})
        td (gh/exec-name :table-cell {:children ["Ada"]})
        hr (gh/exec-name :table-row {:children [th]})
        dr (gh/exec-name :table-row {:children [td]})]
    (is (= [:th "Name"] th))
    (is (= [:td "Ada"] td))
    (is (= [:table [:tr th] [:tr td]]
           (gh/exec-name :table {:children [hr dr]})))))


(deftest field-label-renders-with-for-attr-test
  (is (= [:label {:for "email"} "Email"]
         (gh/exec-name :field-label {:children ["Email"]
                                     :attrs {:for "email"}}))))


;; =============================================================================
;; CSS escape hatch — :custom-stylesheet / :wrap-custom-style +
;; the app.page :stylesheet-handler serving it as text/css
;; =============================================================================

(deftest custom-stylesheet-and-wrap-style-test
  (testing "the const holder returns the CSS body verbatim"
    (is (= "body { margin: 0; }"
           (gh/exec-name :custom-stylesheet {:body "body { margin: 0; }"}))))
  (testing "wrap-custom-style renders an inline <style> hiccup"
    (let [styled (gh/exec-name :wrap-custom-style {:body ".x { color: red; }"})]
      (is (= :style (first styled)))
      (is (some #(= ".x { color: red; }" (str %)) (flatten [styled]))))))


(deftest stylesheet-handler-serves-text-css-test
  (testing "app.page :stylesheet-handler → 200 text/css Ring response, no cache directives"
    (let [r (gh/exec-name :stylesheet-handler {:css "body { margin: 0; }"})
          ;; header keys keywordize on the JSONB round trip — accept
          ;; either form (http-kit stringifies at the boundary).
          header (fn [k kw] (or (get-in r [:headers k]) (get-in r [:headers kw])))]
      (is (= 200 (:status r)))
      (is (= "text/css; charset=utf-8" (header "Content-Type" :Content-Type)))
      (is (nil? (header "Cache-Control" :Cache-Control)))
      (is (= "body { margin: 0; }" (:body r))))))
