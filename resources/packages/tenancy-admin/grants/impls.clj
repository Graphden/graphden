(ns graphden.packages.tenancy-admin.grants.impls
  "Impls for the org-admin grants panel (PLATFORM_PLAN §6).

   `:list-grants` and `:create-grant` are pure graph compositions in
   `fns.edn` over the generic CRUD primitives — the capability
   validation, username→stable-id resolve and row assembly are
   graph-visible. The only Clojure left is the capability vocabulary
   read below: the closed set lives in `graphden.tenancy.grant` (the
   enforcement layer keys on it), so the graph reads it through one
   boundary call rather than duplicating the list."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.tenancy.grant :as grant]))


(defbase grant-capability-set
  "The closed capability vocabulary as a `{name-string true}` map — the
   JSONB-friendly encoding of `grant/capabilities` (a Clojure keyword
   set can't flow through the graph as itself). Membership = `:contains?`;
   the name list for error messages = `:keys`."
  []
  (into {} (map (fn [c] [(name c) true])) grant/capabilities))


(def impls
  {:grant-capability-set grant-capability-set})
