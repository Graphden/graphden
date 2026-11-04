(ns graphden.crud.views.command
  "Typed rejection shared by the atomic view writer. Wire parsing lives in
   the app.views/parse-view-command graph composition.")


(defn reject!
  [reason]
  (throw (ex-info reason {:type :validation-error/view-command :reason reason})))
