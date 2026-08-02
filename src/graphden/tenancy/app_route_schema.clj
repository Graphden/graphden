(ns graphden.tenancy.app-route-schema
  "App-route registry (Track C hosting reframe — see
   `docs/PLATFORM_PLAN.md` § App routing). An org owns MANY named apps, each an
   entry `(org, label, handler-fn-id)`. This replaces the single
   `:org.handler-fn-id` as the source of `host → handler-fn` truth:

   - `label`          — the app's subdomain label. `<label>.<org>.<base>` serves
                        this app's handler; a verified custom `:domain` may also
                        point at `(org, label)`. Lower-cased, UNIQUE per org.
   - `org`            — the owning org. `(org, label)` is the routing key.
   - `handler-fn-id`  — the fn the app-router runs for the host, exactly like the
                        legacy `:org.handler-fn-id` did for the whole org.

   The org's ROOT subdomain (`<org>.<base>`) is the org's editor/login, NOT an
   app — so apps always carry a non-blank `label` and there is no default row.

   Platform-managed: `:app-route` is in
   `tenancy.storage/tenant-forbidden-entities` — a tenant writing it could route
   a host at another org's fn; reading it could enumerate every org's apps. The
   router reads it in the platform context (app-routing runs before the request
   scope binds an org); tenant-facing management goes through an org-stamped
   platform-storage seam, like `:service` / `:domain`."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def ^:private app-route-entity-uuid
  #uuid "e4cb2c87-7486-4cf9-b4c9-a5394a9dd381")


(def ^:private app-route-label-field-uuid
  #uuid "f38b7a73-3012-49c8-969f-885c2f230195")


(def ^:private app-route-org-field-uuid
  #uuid "deacdf9d-ee6e-489e-a109-0804059c6f5f")


(def ^:private app-route-handler-fn-id-field-uuid
  #uuid "f3585251-f68a-4969-a7da-89aca398d7c4")


(defn extend-builder
  "Add the `:app-route` entity — `(label, org, handler-fn-id)`, UNIQUE per
   `(org, label)` so an org can't route one label at two handlers."
  [builder]
  (-> builder
      (ds/add-entity :app-route app-route-entity-uuid
                     {:label {:uuid app-route-label-field-uuid :type :text}
                      :org {:uuid app-route-org-field-uuid :type :text}
                      :handler-fn-id {:uuid app-route-handler-fn-id-field-uuid
                                      :type :text}})
      (ds/add-constraint :app-route {:type :unique :fields [:org :label]})))
