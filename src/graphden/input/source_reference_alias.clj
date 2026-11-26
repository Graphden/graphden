(ns graphden.input.source-reference-alias
  "Prove the direct legacy reader behind an occupied reference alias."
  (:require
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.renames :as renames]
    [graphden.executor.compile.surface :as surface]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.slot-resolution :as slots]))


(defn- resolve-readers
  [context owner target records]
  (let [defs (:defs-by-name context)
        ancestors (distinct (mapcat #(slots/chain-of (slots/own-ref % defs) defs) [owner target]))
        rows (into #{} (mapcat #(parse/parse-prepared-module (assoc context :fn-defs [%])))
                   (remove #(= (:fn-id owner) (:fn-id %)) (map defs ancestors)))
        kinds (group-by :kind (into rows records))
        index (lookups/build-lookups {:fns (:fn kinds) :slots (:slot kinds) :fn-slots (:fn-slot kinds)
                                      :bindings (:binding kinds) :list-items (:binding-list-item kinds)})
        root (:fn-id owner)
        callee (:fn-id target)]
    {:arguments (bindings/collect-bindings root index)
     :callee-arguments (bindings/collect-bindings callee index)
     :environment (concat (bindings/collect-env-bindings root index) (bindings/collect-env-bindings callee index))
     :aliases (renames/compute-rename-aliases root index)
     :translation (renames/build-ref-renames callee root index)
     :surface (surface/surface-entries root index)
     :callee-surface (surface/surface-entries callee index)}))


(defn- without-view
  [rows alias-id member]
  (into []
        (comp (remove #(contains? #{alias-id (:id member)} (:id %)))
              (map #(if (and (= :fn-slot (:kind %)) (= (:fn-id member) (:fn-id %))
                             (< (:position member) (:position %)))
                      (update % :position dec)
                      %)))
        rows))


(defn witness
  "Certify a root value reference whose one free child reader supplies the
   alias name. Preserve the occupied slot and its membership as source
   origins, apart from that reader's executable use address."
  [context definition field]
  (let [spec (get-in definition [:args field])
        owner (:fn-id definition)
        alias (:as spec)
        target (get (:defs-by-name context) (:ref spec))
        rows-for #(parse/parse-prepared-module (assoc context :fn-defs [%]))]
    (when (and (= #{:as :ref} (set (keys spec))) (simple-keyword? alias) (not= alias field)
               (uuid? owner) (uuid? (:fn-id target)) (not= owner (:fn-id target)))
      (let [original (rows-for definition)
            compact (update-in definition [:args field] dissoc :as)
            after (rows-for compact)
            alias-id (ids/slot-id owner alias)
            field-row (some #(when (= alias-id (:id %)) %) original)
            member (some #(when (= (ids/fn-slot-id owner alias-id) (:id %)) %) original)
            before-readers (resolve-readers context definition target original)
            after-readers (resolve-readers context compact target after)
            {:keys [arguments callee-arguments environment surface callee-surface]} before-readers
            [edge & others] (remove #(#{:free :value} (:kind %)) arguments)
            [reader & siblings] (remove #(= :value (:kind %)) callee-arguments)
            binding-id (ids/binding-id owner (:slot-id edge))
            [entry & more] callee-surface]
        (when (and (= :slot (:kind field-row)) (= :fn-slot (:kind member)) (nat-int? (:position member))
                   (some #(and (= :binding (:kind %)) (= binding-id (:id %)) (= owner (:fn-id %))
                               (= (:slot-id edge) (:slot-id %)) (= (:fn-id target) (:ref-fn-id %))) original)
                   (= (set after) (set (without-view original alias-id member)))
                   (= :ref (:kind edge)) (not (:is-fn edge)) (= (:fn-id target) (:ref-id edge))
                   (= field (:base-name edge)) (= alias (:ext-name edge))
                   (= (:slot-id edge) (:source-slot-id field-row)) (empty? environment) (empty? others)
                   (= :free (:kind reader)) (= alias (:ext-name reader) (:ext-name entry)) (empty? siblings)
                   (empty? more) (uuid? (:slot-id entry)) (not= alias-id (:slot-id entry))
                   (= [entry] (filterv #(= alias (:ext-name %)) surface))
                   (= after-readers (update before-readers :arguments
                                            (partial mapv #(if (= edge %) (assoc % :ext-name field) %)))))
          {:field field :alias alias :alias-slot field-row :membership member
           :callable-reader? (true? (:is-fn reader))
           :reference-binding-id binding-id :target-id (:fn-id target) :occupied-address [[owner []] [(:slot-id edge)]]
           :reader-address [[owner [binding-id]] [(:slot-id entry)]]})))))
