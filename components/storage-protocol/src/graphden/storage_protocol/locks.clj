(ns graphden.storage-protocol.locks
  "Read-write lock utilities for thread-safe storage access.

   Provides shared lock utilities for storage implementations that need
   thread-safe concurrent access. Uses ReentrantReadWriteLock for better
   concurrency:
   - Multiple readers can run concurrently
   - Writers have exclusive access"
  (:import
    (java.util.concurrent.locks
      Lock
      ReentrantReadWriteLock)))


(defn with-read-lock
  "Executes f with read lock held. Multiple readers can run concurrently.

   Example:
   (with-read-lock rw-lock #(read-data state))"
  [^ReentrantReadWriteLock rw-lock f]
  (let [lock ^Lock (ReentrantReadWriteLock/.readLock rw-lock)]
    (Lock/.lock lock)
    (try
      (f)
      (finally
        (Lock/.unlock lock)))))


(defn with-write-lock
  "Executes f with write lock held. Exclusive access - no readers or writers
   can proceed while this lock is held.

   Example:
   (with-write-lock rw-lock #(swap! state update-data))"
  [^ReentrantReadWriteLock rw-lock f]
  (let [lock ^Lock (ReentrantReadWriteLock/.writeLock rw-lock)]
    (Lock/.lock lock)
    (try
      (f)
      (finally
        (Lock/.unlock lock)))))


(defn create-rw-lock
  "Creates a new ReentrantReadWriteLock for thread-safe storage access.
   Use with-read-lock and with-write-lock for locking operations."
  []
  (ReentrantReadWriteLock.))


(defn with-double-check-locking
  "Implements double-check locking pattern for lazy initialization.

   This pattern is useful for expensive initialization that should only
   happen once, even under concurrent access. The fast path checks the
   cache-atom without locking; if nil, acquires write lock and re-checks.

   Arguments:
   - cache-atom: atom holding cached value (nil means not initialized)
   - rw-lock: ReentrantReadWriteLock for synchronization
   - compute-fn: zero-arg function to compute value if not cached

   Returns cached value or newly computed value.

   Example:
   (with-double-check-locking metadata-cache rw-lock
     (fn [] (expensive-metadata-fetch)))

   Thread safety:
   - Fast path: returns cached value without locking
   - Slow path: acquires write lock, re-checks cache, computes if needed
   - compute-fn is called at most once even with concurrent callers"
  [cache-atom rw-lock compute-fn]
  (or @cache-atom
      (with-write-lock rw-lock
        (fn []
          (or @cache-atom
              (let [result (compute-fn)]
                (reset! cache-atom result)
                result))))))
