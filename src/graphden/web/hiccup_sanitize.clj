(ns graphden.web.hiccup-sanitize
  "Allowlist sanitizer for graph-authored presentation hiccup.

   Typed value-representations (`:_value-repr-registry` repr fns)
   execute graph code and inline the resulting hiccup into the
   EDITOR's DOM — unlike user-composed pages (their own tab) or the
   sandboxed iframe panes (`srcdoc` + `sandbox=`). That makes repr
   output a stored-XSS surface of the same class as
   `:resource-override`, so it passes through this sanitizer before
   embedding. Defense-in-depth: today the repr registry ships
   first-party, but the sanitizer keeps the property independent of
   who authored the repr fn.

   Policy (deny-by-default):
   - Tags: static presentation + SVG geometry only. No scripts, no
     frames, no forms/inputs, no `<a>`/`<img>` (removes every
     URL-bearing attribute, closing the value-exfiltration door).
   - Attributes: fixed allowlist; `on*`, `hx-*`, `data-*`, `style`,
     `srcdoc`, `href`/`src` never pass (`hx-*`/`data-*` because the
     result pane runs `htmx.process` — an `hx-get` in repr output
     would issue authenticated requests from the editor session).
   - Paint values (`fill` / `stroke` / `stop-color`) must match a
     color literal or an in-document `url(#id)` reference.
   - Non-hiccup nodes (`h-raw` RawStrings included) collapse to
     plain strings, so hiccup2 escapes them on render.
   - Bounded: depth > 40 or node budget exhausted drops the subtree.

   A dropped node returns nil; hiccup renders nil as nothing, so a
   partially-disallowed tree degrades instead of erroring."
  (:require
    [clojure.string :as str]))


(def ^:private allowed-tags
  ;; Lower-cased. Presentation + tables + SVG shapes. Deliberately
  ;; absent: a, img, iframe, script, style, form controls, foreignobject,
  ;; use (external ref via href), animate* (SMIL timing).
  #{"div" "span" "p" "ul" "ol" "li" "dl" "dt" "dd" "table" "thead"
    "tbody" "tfoot" "tr" "td" "th" "caption" "strong" "em" "b" "i"
    "u" "s" "small" "sub" "sup" "code" "pre" "kbd" "samp" "var"
    "blockquote" "figure" "figcaption" "abbr" "mark" "time" "br" "hr"
    "h1" "h2" "h3" "h4" "h5" "h6"
    "svg" "g" "path" "circle" "ellipse" "rect" "line" "polyline"
    "polygon" "text" "tspan" "title" "desc" "defs" "lineargradient"
    "radialgradient" "stop" "clippath"})


(def ^:private allowed-attrs
  ;; Lower-cased. No URL-bearing attribute is ever allowed.
  #{"class" "title" "colspan" "rowspan" "datetime" "width" "height"
    "role" "aria-label" "aria-hidden"
    ;; SVG structure + geometry
    "xmlns" "viewbox" "preserveaspectratio" "d" "points" "x" "y" "x1"
    "y1" "x2" "y2" "cx" "cy" "r" "rx" "ry" "dx" "dy" "transform"
    "clip-path" "offset" "gradientunits" "gradienttransform"
    ;; SVG presentation
    "fill" "stroke" "stroke-width" "stroke-linecap" "stroke-linejoin"
    "stroke-dasharray" "stroke-dashoffset" "opacity" "fill-opacity"
    "stroke-opacity" "fill-rule" "text-anchor" "dominant-baseline"
    "font-size" "font-family" "font-weight" "stop-color"
    "stop-opacity" "vector-effect"})


(def ^:private paint-attrs
  #{"fill" "stroke" "stop-color" "clip-path"})


(def ^:private paint-value-re
  ;; Color literal, keyword paint, or an in-document reference.
  #"(?i)^(none|currentcolor|transparent|inherit|[a-z]+|#[0-9a-f]{3,8}|(?:rgb|rgba|hsl|hsla)\([0-9.,%\s]+\)|url\(#[a-z0-9_-]+\))$")


(def ^:private max-depth 40)
(def ^:private max-nodes 5000)


(defn- attr-name
  "Normalized (lower-cased, namespace-stripped) attribute name, or nil
   for a non-named key."
  [k]
  (cond
    (keyword? k) (str/lower-case (name k))
    (string? k)  (str/lower-case k)
    :else        nil))


(defn- sanitize-attrs
  [attrs]
  (reduce-kv
    (fn [acc k v]
      (let [n (attr-name k)]
        (if (and n
                 (contains? allowed-attrs n)
                 (or (not (contains? paint-attrs n))
                     (and (string? v) (re-matches paint-value-re v))))
          (assoc acc n (str v))
          acc)))
    {}
    attrs))


(defn- tag-name
  "Lower-cased tag string for a keyword/string tag, nil otherwise.
   Strips the hiccup `tag#id.class` shorthand DOWN TO the bare tag —
   the id/class shorthand is dropped rather than parsed, so a repr
   can't smuggle attributes past `sanitize-attrs` through it."
  [tag]
  (when-let [s (cond (keyword? tag) (name tag)
                     (string? tag)  tag
                     :else          nil)]
    (let [bare (first (str/split s #"[#.]" 2))]
      (when (seq bare)
        (str/lower-case bare)))))


(defn sanitize-hiccup
  "Sanitize `node` (an EDN hiccup tree — string or keyword tags) under
   the ns policy. Returns the sanitized tree, or nil when the node is
   disallowed / the bounds are exceeded. Top-level callers should treat
   nil as \"no representable output\"."
  ([node]
   (let [budget (volatile! max-nodes)
         walk   (fn walk
                  [n depth]
                  (when (and (<= depth max-depth) (pos? @budget))
                    (vswap! budget dec)
                    (cond
                      (nil? n) nil
                      (or (string? n) (number? n) (boolean? n)) n
                      (keyword? n) (name n)

                      (vector? n)
                      (when-let [t (tag-name (first n))]
                        (when (contains? allowed-tags t)
                          (let [attrs    (when (map? (second n)) (second n))
                                children (if attrs (nnext n) (next n))
                                kids     (into []
                                               (keep #(walk % (inc depth)))
                                               children)]
                            (into [t (sanitize-attrs (or attrs {}))] kids))))

                      ;; A non-vector sequence is a child seq, not an element.
                      (sequential? n)
                      (into [] (keep #(walk % (inc depth))) n)

                      ;; Anything else (RawString, objects) — collapse to a
                      ;; plain string; hiccup2 escapes it on render.
                      :else (str n))))]
     (walk node 0))))
