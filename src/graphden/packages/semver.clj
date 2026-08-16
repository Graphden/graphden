(ns graphden.packages.semver
  "Semantic-version parsing + constraint matching for `package.edn`
   `:dependencies`.

   A dependency may declare a version constraint; the loader validates
   that the version present on the classpath satisfies it (version
   *selection* from a registry is an install-time concern, not here).

   Supported constraint operators:

   | Syntax        | Meaning                                             |
   |---------------|-----------------------------------------------------|
   | `\"1.2.0\"` / `\"=1.2.0\"` | exact                                  |
   | `\">=1.2.0\"` `\">1.2.0\"` `\"<=1.2.0\"` `\"<1.2.0\"` | comparison  |
   | `\"~>1.2.3\"` | pessimistic: `>=1.2.3` and `<1.3.0`                 |
   | `\"~>1.2\"`   | pessimistic: `>=1.2.0` and `<2.0.0`                 |
   | `\"^1.2.3\"`  | caret: `>=1.2.3` and `<2.0.0` (`^0.2.3` → `<0.3.0`) |
   | `\"*\"` / `nil` / bare name | any version                           |

   Versions are compared on `[major minor patch]`; a pre-release / build
   suffix (`-rc1`, `+build`) is ignored for ordering (MVP — no
   pre-release precedence rules)."
  (:require
    [clojure.string :as str]))


(defn parse-version
  "`\"1.2.3\"` → `[1 2 3]`. Missing minor/patch default to 0; a
   `-pre`/`+build` suffix is dropped, and a leading `v`/`V`
   (`\"v1.2.3\"`) is stripped before parsing. Non-numeric components
   collapse to 0. Returns nil for nil input."
  [v]
  (when (some? v)
    (let [;; Strip a conventional leading `v`/`V` (`v1.2.3`) — without
          ;; this the `v` fuses to the major component, `re-matches
          ;; #\"\\d+\"` fails, and the major silently collapses to 0.
          bare  (str/replace (str/trim (str v)) #"^[vV]" "")
          core  (first (str/split bare #"[-+]" 2))
          parts (str/split core #"\.")
          nums  (map (fn [p] (if (re-matches #"\d+" p) (parse-long p) 0)) parts)]
      (vec (take 3 (concat nums [0 0 0]))))))


(defn- cmp
  "Compare two version vectors lexicographically."
  [a b]
  (compare a b))


(defn parse-constraint
  "Parse a constraint string into `{:op … :version [maj min patch] :parts n}`.
   `:parts` records how many numeric components the author wrote (needed
   for `~>`). Bare name / `\"*\"` / nil → `{:op :any}`."
  [c]
  (let [c (some-> c str str/trim)]
    (if (or (str/blank? c) (= c "*"))
      {:op :any}
      (let [[_ op ver] (re-matches #"(>=|<=|~>|\^|>|<|=)?\s*(.+)" c)
            core       (first (str/split ver #"[-+]" 2))
            parts      (count (str/split core #"\."))]
        {:op (case op
               ">=" :gte
               ">"  :gt
               "<=" :lte
               "<"  :lt
               "~>" :twiddle
               "^"  :caret
               (nil "=") :eq)
         :version (parse-version ver)
         :parts parts
         :raw c}))))


(defn- inc-at
  "Return a 3-vector equal to `v` with index `i` incremented and the
   lower components zeroed: `(inc-at [1 2 3] 1)` → `[1 3 0]`."
  [v i]
  (vec (map-indexed (fn [idx x]
                      (cond
                        (< idx i) x
                        (= idx i) (inc x)
                        :else 0))
                    v)))


(defn- twiddle-upper
  "Exclusive upper bound for `~>`. `~>1.2.3` (3 parts) → `[1 3 0]`;
   `~>1.2` (2 parts) → `[2 0 0]`; `~>1` (1 part) → `[2 0 0]`."
  [target parts]
  (inc-at target (max 0 (- (min parts 3) 2))))


(defn- caret-upper
  "Exclusive upper bound for `^`: bump the leftmost non-zero component.
   `^1.2.3` → `[2 0 0]`; `^0.2.3` → `[0 3 0]`; `^0.0.3` → `[0 0 4]`."
  [[maj min' pat]]
  (cond
    (pos? maj) [(inc maj) 0 0]
    (pos? min') [0 (inc min') 0]
    :else [0 0 (inc pat)]))


(defn satisfies-constraint?
  "Does version `ver` satisfy `constraint` (a string or a parsed map)?
   A nil/blank constraint is `:any` → true."
  [ver constraint]
  (let [{:keys [op parts] target :version}
        (if (map? constraint) constraint (parse-constraint constraint))
        v (parse-version ver)]
    (if (= op :any)
      true
      (and (some? v)
           (case op
             :eq  (= v target)
             :gte (not (neg? (cmp v target)))
             :gt  (pos? (cmp v target))
             :lte (not (pos? (cmp v target)))
             :lt  (neg? (cmp v target))
             :twiddle (and (not (neg? (cmp v target)))
                           (neg? (cmp v (twiddle-upper target parts))))
             :caret   (and (not (neg? (cmp v target)))
                           (neg? (cmp v (caret-upper target)))))))))
