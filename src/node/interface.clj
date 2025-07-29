(ns node.interface)

(defprotocol Protocol

  (set-parent-node [this parent-name])

  (add-args-back-ref [this arg-node-name paren-arg-name])

  (set-base-node-name [this parent-node-name])

  (set-full-args [this parent-full-args])

  (add-child-back-ref [this child-name])

  (delete-child-back-ref [this child-name])

  (rename-child-back-ref [this child-name new-child-name])

  (rename-arg-back-ref-node [this old-name new-name])

  (delete-arg-back-ref-node [this node-name arg-name])

  (change-arg-val [this arg-name arg-val])

  (rename-node [this new-name]))
