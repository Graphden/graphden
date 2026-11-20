(ns graphden.packages.type-declaration
  "Canonical type alias bodies from authored package declarations."
  (:require
    [graphden.packages.records.types :as record-types]
    [graphden.types.core :as types]))


(defn type-row-alias-body
  "The type expression a type-row fn-def declares — `:refine` / record
   `:type` / `:list` / `:union` / `:map` / `:variant` / `:fn-type` — or nil
   for other declarations. Shared by sync, source verification and package audits."
  [fd]
  (cond
    (:refine fd)
    (let [{:keys [base constraint]} (:refine fd)]
      (when base [:refine base (or constraint [:any])]))

    (and (:type fd) (map? (:type fd)))
    (:type fd)

    (:list fd)
    [:list (:list fd)]

    (:union fd)
    (into [:union] (:union fd))

    ;; Homogeneous map alias — `:map {:key K :value V}` is sugar
    ;; for the structural `[:map K V]`. Without this branch the
    ;; alias-body fn ignored the declaration and downstream slot
    ;; references (`:list-entities :where :_storage-where-map`)
    ;; saw a bare keyword the alias registry didn't know about,
    ;; so the type-checker treated it as opaque and a literal
    ;; `{:value {}}` failed against it.
    (and (:map fd) (map? (:map fd)))
    (let [{:keys [key value]} (:map fd)]
      (when (and key value) [:map key value]))

    ;; `:variant [:tag1 T1 :tag2 T2 …]` desugars to a union of
    ;; tag-pinned records (see types/desugar-variant). Without
    ;; this branch the EDN-declared `:result-text`,
    ;; `:result-int`, `:validation` aliases never reached
    ;; `register-type-alias!` and the type-checker treated
    ;; them as unknown keywords — defeating the whole point
    ;; of a variant declaration.
    (:variant fd)
    (types/desugar-variant (:variant fd))

    (:fn-type fd)
    (record-types/fn-type-declaration (:fn-type fd))))
