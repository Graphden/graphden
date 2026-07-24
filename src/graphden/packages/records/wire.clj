(ns graphden.packages.records.wire
  "EDN wire form for fn refs that cannot be spelled as READABLE
   keywords.

   Two namespace shapes produce such refs when a duplicated bare name
   forces qualification (per-ns names, ADR-identity-model.md stage 5):

   - version-materialized package namespaces (`web.components@1-2-0`)
     — `@` is not a keyword-namespace constituent, so `pr-str` writes
     a keyword no EDN reader can read back;
   - the ROOT namespace (nil ns-path) — there is no keyword namespace
     to qualify with at all.

   In MEMORY and through the JSONB codec both are fine: `(keyword
   \"lib@1-2-0.sub\" \"foo\")` and `(keyword \"\" \"foo\")` are legal
   values, and the codec's `{:_kw \"…\"}` carrier restores them via
   single-arg `keyword`, which splits on the last `/`. Only the EDN
   TEXT boundary breaks — so only it gets a wire form: the tagged
   literal

       #graphden/ref \"lib@1-2-0.sub/foo\"
       #graphden/ref \"/foo\"              ; root-ns spelling

   `encode-unreadable-kws` converts such keywords to tagged literals
   before `pr-str` (the whole-graph export bundle); `wire-readers`
   decodes them back to the same keywords at every `clojure.edn` read
   site (package loader, manifest, remote bundle)."
  (:require
    [clojure.walk :as walk]))


(def ^:private edn-symbol-ns-re
  "Characters legal in an EDN keyword NAMESPACE as the exporter emits
   them (dotted paths of `[a-zA-Z0-9_-]` segments). Version-materialized
   namespaces fail this on `@`."
  #"[A-Za-z0-9._-]+")


(defn edn-keyword-ns?
  "Can `ns-path` serve as a READABLE EDN keyword namespace?"
  [ns-path]
  (boolean (re-matches edn-symbol-ns-re ns-path)))


(defn unreadable-kw?
  "A qualified keyword whose printed form no EDN reader accepts: an
   empty namespace (the root-ns spelling `:/name`) or one containing a
   non-symbol constituent (`@`)."
  [x]
  (and (keyword? x)
       (some? (namespace x))
       (not (edn-keyword-ns? (namespace x)))))


(defn read-wire-ref
  "Decode the `#graphden/ref` payload back to the keyword it stands
   for. Single-arg `keyword` splits on the last `/`, so
   `\"lib@1-2-0.sub/foo\"` → `(keyword \"lib@1-2-0.sub\" \"foo\")` and
   `\"/foo\"` → `(keyword \"\" \"foo\")` — exactly the values the
   exporter started from."
  [s]
  (keyword s))


(def wire-readers
  "`:readers` map for every `clojure.edn` read of fn-def-shaped EDN."
  {'graphden/ref read-wire-ref})


(defn encode-unreadable-kws
  "Walk `form` and replace every unreadable qualified keyword with its
   `#graphden/ref` tagged literal, leaving everything else untouched.
   Applied to the whole-graph bundle right before `pr-str`; the JSONB
   publish path keeps raw keywords (its codec round-trips them)."
  [form]
  (walk/postwalk
    (fn [x]
      (if (unreadable-kw? x)
        (tagged-literal 'graphden/ref (subs (str x) 1))
        x))
    form))
