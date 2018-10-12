(ns d2q.helpers.resolvers
  "Utilities for implementing Resolvers."
  (:require [manifold.deferred :as mfd]
            [d2q.api :as d2q]
            [d2q.impl.utils :as impl.utils])
  (:import (java.util ArrayList)))


(defn into-resolver-result
  "Transforms a sequence which elements are either d2q Result Cells or errors into a proper d2q Resolver Result map.
  Useful for constructing Resolver Results by processing entities sequentially.

  An optional transducer may be supplied, which must transform inputs to Result Cells or errors.

  A value `v` is considered an error when `(instance? Throwable v)` is true."
  ([rcells-or-errors]
   (into-resolver-result identity rcells-or-errors))
  ([xform xs]
   (let [^ArrayList l-errs (ArrayList.)
         rcells (into []
                  (comp
                    xform
                    (remove (fn [v]
                              (when (instance? Throwable v)
                                (.add l-errs v)
                                true))))
                  xs)]
     {:d2q-res-cells rcells
      :d2q-errors (vec l-errs)})))

(defn entities-independent-resolver
  "Convenience for implementing resolvers which computations are independent from the entities to which they are applied.

  Some Fields are independent of the Entities to which they are applied (example: :myapp/find-user-by-id);
  such Fields will typically be executed only at the top-level of a Query, and only on one Entity,
  but a Resolver cannot make this assumption.

  resolver-fn must be a function with arguments [qctx i+fcalls]
  (like an ordinary Resolver function, without the last j+entities argument),
  and returns the same results as an ordinary
  resolver function, with dummy values for :d2q-entcell-j in Result Cells (typically -1 or nil).
  Each Result Cell returned by resolver-fn will be repeated once per input Entity."
  [resolver-fn]
  (fn [qctx i+fcalls j+entities]
    (mfd/chain (resolver-fn qctx i+fcalls)
      (fn [{:as ret, res-cells :d2q-res-cells}]
        (let [js (mapv first j+entities)]
          (assoc ret
            :d2q-res-cells
            (into []
              (mapcat
                (fn [partial-res-cell]
                  (->> js
                    (mapv
                      (fn [j]
                        (assoc partial-res-cell :d2q-entcell-j j))))))
              res-cells)))))))

(defn fields-independent-resolver
  "Convenience for implementing a Resolver function which ignores its i+fcalls argument,
  typically because it computes only one Field with no argument."
  [resolver-fn]
  (fn [qctx i+fcalls j+entities]
    (mfd/chain (resolver-fn qctx j+entities)
      (fn [{:as ret, res-cells :d2q-res-cells}]
        (let [is (mapv first i+fcalls)]
          (assoc ret
            :d2q-res-cells
            (into []
              (mapcat
                (fn [partial-res-cell]
                  (->> is
                    (mapv
                      (fn [i]
                        (assoc partial-res-cell :d2q-fcall-i i))))))
              res-cells)))))))

(defn validating-fcall-args
  "Adds validation of :d2q-fcall-arg in Field Calls to a Resolver function.

  Given an options map and Resolver compute function `resolver-fn`,
  returns a Resolver function which checks the :d2q-fcall-arg of each Field Call,
  passing only valid Field Call tuples to the wrapped `resolver-fn` and adding errors
  to the result for the invalid ones.

  Validation is performed via a user-supplied 'checker' function in the Field metadata,
  at key :d2q.helpers.field-meta/check-fcall-args.

  This checker function must accept the :d2q-fcall-arg for a Field, and do one of the following:
  - Throw an Exception: the Field Call is invalid, and an error will be added to the output map.
  - Return a truthy result: the Field Call is valid, and will be passed to the wrapped `resolver-fn`.
  - Return a falsey result: the Field Call is invalid, but will simply be discarded (not passed to
  the wrapped `resolver-fn`) without yielding and error.

  The :d2q.helpers.validating-fcall-args/checker-required? options determines the checking behaviour
  in case :d2q.helpers.field-meta/check-fcall-args is not supplied in a Field meta:
  - if false (the default): the corresponding Field Calls are considered valid.
  - if true: an Exception is returned for the corresponding Field Calls."
  [{:as opts,
    required? :d2q.helpers.validating-fcall-args/checker-required?
    :or {required? false}}
   resolver-fn]
  (fn [qctx i+fcalls j+entities]
    (let [{valid-i+fcalls :valid validation-errors :error}
          (impl.utils/group-and-map-by
            (map (fn [i+fcall]
                   (let [[i fcall field-meta] i+fcall
                         checker (:d2q.helpers.field-meta/check-fcall-args field-meta)]
                     (if (nil? checker)
                       (if required?
                         [:error (ex-info
                                   (str "Missing required checker function at key " (pr-str :d2q.helpers.validating-fcall-args/check-fcall-args)
                                     " in metadata for Field " (pr-str (:d2q-fcall-field fcall)))
                                   {:d2q-fcall-i i})]
                         [:valid i+fcall])
                       (try
                         [(if (checker (:d2q-fcall-arg fcall))
                            :valid
                            :ignored)
                          i+fcall]
                         (catch Throwable err
                           [:error (ex-info
                                     (str "Invalid " (pr-str :d2q-fcall-arg) " when calling Field " (pr-str (:d2q-fcall-field fcall))
                                       ". See the cause of this Exception for details.")
                                     {:d2q-fcall-i i}
                                     err)]))))))
            first second
            i+fcalls)]
      (if (empty? valid-i+fcalls)
        (mfd/success-deferred
          {:d2q-res-cells []
           :d2q-errors (vec validation-errors)})
        (mfd/chain
          (resolver-fn qctx valid-i+fcalls j+entities)
          (fn [ret]
            (update ret :d2q-errors
              (fn [ret-errors]
                (concat validation-errors ret-errors)))))))))

(defn validating-input-entities
  "Adds validation of Entities to a Resolver function.

  Given a `check-entity` and Resolver compute function `resolver-fn`,
  returns a Resolver function which checks the input Entities,
  passing only valid Entity tuples to the wrapped `resolver-fn` and adding errors
  to the result for the invalid ones.

  The `check-entity` function must accept an Entity, and do one of the following:
  - Throw an Exception: the Entity is invalid, and an error will be added to the output map.
  - Return a truthy result: the Entity is valid, and will be passed to the wrapped `resolver-fn`.
  - Return a falsey result: the Entity will be discarded (not passed to the wrapped `resolver-fn`)
  without yielding and error."
  [check-entity resolver-fn]
  {:pre [(fn? check-entity) (fn? resolver-fn)]}
  (fn [qctx i+fcalls j+entities]
    (let [{valid-entities :valid validation-errors :error}
          (impl.utils/group-and-map-by
            (map (fn [j+entity]
                   (let [[j entity] j+entity]
                     (try
                       [(if (check-entity entity)
                          :valid
                          :ignored)
                        j+entity]
                       (catch Throwable err
                         [:error (ex-info
                                   (str "Invalid Entity passed to the Resolver."
                                     " To fix, make sure your `check-entity` function is correct, "
                                     " and that the upstream Resolver returns valid Entities."
                                     " See the cause of this Exception for details.")
                                   {:d2q-entcell-j j}
                                   err)])))))
            first second
            j+entities)]
      (if (empty? valid-entities)
        (mfd/success-deferred
          {:d2q-res-cells []
           :d2q-errors (vec validation-errors)})
        (mfd/chain
          (resolver-fn qctx i+fcalls valid-entities)
          (fn [ret]
            (update ret :d2q-errors
              (fn [ret-errors]
                (concat validation-errors ret-errors)))))))))
