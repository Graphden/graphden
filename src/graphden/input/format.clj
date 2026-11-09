(ns graphden.input.format
  "Version boundary shared by the offline reader and the legacy loader.")


(defn require-v2!
  [module]
  (when-not (and (map? module) (= 2 (:input-format module)))
    (throw (ex-info "Structured source requires input format 2"
                    {:type :input/invalid-module :code :input-format-2-required}))))


(defn require-legacy!
  "Reject unsupported headers before the legacy loader validates or executes
   anything in the module. An absent header and explicit version 1 are v1."
  [module path]
  (when (and (map? module) (contains? module :input-format)
             (not= 1 (:input-format module)))
    (throw (ex-info "Module input format is not supported by the production loader"
                    {:type :package-error/input-format :code :unsupported-input-format
                     :path path})))
  (when (some (fn [definition]
                (and (map? definition)
                     (some #(contains? definition %) [:input :bind :configure])))
              (:fns module))
    (throw (ex-info "Structured input declarations require the offline version 2 reader"
                    {:type :package-error/input-format :code :mixed-input-format
                     :path path}))))
