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
    :synth.fields/throw-arg (throw fcall-arg)
    :synth.fields/qctx qctx

    :synth.fields.refs/one-child
    (->SynthEnt (str (:ent-id ent) "/" fcall-arg))
    :synth.fields.refs/one-missing MISSING
    :synth.fields.refs/one-throw-arg (throw fcall-arg)
    :synth.fields.refs/many-children
    (into []
      (map (fn [i]
             (->SynthEnt (str (:ent-id ent) "/" i))))
      (range fcall-arg))
    :synth.fields.refs/many-missing MISSING
    :synth.fields.refs/many-throw-arg (throw fcall-arg)))

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
        [4 (d2q.datatypes/->FieldCall :synth.fields/throw-arg :throw-arg (ex-info "" {:error-tag "FZDSgjeizo"}) nil nil)]
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
    (fn [qctx [[fcall-i {err :d2q-fcall-arg}]] i+ents]
      (throw err))}
   {:d2q.resolver/name :synth.resolvers/error-deferred-resolver
    :d2q.resolver/compute
    (fn [qctx [[fcall-i {err :d2q-fcall-arg}]] i+ents]
      (mfd/error-deferred err))}
   {:d2q.resolver/name :synth.resolvers/error-returning-resolver
    :d2q.resolver/compute
    (fn [qctx [[fcall-i {err :d2q-fcall-arg}]] i+ents]
      (mfd/success-deferred {:d2q-errors [err]}))}])

(defn synthetic-transform-entities
  [qctx q ent-sel]
  (mfd/success-deferred
    (->> ent-sel
      (map (fn [[ent-i ent :as cell]]
             (if-let [err (:synth.ent/throw-at-transform-entities ent)]
               {:d2q-errors err}
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
              :d2q-fcall-arg (ex-info "aaaaa" {:u "FDZVZVVZ"})}
             {:d2q-fcall-field :synth.fields.refs/one-throw-arg
              :d2q-fcall-arg (ex-info "bbbbbb" {:u 136})}]}
           {:d2q-fcall-field :synth.fields/throw-arg
            :d2q-fcall-arg (ex-info "nooooo" {:z 342522})}
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
            :d2q-fcall-arg (ex-info "Resolver threw" {:d2q-fcall-i 0})
            :d2q-fcall-key "resolver-threw"}
           {:d2q-fcall-field :synth.fields/resolvers-returns-error
            :d2q-fcall-arg (ex-info "Resolver returned error" {})
            :d2q-fcall-key "resolver-returned-error"}
           {:d2q-fcall-field :synth.fields/resolver-deferred-error
            :d2q-fcall-arg (ex-info "Resolver returned error Deferred" {})
            :d2q-fcall-key "resolver-error-deferred"}]
        ents [(->SynthEnt 0)
              (assoc (->SynthEnt 1)
                :synth.ent/throw-at-transform-entities (ex-info "jfdksl" {:x 34242}))]]

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
                                      :d2q.error/query-trace ([:d2q.trace.op/query :...elided])},
                         :error/cause {:error/type clojure.lang.ExceptionInfo, :error/message "jfdksl", :error/data {:x 34242}}}]}
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
                  :error/message "Error in d2q Transform-Entities phase.",
                  :error/data {:d2q.error/type :d2q.error.type/transform-entities,
                               :d2q.error/query-trace ([:d2q.trace.op/query :...elided])},
                  :error/cause {:error/type clojure.lang.ExceptionInfo, :error/message "jfdksl", :error/data {:x 34242}}}
                 {:error/type clojure.lang.ExceptionInfo, :error/message "Resolver returned error", :error/data {}}
                 {:error/type clojure.lang.ExceptionInfo,
                  :error/message "Resolver returned error Deferred",
                  :error/data {}}
                 {:error/type clojure.lang.ExceptionInfo, :error/message "Resolver threw", :error/data {}}
                 {:error/type clojure.lang.ExceptionInfo, :error/message "aaaaa", :error/data {:u "FDZVZVVZ"}}
                 {:error/type clojure.lang.ExceptionInfo, :error/message "bbbbbb", :error/data {:u 136}}
                 {:error/type clojure.lang.ExceptionInfo, :error/message "nooooo", :error/data {:z 342522}}]}

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



(comment                                                    ;; TODO DEPRECATED

  (def db-example
    {:db/persons
     {"john-doe"
      {:person/id "john-doe"
       :person/email "john.doe@gmail.com"
       :person/age 18
       :person/address {:address/number "48"
                        :address/street "rue de Rome"
                        :address/city "Paris"}
       :person/notes ["blah" :blah 42]
       :animal/loves #{"alice-hacker" "minou"}}
      "alice-hacker"
      {:person/id "alice-hacker"
       :person/email "alice.hacker@gmail.com"
       :person/gender :person.gender/female
       :person/notes []
       :person/address nil}
      "bob-moran"
      {:person/id "bob-moran"
       :person/email "bob.moran@gmail.com"
       :person/age nil
       :person/gender :person.gender/male
       :person/address {:address/number "17"
                        :address/street "rue de Mars"
                        :address/city "Orléans"}}}
     :db/cats
     {"minou"
      {:cat/id "minou"
       :cat/name "Minou"
       :cat/owner "john-doe"
       :animal/loves #{"john-doe" "alice-hacker" "fuzzy-fur"}}
      "fuzzy-fur"
      {:cat/id "fuzzy-fur"
       :cat/name "Fuzzy Fur"
       :cat/owner "bob-moran"
       :animal/loves #{}}
      "wild-cat"
      {:cat/id "wild-cat"
       :cat/name "Wild Cat"
       :animal/loves #{}}}}
    )

  (defn qctx-example
    "Constructs an example Query Context."
    []
    {:db db-example})

  ;; ------------------------------------------------------------------------------
  ;; Reads

  (defn- basic-fr
    "Concise helper for defining field resolvers"
    ([field-name ref? many? doc]
     (basic-fr field-name ref? many? doc
       (if ref?
         (throw (ex-info "Cannot create compute function for entity-typed FR" {:d2q.field/name field-name}))
         (fn [_ obj _ _]
           (get obj field-name)))))
    ([field-name ref? many? doc compute]
     {:d2q.field/name field-name
      :doc doc
      :d2q.field/ref? ref?
      :d2q.field/cardinality (if many? :d2q.field.cardinality/many :d2q.field.cardinality/one)
      :bs.d2q.field/acl [:everyone-can-read]
      :bs.d2q.field/compute
      compute}))

  (def field-resolvers
    [{:d2q.field/name :find-person-by-id
      :d2q.field/ref? true
      :d2q.field/cardinality :d2q.field.cardinality/one
      :bs.d2q.field/acl [:everyone-can-read]
      :bs.d2q.field/compute
      (fn [qctx obj _ [person-id]]
        (when-let [p (get-in (:db qctx) [:db/persons person-id])]
          p))}
     {:d2q.field/name :find-cat-by-id
      :d2q.field/ref? true
      :d2q.field/cardinality :d2q.field.cardinality/one
      :bs.d2q.field/acl [:everyone-can-read]
      :bs.d2q.field/compute
      (fn [qctx obj _ [cat-id]]
        (when-let [c (get-in (:db qctx) [:db/cats cat-id])]
          c))}
     {:d2q.field/name :animal/loves
      :d2q.field/ref? true
      :d2q.field/cardinality :d2q.field.cardinality/many
      :bs.d2q.field/acl [:everyone-can-read]
      :bs.d2q.field/compute
      (fn [qctx obj _ _]
        (let [{cats :db/cats persons :db/persons} (:db qctx)]
          (->> obj :animal/loves set
            (mapv (fn [id]
                    (or
                      (get cats id)
                      (get persons id)))))
          ))}
     {:d2q.field/name :animal/loved-by
      :d2q.field/ref? true
      :d2q.field/cardinality :d2q.field.cardinality/many
      :bs.d2q.field/acl [:everyone-can-read]
      :bs.d2q.field/compute
      (fn [qctx obj _ _]
        (let [{cats :db/cats persons :db/persons} (:db qctx)
              id (or (:person/id obj) (:cat/id obj))]
          (->> (concat (vals cats) (vals persons))
            (filter (fn [a]
                      (contains? (or (:animal/loves a) #{}) id)))
            vec)
          ))}
     {:d2q.field/name :find-all-humans
      :d2q.field/ref? true
      :d2q.field/cardinality :d2q.field.cardinality/many
      :bs.d2q.field/acl [:everyone-can-read]
      :bs.d2q.field/compute
      (fn [{:keys [db]} _ _ _]
        (->> db :db/persons vals))}
     {:d2q.field/name :find-all-cats
      :d2q.field/ref? true
      :d2q.field/cardinality :d2q.field.cardinality/many
      :bs.d2q.field/acl [:everyone-can-read]
      :bs.d2q.field/compute
      (fn [{:keys [db]} _ _ _]
        (->> db :db/cats vals))}
     {:d2q.field/name :find-persons-with-gender
      :doc "Example of a cardinality-many entity-typed FR with params"
      :d2q.field/ref? true
      :d2q.field/cardinality :d2q.field.cardinality/many
      :bs.d2q.field/acl [:everyone-can-read]
      :bs.d2q.field/compute
      (fn [{:keys [db]} _ _ [gender]]
        (->> db :db/persons vals
          (filter #(-> % :person/gender (= gender)))))}

     (basic-fr :person/id false false "UID of a person")
     (basic-fr :person/email false false "Email of a person")
     (basic-fr :person/gender false false "Gender, an enum-valued FR, optional")
     (basic-fr :person/age false false "Age, optional")
     (basic-fr :person/address false false "Example of a map-valued scalar field")
     (basic-fr :person/notes false false "Example of a list-valued scalar field")
     (basic-fr :cat/id false false "")
     (basic-fr :cat/name false false "")
     (basic-fr :cat/owner true false "A to-one entity-typed field"
       (fn [{:keys [db]} obj _ _]
         (get-in db [:db/persons (:cat/owner obj)])))

     (basic-fr :one-nil true false "A to-one FR returning nil"
       (constantly nil))
     (basic-fr :many-nil true true "A to-many FR returning nil"
       (constantly nil))
     (basic-fr :scalar-nil false false "A scalar FR returning nil"
       (constantly nil))

     (basic-fr :scalar-throws false false ""
       (fn [_ _ _ _] (throw (ex-info "Scalar failed" {:error-data 42}))))
     (basic-fr :one-throws true false ""
       (fn [_ _ _ _] (throw (ex-info "To-one failed" {:error-data 42}))))
     (basic-fr :many-throws true false ""
       (fn [_ _ _ _] (throw (ex-info "To-many failed" {:error-data 42}))))
     ])

  (defn engine2
    [field-resolvers]
    (d2q.api/query-engine
      [(d2q.api/tabular-resolver-from-field-resolvers ::default field-resolvers)]
      (map #(assoc % :d2q.field/resolver ::default) field-resolvers)
      (fn [qctx query o+is] o+is)))



  (defn query-engine-example
    []
    (fn [qctx query obj]
      ;;  Execution time mean : 228.790078 µs on first sample query
      @((engine2 field-resolvers)
         qctx (d2q/normalize-query query) obj))
    ;; Execution time mean : 48.560712 µs on first sample query
    #_(d2q/engine field-resolvers
        {:demand-driven.authorization.read/accesses-for-object (constantly #{:everyone-can-read})}))

  (defn q
    "Runs a query on the example dataset"
    [query]
    (let [q-engine (query-engine-example)
          qctx (qctx-example)
          root-obj {}]
      (q-engine qctx query root-obj)))

  (defn fcall
    "Helper for writing Field Calls concisely"
    ([field-name nested]
     {:d2q.fcall/field field-name
      :d2q.fcall/nested nested})
    ([field-name key args]
     {:d2q.fcall/key key
      :d2q.fcall/field field-name
      :d2q.fcall/args args})
    ([field-name key args nested]
     {:d2q.fcall/key key
      :d2q.fcall/field field-name
      :d2q.fcall/args args
      :d2q.fcall/nested nested}))

  (fact "Canonical example"
    (q (let [human-q [:person/id :person/email :person/age :person/address
                      (fcall :animal/loves
                        [:person/id :cat/id])]]
         [(fcall :find-person-by-id "jd" ["john-doe"]
            human-q)
          (fcall :find-all-humans "humans" nil
            human-q)
          (fcall :find-all-cats "m" nil
            [:cat/id :cat/name
             {:d2q.fcall/field :cat/owner
              :d2q.fcall/nested
              [:person/email]}])]))
    =>
    {"jd" {:person/id "john-doe",
           :person/email "john.doe@gmail.com",
           :person/age 18,
           :person/address {:address/number "48", :address/street "rue de Rome", :address/city "Paris"},
           :animal/loves [{:person/id "alice-hacker"} {:cat/id "minou"}]},
     "humans" [{:person/id "john-doe",
                :person/email "john.doe@gmail.com",
                :person/age 18,
                :person/address {:address/number "48", :address/street "rue de Rome", :address/city "Paris"},
                :animal/loves [{:person/id "alice-hacker"} {:cat/id "minou"}]}
               {:person/id "alice-hacker", :person/email "alice.hacker@gmail.com", :animal/loves []}
               {:person/id "bob-moran",
                :person/email "bob.moran@gmail.com",
                :person/address {:address/number "17", :address/street "rue de Mars", :address/city "Orléans"},
                :animal/loves []}],
     "m" [{:cat/id "minou", :cat/name "Minou", :cat/owner {:person/email "john.doe@gmail.com"}}
          {:cat/id "fuzzy-fur", :cat/name "Fuzzy Fur", :cat/owner {:person/email "bob.moran@gmail.com"}}
          {:cat/id "wild-cat", :cat/name "Wild Cat"}]}
    )

  (fact "When entity does not exist, not added to the result"
    (q [{:d2q.fcall/key "x"
         :d2q.fcall/field :find-person-by-id
         :d2q.fcall/args ["does not exist"]
         :d2q.fcall/nested
         [:person/id
          {:d2q.fcall/field :animal/loves
           :d2q.fcall/nested
           [:person/id :cat/id]}]}])
    => {}

    (q [{:d2q.fcall/key "w"
         :d2q.fcall/field :find-cat-by-id
         :d2q.fcall/args ["wild-cat"]
         :d2q.fcall/nested
         [:cat/id
          {:d2q.fcall/field :cat/owner
           :d2q.fcall/nested
           [:person/id]}]}])
    => {"w" {:cat/id "wild-cat"}}
    )

  (fact "When a Field Resolver returns nil, the key is not added to the result."
    (q [:many-nil :one-nil :scalar-nil])
    => {}

    (fact "When an Entity-typed Field Resolver returns nil, the nested fields are not computed"
      (q [(fcall :one-nil
            [:scalar-throws :one-throws :many-throws])])
      => {}))

  (fact "When a Field Resolver throws, the whole query fails, with informative data about the error."
    (tabular
      (fact
        (try
          (q [(fcall ?field-name ?field-name "aaaaaargs")])
          :should-have-failed
          (catch Throwable err
            (ex-data err)))
        =>
        (contains
          {:q-field {:d2q.fcall/field ?field-name,
                     :d2q.fcall/key ?field-name
                     :d2q.fcall/args "aaaaaargs"},
           :d2q.error/type :d2q.error.type/field-resolver-failed-to-compute
           :error-data 42})
        )
      ?field-name
      :scalar-throws
      :one-throws
      :many-throws
      )))










