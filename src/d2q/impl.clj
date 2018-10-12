(ns d2q.impl
  "Algorithm with tabular, non-blocking resolvers. Allows for low-latency and avoiding blocking IO."
  (:require [manifold.deferred :as mfd]
            [d2q.impl.tabular-protocols :as tp]
            [d2q.impl.utils :as impl.utils]
            [d2q.datatypes]
            [d2q.impl.datatypes]

            [#_taoensso.tufte d2q.impl.tufte-mock           ;; NOTE comment one or the other to toggle profiling
             :refer (p profiled profile)])
  (:import (java.util List ArrayList)
           (d2q.datatypes ResultCell)
           (java.io Writer)))

;; TODO more query validation? (Val, 07 Apr 2018)
(letfn [(normalize-query*
          [q rev-query-path]
          (if (sequential? q)
            (d2q.datatypes/->Query
              nil
              (into []
                (map-indexed
                  (fn [i fc]
                    (normalize-field-call*
                      fc
                      (cons i rev-query-path))))
                q)
              rev-query-path)
            (let [rev-qpath (cons :d2q-query-fcalls rev-query-path)
                  fcs
                  (into []
                    (map-indexed
                      (fn [i fc]
                        (normalize-field-call*
                          fc
                          (cons i rev-qpath))))
                    (:d2q-query-fcalls q))]
              (assoc
                (d2q.datatypes/map->Query q)
                :d2q-query-fcalls fcs
                :d2q-rev-query-path rev-query-path))))
        (normalize-field-call*
          [fc rev-query-path]
          (if (keyword? fc)
            (d2q.datatypes/->FieldCall fc fc nil nil rev-query-path)
            (let [fc1 (d2q.datatypes/map->FieldCall fc)]
              (as-> fc1 ret
                (assoc ret :d2q-fcall-rev-query-path rev-query-path)
                (if (:d2q-fcall-key ret)
                  ret
                  (assoc ret :d2q-fcall-key (:d2q-fcall-field ret)))
                (if-let [subq (:d2q-fcall-subquery fc1)]
                  (let [rev-qpath (cons :d2q-fcall-subquery rev-query-path)]
                    (assoc ret
                      :d2q-fcall-subquery
                      (normalize-query* subq rev-qpath)))
                  ret)))))]
  (defn normalize-query
    [q]
    (normalize-query* q ()))
  (defn normalize-field-call
    [fc]
    (normalize-field-call* fc ())))

(comment
  (normalize-query
    [:a
     {:d2q-fcall-field :b
      :d2q-fcall-subquery
      {:d2q-query-fcalls
       [:d
        :e]}
      :x :y}
     :c])
  )
;; Selection transformer: (Qctx, Query, [EntitySelectionCell]) -> Deferred<{:d2q-ent-selection [EntitySelectionCell], :d2q-errors [Throwable]}>
;; Tabular resolver: (Qctx, [FieldCall], [EntitySelectionCell]) -> Deferred<ResolverResult>

;; Server: (Engine, Qctx, Query, [EntitySelectionCell]) -> Deferred<{:d2q-results [Any], :d2q-errors [Throwable]}>
(let [MISSING (Object.)]
  ;; TODO keep track of the reversed data path for error reporting
  (letfn
    [(query* [svr, qctx, query, ent-sel
              q-trace]
       (assert (list? q-trace))
       (let [query-q-trace (conj q-trace
                             [:d2q.trace.op/query
                              ;; NOTE elision to get better REPL output
                              (assoc query :d2q-query-fcalls [:...elided])])
             n-ents (count ent-sel)]
         (if (zero? n-ents)
           (mfd/success-deferred {:d2q-results [] :d2q-errors []})
           (-> (transform-entities-safe svr qctx query ent-sel)
             (mfd/chain
               (fn [{t-ent-sel :d2q-ent-selection, t-errs :d2q-errors, early-results :d2q-early-results}]
                 (let [enriched-t-errs
                       (map
                         (fn [err]
                           ;; TODO enrich with data type
                           (ex-info
                             "Error in d2q Transform-Entities phase."
                             {:d2q.error/type :d2q.error.type/transform-entities
                              :d2q.error/query-trace query-q-trace}
                             err))
                         t-errs)
                       fcalls (:d2q-query-fcalls query)]
                   (if (empty? fcalls)
                     (mfd/success-deferred
                       {:d2q-results (vec (repeat (count ent-sel) {}))
                        :d2q-errors enriched-t-errs})
                     (let [by-fr
                           (p :by-fr
                             (group-by
                               (fn [fc]
                                 (let [field
                                       ;; TODO catch unknown Field error (Val, 06 Apr 2018)
                                       (tp/field-by-name svr (:d2q-fcall-field fc))
                                       ]
                                   (try (tp/field-table-resolver field)
                                        (catch Throwable err
                                          (throw err)))))
                               fcalls))]
                       (->
                         (->> by-fr
                           (map
                             (fn [[resolver fcalls]]
                               (apply-resolver svr qctx t-ent-sel resolver fcalls query-q-trace)))
                           (apply mfd/zip))
                         (mfd/chain
                           (fn [cells-results]
                             (p :ent-results-from-rcells
                               {:d2q-results
                                (ent-results-from-rcells n-ents cells-results early-results)
                                :d2q-errors
                                (concat
                                  enriched-t-errs
                                  (into [] (comp cat (mapcat :d2q-errors)) cells-results))})))))))))))))
     (transform-entities-safe [eng qctx query ent-sel]
       (try
         (->
           (tp/transform-entities eng qctx query ent-sel)
           (mfd/catch (fn [err] {:d2q-ent-selection [] :d2q-errors [err]})))
         (catch Throwable err
           (mfd/success-deferred {:d2q-ent-selection [] :d2q-errors [err]}))))
     ;; (...) -> Deferred<[{:d2q-results [FinalResultCell], :d2q-errors [Throwable]}]>
     (apply-resolver
       [svr qctx t-ent-sel resolver fcalls
        q-trace]
       (assert (list? q-trace))
       (let [resolver-q-trace
             (conj
               q-trace
               [:d2q.trace.op/resolver {:d2q.resolver/name (tp/tr-name resolver)}])
             fcall-tuples
             (p :fcall-tuples
               (into []
                 (map-indexed
                   (fn [fcall-i fcall]
                     (let [field (tp/field-by-name svr (:d2q-fcall-field fcall))]
                       [fcall-i fcall (tp/field-meta field)])))
                 fcalls))]
         (-> (resolve-table-safe resolver qctx fcall-tuples t-ent-sel)
           (mfd/chain
             (fn [{:keys [d2q-res-cells] resolver-errors :d2q-errors}]
               ;; TODO enrich error (Val, 06 Apr 2018)
               (let [n-fcalls (count fcall-tuples)

                     ^objects a-scalar-keys
                     (impl.utils/obj-array-with n-fcalls MISSING)

                     ^objects a-ref-fcall-cells-lists
                     (object-array n-fcalls)

                     ref-fcall-infos
                     (p :ref-fcall-infos
                       (into []
                         (comp
                           (map
                             (fn [[fcall-i fc]]
                               (let [i (int fcall-i)
                                     field (->> fc :d2q-fcall-field
                                             (tp/field-by-name svr))]
                                 (if (tp/field-scalar? field)
                                   (do
                                     (aset a-scalar-keys i
                                       (:d2q-fcall-key fc))
                                     nil)
                                   (let [;; IMPROVEMENT initialize to optimal size (Val, 02 Apr 2018)
                                         ^ArrayList res-cells (ArrayList.)]
                                     (aset a-ref-fcall-cells-lists i res-cells)
                                     [res-cells field fc])
                                   ))))
                           (remove nil?))
                         fcall-tuples))

                     ;; TODO maybe we don't want validation errors to be reported the same way as application-level errors. (Val, 05 Apr 2018)
                     ^ArrayList validation-errors
                     (ArrayList.)

                     scalar-cells
                     (p :scalar-cells
                       (try
                         (into []
                           (comp
                             (map-indexed
                               (fn [rcell-index rcell]
                                 ;; TODO validation (Val, 05 Apr 2018)
                                 (try
                                   (let [;; IMPROVEMENT read primitive int for performance (Val, 06 Apr 2018)
                                         d2q-fcall-i (int (:d2q-fcall-i rcell))
                                         ;; TODO catch ArrayIndexOutOfBounds
                                         scalar-k (aget a-scalar-keys d2q-fcall-i)]
                                     (if (identical? scalar-k MISSING)
                                       ;; ref-typed case
                                       (if-some [res-cells (aget a-ref-fcall-cells-lists d2q-fcall-i)]
                                         (do
                                           (.add ^ArrayList res-cells rcell)
                                           nil)
                                         (throw (ex-info
                                                  (str "Invalid :d2q-fcall-i " (pr-str d2q-fcall-i))
                                                  {})))
                                       ;; scalar-typed case
                                       (d2q.impl.datatypes/->FinalResultCell
                                         scalar-k
                                         (:d2q-entcell-j rcell) ;; IMPROVEMENT read primitive int for performance (Val, 06 Apr 2018)
                                         (:d2q-rescell-value rcell))))
                                   (catch Throwable err
                                     ;; TODO enrich error with contextual data (Val, 05 Apr 2018)
                                     (.add validation-errors err)
                                     nil))))
                             (remove nil?))
                           d2q-res-cells)
                         (catch Throwable err
                           (let [err1 (ex-info
                                        (str "Error when iterating over " :d2q-res-cells " returned by Resolver "
                                          (pr-str (tp/tr-name resolver))
                                          ". To fix: make sure that " :d2q-res-cells
                                          " is a sequential collection, and if it is lazy, make sure its realization does not throw errors.")
                                        {}                  ;; TODO enrich (Val, 06 Apr 2018)
                                        err)]
                             (.add validation-errors err1)
                             []))))

                     d-scalars
                     (mfd/success-deferred
                       {:d2q-results scalar-cells
                        :d2q-errors (concat
                                      (mapv
                                        ;; TODO enrich with Entity info when supplied
                                        (fn enrich-resolver-error [err]
                                          (let [exdata (ex-data err)
                                                fcall (when-some [d2q-fcall-i (:d2q-fcall-i exdata)]
                                                        (get fcalls d2q-fcall-i))]
                                            (ex-info
                                              (str
                                                "Error in d2q Resolver " (pr-str (tp/tr-name resolver))
                                                (when fcall
                                                  (str
                                                    ", on Field " (pr-str (:d2q-fcall-field fcall))
                                                    " at key " (pr-str (:d2q-fcall-key fcall)))))
                                              (cond->
                                                {:d2q.error/type :d2q.error.type/resolver
                                                 :d2q.resolver/name (tp/tr-name resolver)
                                                 :d2q.error/query-trace resolver-q-trace}
                                                (some? fcall)
                                                (assoc :d2q.error/field-call fcall))
                                              err)))
                                        resolver-errors)         ;; TODO enrich (Val, 10 Apr 2018)
                                      (seq (.toArray validation-errors)))})]
                 (->> ref-fcall-infos
                   (map (fn [[^ArrayList res-cells, field, fcall]]
                          (let [fck (:d2q-fcall-key fcall)
                                subq (:d2q-fcall-subquery fcall)
                                fc-q-stack (conj
                                             resolver-q-trace
                                             [:d2q.trace.op/field-call
                                              ;; NOTE elision to get better REPL output (Val, 10 Apr 2018)
                                              (assoc fcall
                                                :d2q-fcall-subquery [:...elided])])]
                            (if (tp/field-many? field)
                              ;; to-many case
                              (let [subsel
                                    (p :to-many-subsel
                                      #_(let [^ArrayList l (ArrayList.)]
                                          (reduce
                                            (fn [offset rcell]
                                              (let [children-ents (:d2q-rescell-value rcell)]
                                                (reduce
                                                  (fn [child-i child-ent]
                                                    (.add l (d2q.datatypes/->EntitySelectionCell child-i child-ent))
                                                    (inc child-i))
                                                  offset children-ents)))
                                            0 res-cells)
                                          (seq (.toArray l)))
                                      (into []
                                        (comp
                                          (mapcat :d2q-rescell-value)
                                          (map-indexed
                                            (fn [child-i child-ent]
                                              [child-i child-ent])))
                                        res-cells))]
                                (->
                                  ;; NOTE that's where it recurses (Val, 05 Apr 2018)
                                  (query* svr qctx subq subsel fc-q-stack)
                                  (mfd/chain
                                    (fn [{:keys [d2q-results, d2q-errors]}]
                                      (p :to-many-results
                                        {:d2q-results
                                         (let [vs (vec d2q-results)] ;; is this coercion fast? Should be, since query* always returns a vector (Val, 05 Apr 2018)
                                           ;; lookup in ArrayList by index for faster iteration (Val, 06 Apr 2018)
                                           ;; no apparent improvement in perf
                                           #_(let [n-res-cells (.size res-cells)]
                                               (loop [i 0
                                                      ;parent-cells (seq res-cells)
                                                      offset 0
                                                      tres (transient [])]
                                                 (if (= i n-res-cells)
                                                   (persistent! tres)
                                                   (let [parent-cell (.get res-cells i)
                                                         n (count (:d2q-rescell-value parent-cell))
                                                         next-offset (+ offset n)
                                                         frcell
                                                         (d2q.impl.datatypes/->FinalResultCell
                                                           fck
                                                           (:d2q-entcell-j parent-cell) ;; IMPROVEMENT read primitive int for performance (Val, 06 Apr 2018)
                                                           (subvec vs offset next-offset))]
                                                     (recur
                                                       (unchecked-inc-int i)
                                                       next-offset
                                                       (conj! tres frcell))))))
                                           (loop [parent-cells (seq res-cells)
                                                  offset 0
                                                  tres (transient [])]
                                             (if (empty? parent-cells)
                                               (persistent! tres)
                                               (let [parent-cell (first parent-cells)
                                                     n (count (:d2q-rescell-value parent-cell))
                                                     next-offset (+ offset n)
                                                     frcell
                                                     (d2q.impl.datatypes/->FinalResultCell
                                                       fck
                                                       (:d2q-entcell-j parent-cell) ;; IMPROVEMENT read primitive int for performance (Val, 06 Apr 2018)
                                                       (subvec vs offset next-offset))]
                                                 (recur
                                                   (next parent-cells)
                                                   next-offset
                                                   (conj! tres frcell))))))
                                         :d2q-errors d2q-errors})))))
                              ;; to-one case
                              (let [subsel
                                    (p :to-one-subsel
                                      (into []
                                        (map-indexed
                                          (fn [child-i rcell]
                                            (let [child-ent (:d2q-rescell-value rcell)]
                                              [child-i child-ent])))
                                        res-cells))]
                                (->
                                  ;; NOTE that's where it recurses (Val, 05 Apr 2018)
                                  (query* svr qctx subq subsel fc-q-stack)
                                  (mfd/chain
                                    (fn [{:keys [d2q-results, d2q-errors]}]
                                      (p :to-one-results
                                        {:d2q-results

                                         (mapv
                                           (fn [parent-cell child-v]
                                             (d2q.impl.datatypes/->FinalResultCell
                                               fck
                                               (:d2q-entcell-j parent-cell) ;; IMPROVEMENT read primitive int for performance (Val, 06 Apr 2018)
                                               child-v))
                                           res-cells
                                           d2q-results)
                                         :d2q-errors d2q-errors})))))
                              ))))
                   (cons d-scalars)
                   (apply mfd/zip))))))))
     (resolve-table-safe [resolver qctx fcall-tuples t-ent-sel]
       (try
         (-> (tp/resolve-table resolver qctx fcall-tuples t-ent-sel)
           (mfd/catch
             (fn [err] {:d2q-results [] :d2q-errors [err]})))
         (catch Throwable err
           (mfd/success-deferred {:d2q-results [] :d2q-errors [err]}))))
     (ent-results-from-rcells [n-ents cells-results early-results]
       (let [a-res (object-array n-ents)]
         (impl.utils/doarr-indexed! [[i _] a-res]
           (aset a-res i (transient {})))
         ;; PERFORMANCE IDEA would it be faster to sort by :d2q-entcell-j first? (Val, 05 Apr 2018)
         (transduce
           (comp
             cat
             (mapcat :d2q-results))
           (completing
             (fn [_ rcell]
               (d2q.impl.datatypes/merge-in-result-array rcell a-res)
               nil))
           nil cells-results)
         (impl.utils/doarr-indexed! [[i e] a-res]
           (aset a-res i (persistent! e)))
         (when (seq early-results)
           (reduce
             (fn [_ early-result-cell]
               (let [i (int (:d2q-entcell-j early-result-cell)) ;; IMPROVEMENT read primitive int for performance (Val, 06 Apr 2018)
                     v (:d2q-rescell-value early-result-cell)]
                 (aset a-res i v))
               nil)
             nil early-results))
         (vec a-res)))]
    (def query* query*)))


(defn query-normalized
  [svr qctx nq ent-sel]
  (let [q-trace ()]
    (query* svr qctx nq ent-sel q-trace)))

(defn query
  [svr qctx q entities]
  (let [nq (normalize-query q)
        ent-sel (into []
                  (map-indexed
                    (fn [ent-i ent] [ent-i ent]))
                  entities)]
    (query-normalized svr qctx nq ent-sel)))

;; ------------------------------------------------------------------------------
;; Structs

(defrecord TabularResolver
  [name f]
  tp/ITabularResolver
  (tr-name [_] name)
  (resolve-table [this qctx f+args o+is]
    (f qctx f+args o+is)))

(defrecord Field
  [fieldName isScalar isMany tr fieldMeta]
  tp/IField
  (field-name [this] fieldName)
  (field-scalar? [this] isScalar)
  (field-many? [this] isMany)
  (field-table-resolver [this] tr)
  (field-meta [this] fieldMeta))

(deftype Server
  [fieldByNames transformEntitiesFn #_resolveAccessLevelsFn]
  tp/IServer
  (transform-entities [this qctx query ent-sel]
    (transformEntitiesFn qctx query ent-sel))
  (field-by-name [this field-name]
    (get fieldByNames field-name)))

(defn server
  [resolvers fields transform-entities-fn]
  {:pre [(fn? transform-entities-fn)]}
  (let [trs-by-name (->> resolvers
                      (map (fn [resolver-opts]
                             (->TabularResolver
                               (impl.utils/get-safe resolver-opts :d2q.resolver/name)
                               (impl.utils/get-safe resolver-opts :d2q.resolver/compute))))
                      (impl.utils/index-by :name))
        field-names (into #{}
                      (map (fn [field-spec] (impl.utils/get-safe field-spec :d2q.field/name)))
                      fields)
        field->resolver+meta
        (let [field->resolver+metas
              (->> resolvers
                (mapcat (fn [{:as resolver-opts, res-name :d2q.resolver/name}]
                          (for [[field-name field-meta] (impl.utils/get-safe resolver-opts :d2q.resolver/field->meta)]
                            [field-name res-name field-meta])))
                (reduce
                  (fn [m [field-name res-name field-meta]]
                          (update m field-name (fn [v] (-> v (or []) (conj [res-name field-meta])))))
                  {}))
              undeclared-fields
              (->> field->resolver+metas keys (remove field-names) set)
              non-implemented
              (->> field-names (remove field->resolver+metas) set)
              implemented-several-times
              (->> field->resolver+metas
                (filter (fn [[_field-name resolver+metas]]
                          (> (count resolver+metas) 1)))
                (map (fn [[field-name resolver+metas]]
                       [field-name (mapv first resolver+metas)]))
                (into (sorted-map)))]
          (if-not (and (empty? undeclared-fields) (empty? non-implemented) (empty? implemented-several-times))
            (throw (ex-info
                     (str "Each Field must be implemented by exactly one Resolver; found problems with Fields "
                       (pr-str (into (sorted-set) cat [undeclared-fields non-implemented (keys implemented-several-times)]))
                       " and Resolvers "
                       (pr-str (into (sorted-set) cat (vals implemented-several-times))))
                     {:undeclared-fields (into (sorted-set) undeclared-fields)
                      :non-implemented-fields (into (sorted-set) non-implemented)
                      :fields-implemented-by-several-resolvers implemented-several-times}))
            (->> field->resolver+metas
              (map (fn [[field-name resolver+meta]]
                     [field-name (first resolver+meta)]))
              (into {}))))
        fields-by-name
        (->> fields
          (map (fn [field-spec]
                 (let [field-name (:d2q.field/name field-spec)
                       [resolver-name field-meta] (get field->resolver+meta field-name)]
                   (->Field
                     field-name
                     (not (impl.utils/get-safe field-spec :d2q.field/ref?))
                     (= :d2q.field.cardinality/many (:d2q.field/cardinality field-spec))
                     (get trs-by-name resolver-name)
                     field-meta))))
          (impl.utils/index-by :fieldName))]
    (->Server fields-by-name transform-entities-fn)))

;; ------------------------------------------------------------------------------
;; Sandbox

(comment
  (defrecord MyEntity [id])

  (defn my-resolve-1 [qctx fcall+is ent-sel]
    (p :my-resolve-1
      (mfd/success-deferred
        (d2q.datatypes/->ResolverResult
          (let [res
                (into []
                  (remove nil?)
                  (for [[ent-i ent] ent-sel
                        [fcall-i {:keys [d2q-fcall-field d2q-fcall-arg]}] fcall+is]
                    (case d2q-fcall-field
                      :myapp/scalar-42
                      (d2q.datatypes/->ResultCell ent-i fcall-i
                        42)
                      :myapp/scalar-arg
                      (d2q.datatypes/->ResultCell ent-i fcall-i
                        d2q-fcall-arg)
                      :myapp/scalar-exclude-me
                      nil
                      :myapp/scalar-entity-id
                      (d2q.datatypes/->ResultCell ent-i fcall-i
                        (:id ent))
                      :myapp/ref-one
                      (d2q.datatypes/->ResultCell ent-i fcall-i
                        (->MyEntity (str (:id ent) "/" d2q-fcall-arg)))
                      :myapp/ref-many
                      (d2q.datatypes/->ResultCell ent-i fcall-i
                        (map #(->MyEntity (str (:id ent) "/" %)) d2q-fcall-arg))
                      nil)))]
            res)
          []))))

  (def eng
    (server
      [{:d2q.resolver/name :my-resolver-1
        :d2q.resolver/compute #'my-resolve-1}]
      [{:d2q.field/name :myapp/scalar-42
        :d2q.field/ref? false
        :d2q.field/resolver :my-resolver-1}
       {:d2q.field/name :myapp/scalar-arg
        :d2q.field/ref? false
        :d2q.field/resolver :my-resolver-1}
       {:d2q.field/name :myapp/scalar-exclude-me
        :d2q.field/ref? false
        :d2q.field/resolver :my-resolver-1}
       {:d2q.field/name :myapp/scalar-entity-id
        :d2q.field/ref? false
        :d2q.field/resolver :my-resolver-1}

       {:d2q.field/name :myapp/ref-one
        :d2q.field/ref? true
        :d2q.field/cardinality :d2q.field.cardinality/one
        :d2q.field/resolver :my-resolver-1}
       {:d2q.field/name :myapp/ref-many
        :d2q.field/ref? true
        :d2q.field/cardinality :d2q.field.cardinality/many
        :d2q.field/resolver :my-resolver-1}]
      (fn [qctx query ent-sel]
        (mfd/success-deferred
          (let [get-early-result
                (fn [[i e]]
                  (when-let [res (get (meta e) :myapp/early-result)]
                    {:d2q-entcell-j i :d2q-rescell-value res}))
                early-results
                (into []
                  (keep get-early-result)
                  ent-sel)]
            {:d2q-ent-selection
             (if (seq early-results)
               (into [] (remove get-early-result) ent-sel)
               ent-sel)
             :d2q-errors
             []
             :d2q-early-results
             early-results})))))

  (require '[taoensso.tufte :as tufte :refer (defnp p profiled profile)])
  (tufte/add-basic-println-handler! {})

  (require '[criterium.core :as bench])

  (set! *warn-on-reflection* true)

  (def q
    [{:d2q-fcall-field :myapp/scalar-arg
      :d2q-fcall-arg "A"}
     :myapp/scalar-entity-id
     {:d2q-fcall-field :myapp/ref-many
      :d2q-fcall-key :many
      :d2q-fcall-arg (range 5)
      :d2q-fcall-subquery
      [{:d2q-fcall-field :myapp/scalar-arg
        :d2q-fcall-arg "B"}
       :myapp/scalar-entity-id
       {:d2q-fcall-field :myapp/ref-one
        :d2q-fcall-key :one
        :d2q-fcall-arg 0
        :d2q-fcall-subquery
        [{:d2q-fcall-field :myapp/scalar-arg
          :d2q-fcall-arg "C"}
         :myapp/scalar-entity-id]}]}])

  (def ent-sel
    (vec
      (for [i (range 2)]
        [i (-> (->MyEntity i)
             #_(with-meta {:myapp/early-result :coucou}))])))

  @(query* eng {} (normalize-query q) ent-sel)
  =>
  {:d2q-results [{:myapp/scalar-arg "A",
                  :myapp/scalar-entity-id 0,
                  :many [{:myapp/scalar-arg "B",
                          :myapp/scalar-entity-id "0/0",
                          :one {:myapp/scalar-arg "C", :myapp/scalar-entity-id "0/0/0"}}
                         {:myapp/scalar-arg "B",
                          :myapp/scalar-entity-id "0/1",
                          :one {:myapp/scalar-arg "C", :myapp/scalar-entity-id "0/1/0"}}
                         {:myapp/scalar-arg "B",
                          :myapp/scalar-entity-id "0/2",
                          :one {:myapp/scalar-arg "C", :myapp/scalar-entity-id "0/2/0"}}
                         {:myapp/scalar-arg "B",
                          :myapp/scalar-entity-id "0/3",
                          :one {:myapp/scalar-arg "C", :myapp/scalar-entity-id "0/3/0"}}]}
                 {:myapp/scalar-arg "A",
                  :myapp/scalar-entity-id 1,
                  :many [{:myapp/scalar-arg "B",
                          :myapp/scalar-entity-id "1/0",
                          :one {:myapp/scalar-arg "C", :myapp/scalar-entity-id "1/0/0"}}
                         {:myapp/scalar-arg "B",
                          :myapp/scalar-entity-id "1/1",
                          :one {:myapp/scalar-arg "C", :myapp/scalar-entity-id "1/1/0"}}
                         {:myapp/scalar-arg "B",
                          :myapp/scalar-entity-id "1/2",
                          :one {:myapp/scalar-arg "C", :myapp/scalar-entity-id "1/2/0"}}
                         {:myapp/scalar-arg "B",
                          :myapp/scalar-entity-id "1/3",
                          :one {:myapp/scalar-arg "C", :myapp/scalar-entity-id "1/3/0"}}]}],
   :d2q-errors ()}

  (profile {}
    (dotimes [_ 500]
      (p :serve
        @(query* eng {}
           (p :normalize-query (normalize-query q))
           ent-sel)
        )))

;                       pId      nCalls        Min        Max        MAD       Mean   Time%        Time
;
;                  :serve         500    64.00μs   353.00μs    16.33μs    76.00μs      98     38.00ms
;           :my-resolve-1       1,500     2.00μs    55.00μs     2.60μs     7.20μs      28     10.80ms
;:ent-results-from-rcells       1,500      0.0ns   239.00μs     1.22μs     2.53μs      10      3.80ms
;           :scalar-cells       1,500     1.00μs    58.00μs     1.06μs     2.52μs      10      3.78ms
;        :normalize-query         500     3.00μs    50.00μs     1.05μs     4.41μs       6      2.21ms
;         :to-many-subsel         500     3.00μs    26.00μs   983.28ns     3.96μs       5      1.98ms
;         :to-one-results         500     2.00μs    12.00μs  735.264ns     2.83μs       4      1.41ms
;                  :by-fr       1,500      0.0ns    56.00μs 422.6695555555556ns    853.0ns       3      1.28ms
;            :scalar-keys       1,500      0.0ns     9.00μs 493.6484444444444ns    681.0ns       3      1.02ms
;        :to-many-results         500     1.00μs    28.00μs   640.24ns     1.53μs       2    765.00μs
;          :to-one-subsel         500      0.0ns     4.00μs  185.696ns     1.02μs       1    508.00μs
;        :ref-fcall-infos       1,500      0.0ns    21.00μs 453.60422222222223ns    329.0ns       1    494.00μs
;
;              Clock Time                                                             100     38.69ms
;          Accounted Time                                                             171     66.05ms

  (bench/quick-bench
    @(query* eng {} (normalize-query q) ent-sel)
    )

  ;Evaluation count : 8304 in 6 samples of 1384 calls.
  ;           Execution time mean : 79.247647 µs
  ;  Execution time std-deviation : 13.099458 µs
  ; Execution time lower quantile : 62.669853 µs ( 2.5%)
  ; Execution time upper quantile : 94.525887 µs (97.5%)
  ;                 Overhead used : 2.112370 ns

  )

;; ------------------------------------------------------------------------------
;; Custom printing

(defmethod print-method ResultCell
  [v ^Writer wtr]
  (.write wtr "#d2q/result-cell")
  (.write wtr (pr-str (into {} v))))

(defn read-result-cell
  [{:as m, :keys [:d2q-entcell-j :d2q-fcall-i :d2q-rescell-value]}]
  {:pre [(integer? d2q-entcell-j) (integer? d2q-fcall-i)]}
  (d2q.datatypes/->ResultCell d2q-entcell-j d2q-fcall-i d2q-rescell-value))

