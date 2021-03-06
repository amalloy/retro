(ns retro.core
  (:use [useful.utils :only [returning]])
  (:import (javax.transaction InvalidTransactionException TransactionRolledbackException)))

(def ^{:dynamic true} *in-revision* #{})

(defprotocol Transactional
  (txn-begin! [obj]
    "Begin a new transaction.")
  (txn-commit! [obj]
    "Commit the current transaction.")
  (txn-rollback! [obj]
    "Roll back the current transaction."))

(defprotocol WrappedTransactional
  (txn-wrap [obj f]
    "Wrap the given function in a transaction, returning a new function."))

(defprotocol Queueable
  "A retro object capable of queuing up a list of operations to be performed later,
   typically at transaction-commit time."
  (enqueue [obj f]
    "Add an action - it will be called later with the single argument obj.")
  (get-queue [obj]
    "Return a sequence of this object's pending actions.")
  (empty-queue [obj]
    "Empty this object's queue."))

(defprotocol Revisioned
  (at-revision [obj rev]
    "Return a copy of obj with the current revision set to rev.")
  (current-revision [obj]
    "Return the current revision."))

(defprotocol Applied
  (revision-applied? [obj rev]
    "Tell whether the revision named by rev has already been written."))

(defprotocol OrderedRevisions
  (max-revision [obj]
    "What is the 'latest' revision that has been applied? Should be unaffected by at-revision
     'views'. nil is an acceptable answer, meaning 'none', or 'I'm not tracking that'.")
  (touch [obj]
    "Mark the current revision as being applied, guaranteeing that max-revision returns a
     number at least as large as the object's current revision."))

(let [conj (fnil conj [])]
  (extend-type clojure.lang.IObj
    Queueable
    (enqueue [this f]
      (vary-meta this update-in [::queue] conj f))
    (get-queue [this]
      (-> this meta ::queue))
    (empty-queue [this]
      (vary-meta this assoc ::queue []))

    Revisioned
    (at-revision [this rev]
      (vary-meta this assoc ::revision rev))
    (current-revision [this]
      (-> this meta ::revision))
    (revision-applied? [this rev]
      false)))

(extend-type Object
  WrappedTransactional
  (txn-wrap [_ f]
    (fn [obj]
      (txn-begin! obj)
      (try (returning (f obj)
             (txn-commit! obj))
           (catch Throwable e
             (txn-rollback! obj)
             (throw e)))))
  Applied
  (revision-applied? [this rev]
    (when-let [max (max-revision this)]
      (>= max rev)))

  OrderedRevisions
  (max-revision [this]
    nil)
  (touch [this]
    nil))

(def ^{:dynamic true} *active-transaction* nil)

(defn modify!
  "Alert retro that an operation is about to occur which will modify the given object.
   If there is an active transaction which is not expected to modify the object,
   retro will throw an exception. Should be used similarly to clojure.core/io!."
  [obj]
  (when (and *active-transaction*
             (not (= obj *active-transaction*)))
    (throw (IllegalStateException.
            (format "Attempt to modify %s while in a transaction on %s"
                    obj *active-transaction*)))))

(do
  ;;; These function-wrapping functions behave kinda like ring wrappers: they
  ;;; return a function which takes a retro-object and returns a retro-object.
  (defn- active-object [f]
    (fn [obj]
      (binding [*active-transaction* obj]
        (f obj))))

  (defn- catch-rollbacks
    "Takes a function and wraps it in a new function that catches the exception thrown by abort-transaction."
    [f]
    (fn [obj]
      (try (f obj)
           (catch TransactionRolledbackException e obj))))

  (defn wrap-touching
    "Wrap a function so that the active object is touched at the end."
    [f]
    (fn [obj]
      (returning (f obj)
        (touch obj))))

  (defn wrap-transaction
    "Takes a function and returns a new function wrapped in a transaction on the given object."
    [f obj]
    (->> (wrap-touching f)
         (txn-wrap obj)
         (active-object)
         (catch-rollbacks))))

(defn abort-transaction
  "Throws an exception that will be caught by catch-rollbacks to abort the transaction."
  []
  (throw (TransactionRolledbackException.)))

(defmacro with-transaction
  "Execute forms within a transaction on the specified object."
  [obj & forms]
  `(let [obj# ~obj]
     ((wrap-transaction (fn [inner-obj#]
                          (do ~@forms
                              inner-obj#))
                        obj#)
      obj#)))

(defn skip-applied-revs
  "Useful building block for skipping applied revisions. Calling this on a retro object
  will empty the object's queue if the revision has already been applied."
  [obj]
  (let [rev (current-revision obj)]
    (if (and rev (revision-applied? obj rev))
      (empty-queue obj)
      obj)))

(defmacro dotxn
  "Perform body in a transaction around obj. The body should evaluate to a version of
   obj with some actions enqueued via its implementation of Queueable; those actions
   will be performed after the object has been passed through its before-mutate hook."
  [obj & body]
  `((wrap-transaction (fn [new-obj#]
                        (doseq [f# (get-queue (skip-applied-revs new-obj#))]
                          (f# new-obj#))
                        (empty-queue new-obj#))
                      ~obj)
    (binding [*active-transaction* 'writes-disabled]
      ~@body)))
