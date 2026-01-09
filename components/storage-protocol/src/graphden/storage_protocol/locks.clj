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
