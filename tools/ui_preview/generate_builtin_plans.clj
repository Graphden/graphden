;; Run from the repository root:
;; clojure -M:dev tools/ui_preview/generate_builtin_plans.clj
(load-file "tools/ui_preview/builtin_plans.clj")
(apply (resolve 'tools.ui-preview.builtin-plans/-main) *command-line-args*)
