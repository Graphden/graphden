(ns graphden.crud.branches
  "Implementation primitives consumed by the `/api/branches/*` graph
   fn-defs in `resources/packages/app/branches`. Only two helpers
   remain: `base-storage` (used by the read-side `:diff-branches` /
   `:detect-conflicts` base-fns to drop the version wrapper) and
   `resolve-branch-ref` (used by the `:resolve-branch-ref` base-fn).

   The branch API's create / delete / diff / detect-conflicts / merge
   logic lives in graph fn-defs over atomic primitives (`:create-branch!`
   / `:delete-branch!` / `:diff-branches` / `:detect-conflicts` /
   `:merge-branch!`) + `:zipmap` / `:as-json-branch` / `:stringify-uuids`
   composition + graph-level `:try` for exception → structured-envelope
   dispatch. See `branches_graph_test.clj` for the equivalence-tested
   call path."
  (:require
    [clojure.string :as str]
    [graphden.crud.request :as request]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(defn base-storage
  "Read endpoints always operate on the unwrapped base storage — the
   branch context is encoded in the request path/query, not in the
   wrapper. Throws the same `:execution-error/missing-storage` shape
   as `request/require-storage` when ctx has no storage. Public so
   the `:resolve-branch-ref` base-fn in `app/branches` can use it."
  [ctx]
  (vs/unwrap (request/require-storage ctx)))


(defn resolve-branch-ref
  "Branch lookup that accepts either a stringified UUID or a branch
   name. Returns the row, or nil when not found. Public so the
   `:resolve-branch-ref` base-fn in `app/branches` can delegate
   directly."
  [storage branch-ref]
  (or (some->> (request/parse-uuid-or-clear branch-ref)
               (sp/read-entity storage :branch))
      (when (and (string? branch-ref) (not (str/blank? branch-ref)))
        (first (sp/query-entities storage :branch {:name branch-ref})))))
