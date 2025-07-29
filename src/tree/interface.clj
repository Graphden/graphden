(ns tree.interface)

(defprotocol Protocol

  (add-node [this node])

  (add-nodes [this nodes])

  (delete-node [this node-name])

  (node-name->node [this node-name])

  (rename-args-back-ref-node [this args old-name new-name])

  (children->rename-parent-node [this children new-name])

  (change-args-val [this node-names new-name])

  (disj-child-back-ref [this node])

  (disj-arg-back-ref [this args disj-name]))
