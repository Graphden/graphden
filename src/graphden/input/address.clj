(ns graphden.input.address
  "Structural relations between derived input scopes; no graph execution.")


(defn within-scope?
  "Whether a derived scope belongs to a root's reference-use subtree."
  [[root prefix] [owner path]]
  (and (= root owner) (<= (count prefix) (count path))
       (= prefix (subvec path 0 (count prefix)))))
