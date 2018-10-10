(ns d2q.test.api
  (:require [clojure.test :refer :all]
            [midje.sweet :refer :all]

            [d2q.api :as d2q :refer :all]
            [d2q.datatypes]
            [manifold.deferred :as mfd]
            [vvvvalvalval.supdate.api :as supd]))

(defn dataify-ex
  [^Throwable ex]
  (let [cause (.getCause ex)]
    (cond-> {:error/type (-> ex class .getName symbol)
             :error/message (.getMessage ex)
             :error/data (ex-data ex)}
      (some? cause)
      (assoc :error/cause (dataify-ex cause)))))

(defn errors->ex-data
  [res]
  (update res :d2q-errors
    #(->> %
       (map dataify-ex)
       (sort-by :error/message)
       vec)))


;; ------------------------------------------------------------------------------
;; Example with 'synthetic' fields - not representing a real-word domain, but useful for testing

(defrecord SynthEnt [ent-id])

(defn synthetic-resolve-datum
  [qctx field-name fcall-arg ent MISSING]
  (case field-name
    :synth.fields/ent-id (:ent-id ent)
    :synth.fields/always-42 42
    :synth.fields/return-arg fcall-arg
    :synth.fields/inc-arg (inc fcall-arg)
    :synth.fields/missing MISSING
    :synth.fields/throw-arg (throw (apply ex-info fcall-arg))
    :synth.fields/qctx qctx

    :synth.fields.refs/one-child
    (->SynthEnt (str (:ent-id ent) "/" fcall-arg))
    :synth.fields.refs/one-missing MISSING
    :synth.fields.refs/one-throw-arg (throw (apply ex-info fcall-arg))
    :synth.fields.refs/many-children
    (into []
      (map (fn [i]
             (->SynthEnt (str (:ent-id ent) "/" i))))
      (range fcall-arg))
    :synth.fields.refs/many-missing MISSING
    :synth.fields.refs/many-throw-arg (throw (apply ex-info fcall-arg))))

(defn synthetic-resolve
  [qctx fcall-tuples ent-sel]
  (mfd/success-deferred
    (let [MISSING (Object.)]
      (->>
        (for [[fcall-i {:keys [d2q-fcall-field d2q-fcall-arg]}] fcall-tuples
              [ent-i ent] ent-sel]
          (try
            (let [v (synthetic-resolve-datum qctx d2q-fcall-field d2q-fcall-arg ent MISSING)]
              (when-not (identical? v MISSING)
                {:d2q-res-cells (d2q.datatypes/->ResultCell ent-i fcall-i v)}))
            (catch Throwable err
              {:d2q-errors err})))
        (remove nil?)
        (reduce
          #(merge-with conj %1 %2)
          {:d2q-res-cells [], :d2q-errors []})))))

(fact "Examples of synthetic-resolve"
  (->
    @(synthetic-resolve :qctx
       [[0 (d2q.datatypes/->FieldCall :synth.fields/ent-id :ent-id-1 nil nil nil)]
        [1 (d2q.datatypes/->FieldCall :synth.fields/return-arg :return-arg "hello" nil nil)]
        [2 (d2q.datatypes/->FieldCall :synth.fields.refs/one-child :one-child "a" nil nil)]
        [3 (d2q.datatypes/->FieldCall :synth.fields/missing :missing nil nil nil)]
        [4 (d2q.datatypes/->FieldCall :synth.fields/throw-arg :throw-arg ["" {:error-tag "FZDSgjeizo"}] nil nil)]
        [5 (d2q.datatypes/->FieldCall :synth.fields/qctx :qctx nil nil nil)]
        ]
       [[0 (->SynthEnt "a")]
        [1 (->SynthEnt "b")]])
    errors->ex-data)
  =>
  '{:d2q-res-cells [#d2q/result-cell{:d2q-entcell-i 0, :d2q-fcall-i 0, :d2q-rescell-value "a"}
                    #d2q/result-cell{:d2q-entcell-i 1, :d2q-fcall-i 0, :d2q-rescell-value "b"}
                    #d2q/result-cell{:d2q-entcell-i 0, :d2q-fcall-i 1, :d2q-rescell-value "hello"}
                    #d2q/result-cell{:d2q-entcell-i 1, :d2q-fcall-i 1, :d2q-rescell-value "hello"}
                    #d2q/result-cell{:d2q-entcell-i 0,
                                     :d2q-fcall-i 2,
                                     :d2q-rescell-value #d2q.test.api.SynthEnt{:ent-id "a/a"}}
                    #d2q/result-cell{:d2q-entcell-i 1,
                                     :d2q-fcall-i 2,
                                     :d2q-rescell-value #d2q.test.api.SynthEnt{:ent-id "b/a"}}
                    #d2q/result-cell{:d2q-entcell-i 0, :d2q-fcall-i 5, :d2q-rescell-value :qctx}
                    #d2q/result-cell{:d2q-entcell-i 1, :d2q-fcall-i 5, :d2q-rescell-value :qctx}],
    :d2q-errors [{:error/type clojure.lang.ExceptionInfo, :error/message "", :error/data {:error-tag "FZDSgjeizo"}}
                 {:error/type clojure.lang.ExceptionInfo, :error/message "", :error/data {:error-tag "FZDSgjeizo"}}]}



  (fact "When no Field calls"
    (->
      @(synthetic-resolve :qctx
         []
         [[0 (->SynthEnt "a")]
          [1 (->SynthEnt "b")]])
      (update :d2q-errors #(mapv ex-data %)))
    => {:d2q-res-cells [], :d2q-errors []})

  (fact "When no entities"
    (->
      @(synthetic-resolve :qctx
         [[0 (d2q.datatypes/->FieldCall :synth.fields/ent-id :ent-id-1 nil nil nil)]
          [1 (d2q.datatypes/->FieldCall :synth.fields/return-arg :return-arg "hello" nil nil)]
          [2 (d2q.datatypes/->FieldCall :synth.fields.refs/one-child :one-child "a" nil nil)]
          [3 (d2q.datatypes/->FieldCall :synth.fields/missing :missing nil nil nil)]
          [4 (d2q.datatypes/->FieldCall :synth.fields/throw-arg :throw-arg (ex-info "" {:error-tag "FZDSgjeizo"}) nil nil)]
          [5 (d2q.datatypes/->FieldCall :synth.fields/qctx :qctx nil nil nil)]
          ]
         [])

      errors->ex-data)
    => {:d2q-res-cells [], :d2q-errors []})
  )

(def synthetic-fields
  [{:d2q.field/name :synth.fields/ent-id
    :d2q.field/resolver :synth.resolvers/resolver-1
    :d2q.field/ref? false}
   {:d2q.field/name :synth.fields/always-42
    :d2q.field/resolver :synth.resolvers/resolver-2
    :d2q.field/ref? false}
   {:d2q.field/name :synth.fields/return-arg
    :d2q.field/resolver :synth.resolvers/resolver-1
    :d2q.field/ref? false}
   {:d2q.field/name :synth.fields/inc-arg
    :d2q.field/resolver :synth.resolvers/resolver-2
    :d2q.field/ref? false}
   {:d2q.field/name :synth.fields/missing
    :d2q.field/resolver :synth.resolvers/resolver-1
    :d2q.field/ref? false}
   {:d2q.field/name :synth.fields/throw-arg
    :d2q.field/resolver :synth.resolvers/resolver-2
    :d2q.field/ref? false}
   {:d2q.field/name :synth.fields/qctx
    :d2q.field/resolver :synth.resolvers/resolver-1
    :d2q.field/ref? false}

   {:d2q.field/name :synth.fields.refs/one-child
    :d2q.field/resolver :synth.resolvers/resolver-1
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/one}
   {:d2q.field/name :synth.fields.refs/one-missing
    :d2q.field/resolver :synth.resolvers/resolver-2
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/one}
   {:d2q.field/name :synth.fields.refs/one-throw-arg
    :d2q.field/resolver :synth.resolvers/resolver-1
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/one}
   {:d2q.field/name :synth.fields.refs/many-children
    :d2q.field/resolver :synth.resolvers/resolver-2
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/many}
   {:d2q.field/name :synth.fields.refs/many-missing
    :d2q.field/resolver :synth.resolvers/resolver-1
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/many}
   {:d2q.field/name :synth.fields.refs/many-throw-arg
    :d2q.field/resolver :synth.resolvers/resolver-2
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/many}

   {:d2q.field/name :synth.fields/resolver-throws
    :d2q.field/resolver :synth.resolvers/throwing-resolver
    :d2q.field/ref? false}
   {:d2q.field/name :synth.fields/resolvers-returns-error
    :d2q.field/resolver :synth.resolvers/error-returning-resolver
    :d2q.field/ref? false}
   {:d2q.field/name :synth.fields/resolver-deferred-error
    :d2q.field/resolver :synth.resolvers/error-deferred-resolver
    :d2q.field/ref? false}
   ])

(def synthetic-resolvers
  [{:d2q.resolver/name :synth.resolvers/resolver-1
    :d2q.resolver/compute #'synthetic-resolve}
   {:d2q.resolver/name :synth.resolvers/resolver-2
    :d2q.resolver/compute #'synthetic-resolve}
   {:d2q.resolver/name :synth.resolvers/throwing-resolver
    :d2q.resolver/compute
    (fn [qctx [[fcall-i {[msg data] :d2q-fcall-arg}]] i+ents]
      (throw (ex-info msg data)))}
   {:d2q.resolver/name :synth.resolvers/error-deferred-resolver
    :d2q.resolver/compute
    (fn [qctx [[fcall-i {[msg data] :d2q-fcall-arg}]] i+ents]
      (mfd/error-deferred (ex-info msg data)))}
   {:d2q.resolver/name :synth.resolvers/error-returning-resolver
    :d2q.resolver/compute
    (fn [qctx [[fcall-i {[msg data] :d2q-fcall-arg}]] i+ents]
      (mfd/success-deferred {:d2q-errors [(ex-info msg data)]}))}])

(defn synthetic-transform-entities
  [qctx q ent-sel]
  (mfd/success-deferred
    (->> ent-sel
      (map (fn [[ent-i ent :as cell]]
             (if-let [[msg data] (:synth.ent/throw-at-transform-entities ent)]
               (let [err (ex-info msg data)]
                 {:d2q-errors err})
               (if-let [res (:synth.ent/early-result ent)]
                 {:d2q-early-results {:d2q-entcell-i ent-i :d2q-rescell-value res}}
                 {:d2q-ent-selection cell}))))
      (reduce
        #(merge-with conj %1 %2)
        {:d2q-ent-selection [] :d2q-errors [] :d2q-early-results []}))))

(defn synth-server
  []
  (d2q.api/server
    {:d2q.server/fields synthetic-fields
     :d2q.server/resolvers synthetic-resolvers
     :d2q.server/transform-entities-fn synthetic-transform-entities}))


(fact "Synthetic query example"
  (let [qctx nil
        q [:synth.fields/ent-id
           :synth.fields/always-42
           {:d2q-fcall-field :synth.fields/return-arg
            :d2q-fcall-key "scalar-k0"
            :d2q-fcall-arg "HI"}
           :synth.fields/missing
           {:d2q-fcall-field :synth.fields.refs/one-child
            :d2q-fcall-key "child1"
            :d2q-fcall-subquery
            [:synth.fields/ent-id
             :synth.fields/always-42
             {:d2q-fcall-field :synth.fields/return-arg
              :d2q-fcall-key "scalar-k1"
              :d2q-fcall-arg "foo"}
             {:d2q-fcall-field :synth.fields.refs/many-throw-arg
              :d2q-fcall-arg ["aaaaa" {:u "FDZVZVVZ"}]}
             {:d2q-fcall-field :synth.fields.refs/one-throw-arg
              :d2q-fcall-arg ["bbbbbb" {:u 136}]}]}
           {:d2q-fcall-field :synth.fields/throw-arg
            :d2q-fcall-arg ["nooooo" {:z 342522}]}
           {:d2q-fcall-field :synth.fields.refs/many-children
            :d2q-fcall-key "children2"
            :d2q-fcall-arg 3
            :d2q-fcall-subquery
            [:synth.fields/ent-id
             :synth.fields/always-42
             {:d2q-fcall-field :synth.fields/return-arg
              :d2q-fcall-key "scalar-k2"
              :d2q-fcall-arg "bar"}
             :synth.fields.refs/one-missing
             :synth.fields.refs/many-missing]}
           {:d2q-fcall-field :synth.fields/resolver-throws
            :d2q-fcall-arg ["I threw an error during resolution" {:d2q-fcall-i 0}]
            :d2q-fcall-key "resolver-threw"}
           {:d2q-fcall-field :synth.fields/resolvers-returns-error
            :d2q-fcall-arg ["I returned an error during resolution" {}]
            :d2q-fcall-key "resolver-returned-error"}
           {:d2q-fcall-field :synth.fields/resolver-deferred-error
            :d2q-fcall-arg ["I returned an error Deferred during resolution" {}]
            :d2q-fcall-key "resolver-error-deferred"}]
        ents [(->SynthEnt 0)
              (assoc (->SynthEnt 1)
                :synth.ent/throw-at-transform-entities ["jfdksl" {:x 34242}])]]

    (fact "Empty entities"
      (-> @(d2q.api/query (synth-server) qctx q [])
        errors->ex-data)
      => {:d2q-results [] :d2q-errors []}
      )

    (fact "Empty query"
      (-> @(d2q.api/query (synth-server) qctx [] ents)
        errors->ex-data)
      => '{:d2q-results [{} {}],
           :d2q-errors [{:error/type clojure.lang.ExceptionInfo,
                         :error/message "Error in d2q Transform-Entities phase.",
                         :error/data {:d2q.error/type :d2q.error.type/transform-entities,
                                      :d2q.error/query-trace ([:d2q.trace.op/query
                                                               #d2q.datatypes.Query{:d2q-query-id nil,
                                                                                    :d2q-query-fcalls [:...elided],
                                                                                    :d2q-rev-query-path ()}])},
                         :error/cause {:error/type clojure.lang.ExceptionInfo,
                                       :error/message "jfdksl",
                                       :error/data {:x 34242}}}]}
      )

    ;; FIXME fix tests for new error reporting (Val, 10 Apr 2018)

    (-> @(d2q.api/query (synth-server) qctx q ents)
      errors->ex-data))
  =>
  '{:d2q-results [{:synth.fields/ent-id 0,
                   "scalar-k0" "HI",
                   "child1" {:synth.fields/ent-id "0/", "scalar-k1" "foo", :synth.fields/always-42 42},
                   :synth.fields/always-42 42,
                   "children2" [{:synth.fields/ent-id "0/0", "scalar-k2" "bar", :synth.fields/always-42 42}
                                {:synth.fields/ent-id "0/1", "scalar-k2" "bar", :synth.fields/always-42 42}
                                {:synth.fields/ent-id "0/2", "scalar-k2" "bar", :synth.fields/always-42 42}]}
                  {}],
    :d2q-errors [{:error/type clojure.lang.ExceptionInfo,
                  :error/message "Error in d2q Resolver :synth.resolvers/error-deferred-resolver",
                  :error/data {:d2q.error/type :d2q.error.type/resolver,
                               :d2q.resolver/name :synth.resolvers/error-deferred-resolver,
                               :d2q.error/query-trace ([:d2q.trace.op/resolver
                                                        {:d2q.resolver/name :synth.resolvers/error-deferred-resolver}]
                                                        [:d2q.trace.op/query
                                                         #d2q.datatypes.Query{:d2q-query-id nil,
                                                                              :d2q-query-fcalls [:...elided],
                                                                              :d2q-rev-query-path ()}])},
                  :error/cause {:error/type clojure.lang.ExceptionInfo,
                                :error/message "I returned an error Deferred during resolution",
                                :error/data {}}}
                 {:error/type clojure.lang.ExceptionInfo,
                  :error/message "Error in d2q Resolver :synth.resolvers/error-returning-resolver",
                  :error/data {:d2q.error/type :d2q.error.type/resolver,
                               :d2q.resolver/name :synth.resolvers/error-returning-resolver,
                               :d2q.error/query-trace ([:d2q.trace.op/resolver
                                                        {:d2q.resolver/name :synth.resolvers/error-returning-resolver}]
                                                        [:d2q.trace.op/query
                                                         #d2q.datatypes.Query{:d2q-query-id nil,
                                                                              :d2q-query-fcalls [:...elided],
                                                                              :d2q-rev-query-path ()}])},
                  :error/cause {:error/type clojure.lang.ExceptionInfo,
                                :error/message "I returned an error during resolution",
                                :error/data {}}}
                 {:error/type clojure.lang.ExceptionInfo,
                  :error/message "Error in d2q Resolver :synth.resolvers/resolver-1",
                  :error/data {:d2q.error/type :d2q.error.type/resolver,
                               :d2q.resolver/name :synth.resolvers/resolver-1,
                               :d2q.error/query-trace ([:d2q.trace.op/resolver
                                                        {:d2q.resolver/name :synth.resolvers/resolver-1}]
                                                        [:d2q.trace.op/query
                                                         #d2q.datatypes.Query{:d2q-query-id nil,
                                                                              :d2q-query-fcalls [:...elided],
                                                                              :d2q-rev-query-path (:d2q-fcall-subquery 4)}]
                                                        [:d2q.trace.op/field-call
                                                         #d2q.datatypes.FieldCall{:d2q-fcall-field :synth.fields.refs/one-child,
                                                                                  :d2q-fcall-key "child1",
                                                                                  :d2q-fcall-arg nil,
                                                                                  :d2q-fcall-subquery [:...elided],
                                                                                  :d2q-fcall-rev-query-path (4)}]
                                                        [:d2q.trace.op/resolver
                                                         {:d2q.resolver/name :synth.resolvers/resolver-1}]
                                                        [:d2q.trace.op/query
                                                         #d2q.datatypes.Query{:d2q-query-id nil,
                                                                              :d2q-query-fcalls [:...elided],
                                                                              :d2q-rev-query-path ()}])},
                  :error/cause {:error/type clojure.lang.ExceptionInfo, :error/message "bbbbbb", :error/data {:u 136}}}
                 {:error/type clojure.lang.ExceptionInfo,
                  :error/message "Error in d2q Resolver :synth.resolvers/resolver-2",
                  :error/data {:d2q.error/type :d2q.error.type/resolver,
                               :d2q.resolver/name :synth.resolvers/resolver-2,
                               :d2q.error/query-trace ([:d2q.trace.op/resolver
                                                        {:d2q.resolver/name :synth.resolvers/resolver-2}]
                                                        [:d2q.trace.op/query
                                                         #d2q.datatypes.Query{:d2q-query-id nil,
                                                                              :d2q-query-fcalls [:...elided],
                                                                              :d2q-rev-query-path (:d2q-fcall-subquery 4)}]
                                                        [:d2q.trace.op/field-call
                                                         #d2q.datatypes.FieldCall{:d2q-fcall-field :synth.fields.refs/one-child,
                                                                                  :d2q-fcall-key "child1",
                                                                                  :d2q-fcall-arg nil,
                                                                                  :d2q-fcall-subquery [:...elided],
                                                                                  :d2q-fcall-rev-query-path (4)}]
                                                        [:d2q.trace.op/resolver
                                                         {:d2q.resolver/name :synth.resolvers/resolver-1}]
                                                        [:d2q.trace.op/query
                                                         #d2q.datatypes.Query{:d2q-query-id nil,
                                                                              :d2q-query-fcalls [:...elided],
                                                                              :d2q-rev-query-path ()}])},
                  :error/cause {:error/type clojure.lang.ExceptionInfo,
                                :error/message "aaaaa",
                                :error/data {:u "FDZVZVVZ"}}}
                 {:error/type clojure.lang.ExceptionInfo,
                  :error/message "Error in d2q Resolver :synth.resolvers/resolver-2",
                  :error/data {:d2q.error/type :d2q.error.type/resolver,
                               :d2q.resolver/name :synth.resolvers/resolver-2,
                               :d2q.error/query-trace ([:d2q.trace.op/resolver
                                                        {:d2q.resolver/name :synth.resolvers/resolver-2}]
                                                        [:d2q.trace.op/query
                                                         #d2q.datatypes.Query{:d2q-query-id nil,
                                                                              :d2q-query-fcalls [:...elided],
                                                                              :d2q-rev-query-path ()}])},
                  :error/cause {:error/type clojure.lang.ExceptionInfo, :error/message "nooooo", :error/data {:z 342522}}}
                 {:error/type clojure.lang.ExceptionInfo,
                  :error/message "Error in d2q Resolver :synth.resolvers/throwing-resolver, on Field :synth.fields/resolver-throws at key \"resolver-threw\"",
                  :error/data {:d2q.error/type :d2q.error.type/resolver,
                               :d2q.resolver/name :synth.resolvers/throwing-resolver,
                               :d2q.error/query-trace ([:d2q.trace.op/resolver
                                                        {:d2q.resolver/name :synth.resolvers/throwing-resolver}]
                                                        [:d2q.trace.op/query
                                                         #d2q.datatypes.Query{:d2q-query-id nil,
                                                                              :d2q-query-fcalls [:...elided],
                                                                              :d2q-rev-query-path ()}]),
                               :d2q.error/field-call #d2q.datatypes.FieldCall{:d2q-fcall-field :synth.fields/resolver-throws,
                                                                              :d2q-fcall-key "resolver-threw",
                                                                              :d2q-fcall-arg ["I threw an error during resolution"
                                                                                              {:d2q-fcall-i 0}],
                                                                              :d2q-fcall-subquery nil,
                                                                              :d2q-fcall-rev-query-path (7)}},
                  :error/cause {:error/type clojure.lang.ExceptionInfo,
                                :error/message "I threw an error during resolution",
                                :error/data {:d2q-fcall-i 0}}}
                 {:error/type clojure.lang.ExceptionInfo,
                  :error/message "Error in d2q Transform-Entities phase.",
                  :error/data {:d2q.error/type :d2q.error.type/transform-entities,
                               :d2q.error/query-trace ([:d2q.trace.op/query
                                                        #d2q.datatypes.Query{:d2q-query-id nil,
                                                                             :d2q-query-fcalls [:...elided],
                                                                             :d2q-rev-query-path ()}])},
                  :error/cause {:error/type clojure.lang.ExceptionInfo, :error/message "jfdksl", :error/data {:x 34242}}}]}


  )


(fact "into-resolver-result"
  (fact "with transducer"
    (->
      (d2q.api/into-resolver-result
        (map-indexed
          (fn [ent-i n]
            (try
              (d2q.api/result-cell ent-i 0
                (/ 1 n))
              (catch Throwable err
                (ex-info "aaaaaarrrrg"
                  {:n n}
                  err)))))
        (range -2 3))
      errors->ex-data)

    =>
    '{:d2q-res-cells [#d2q/result-cell{:d2q-entcell-i 0, :d2q-fcall-i 0, :d2q-rescell-value -1/2}
                      #d2q/result-cell{:d2q-entcell-i 1, :d2q-fcall-i 0, :d2q-rescell-value -1}
                      #d2q/result-cell{:d2q-entcell-i 3, :d2q-fcall-i 0, :d2q-rescell-value 1}
                      #d2q/result-cell{:d2q-entcell-i 4, :d2q-fcall-i 0, :d2q-rescell-value 1/2}],
      :d2q-errors [{:error/type clojure.lang.ExceptionInfo,
                    :error/message "aaaaaarrrrg",
                    :error/data {:n 0},
                    :error/cause {:error/type java.lang.ArithmeticException,
                                  :error/message "Divide by zero",
                                  :error/data nil}}]})

  (fact "without transducer"
    (->
      (d2q.api/into-resolver-result
        (map-indexed
          (fn [ent-i n]
            (try
              (d2q.api/result-cell ent-i 0
                (/ 1 n))
              (catch Throwable err
                (ex-info "aaaaaarrrrg"
                  {:n n}
                  err))))
          (range -2 3)))
      errors->ex-data)
    =>
    '{:d2q-res-cells [#d2q/result-cell{:d2q-entcell-i 0, :d2q-fcall-i 0, :d2q-rescell-value -1/2}
                      #d2q/result-cell{:d2q-entcell-i 1, :d2q-fcall-i 0, :d2q-rescell-value -1}
                      #d2q/result-cell{:d2q-entcell-i 3, :d2q-fcall-i 0, :d2q-rescell-value 1}
                      #d2q/result-cell{:d2q-entcell-i 4, :d2q-fcall-i 0, :d2q-rescell-value 1/2}],
      :d2q-errors [{:error/type clojure.lang.ExceptionInfo,
                    :error/message "aaaaaarrrrg",
                    :error/data {:n 0},
                    :error/cause {:error/type java.lang.ArithmeticException,
                                  :error/message "Divide by zero",
                                  :error/data nil}}]})
  )
