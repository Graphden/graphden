(ns graphden.test-infra.wait
  "The one bounded-poll helper. Six test namespaces carried private
   copies (sleep step 20–50 ms — immaterial); the PATTERN is the
   point: poll until truthy or deadline, so an assertion on an
   asynchronous condition waits exactly as long as the condition
   takes, instead of a fixed sleep that either wastes time or expires
   early under host load.")


(defn wait-for
  "Poll `pred` every 20 ms until it returns truthy or `ms` elapses.
   Returns the truthy `pred` value, or nil/false on timeout — assert
   on the return value."
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 20)
            (recur))))))
