(ns d2q.test.example.persons
  (:require [d2q.api :as d2q]
            [manifold.deferred :as mfd]))

;; A tutorial demonstrating basic usage of d2q.

;; Table of Contents:
;; 1. DOMAIN
;; 2. QUERYING
;; 3. DEFINING AND IMPLEMENTING THE SERVER

;; If you want to experiment with the examples at the REPL,
;; start by loading this entire file in the REPL,
;; (for instance with the command: clj -i test/d2q/test/example/persons.clj -e "(in-ns 'd2q.test.example.persons)"  -r)
;; then you can evaluate the forms in the (comment ...) blocks.

;;;; ****************************************************************
;;;; 1. DOMAIN
;;;; ****************************************************************

;; Our example domain consists of persons, their names, and family bonds.

;; We will use the following 'database' data structure to support
;; the data for our examples:

(def app-db
  {:myapp.db/persons-by-id
   {"luke-skywalker"
    {:myapp.person/id "luke-skywalker"
     :myapp.person/first-name "Luke"
     :myapp.person/last-name "Skywalker"
     :myapp.person/father-id "anakin-skywalker"
     :myapp.person/mother-id "padme-amidala"}
    "leia-organa"
    {:myapp.person/id "leia-organa"
     :myapp.person/first-name "Leïa"
     :myapp.person/last-name "Organa"
     :myapp.person/father-id "anakin-skywalker"
     :myapp.person/mother-id "padme-amidala"}
    "anakin-skywalker"
    {:myapp.person/id "anakin-skywalker"
     :myapp.person/first-name "Anakin"
     :myapp.person/last-name "Skywalker"}
    "padme-amidala"
    {:myapp.person/id "padme-amidala"
     :myapp.person/first-name "Padme"
     :myapp.person/last-name "Amidala"}}})

;; In our examples, the data-fetching will be implemented by navigating
;; this in-memory data structure; but you have to imagine that we could apply the
;; same principles to a regular database engine such as PostgreSQL, Datomic
;; or ElasticSearch.

;; We will build a d2q Query Server which serves the following Fields,
;; which names should be most self-explanatory:

#{:myapp.persons/person-of-id
  :myapp.person/id
  :myapp.person/first-name
  :myapp.person/last-name
  :myapp.person/full-name
  :myapp.person/mother
  :myapp.person/father
  :myapp.person/parents
  :myapp.person/children}

;;;; ****************************************************************
;;;; 2. QUERYING
;;;; ****************************************************************

;;;; ----------------------------------------------------------------
;;;; Calling the query server

;; We assume we have already implemented a d2q Query Server for our domain.
;; We will actually implement it in the next section.
(declare app-server)

;; Queries are executed on _Entities_, therefore we need to choose a
;; (server-side) representation for Entities.
;; Any type of values is allowed, as long as our data-fetching code
;; can make sense of it; in this example, we choose to use Clojure records
;; to provide a good visual contrast, but we could also use plain old Clojure
;; maps or custom Java classes for example.
(defrecord Person [person-id])

;; Let's start with a minimalistic query:

(comment
  (let [;; The application Context in which we execute our query.
        ;; In our minimalistic example, the Context only consists
        ;; of a db, but in a real-world scenario, it would likely
        ;; have more components, such as various database connections,
        ;; configuration, authentication information
        qctx {:db app-db}
        ;; Our Query, which describes the data we're interested in and the
        ;; shape of the result. In this case, we're interested in 3 Fields,
        ;; to be packaged in a flat map.
        q [:myapp.person/id
           :myapp.person/first-name
           :myapp.person/last-name]
        ;; The batch of Entities to which we apply the query.
        entities
        [(->Person "luke-skywalker")
         (->Person "leia-organa")]]
    @(d2q/query app-server qctx q entities))
  =>
  {:d2q-results [{:myapp.person/id "luke-skywalker",
                  :myapp.person/first-name "Luke",
                  :myapp.person/last-name "Skywalker"}
                 {:myapp.person/id "leia-organa",
                  :myapp.person/first-name "Leïa",
                  :myapp.person/last-name "Organa"}],
   :d2q-errors ()}

  ;; What just happened? The d2q query engine has called the above-defined resolvers
  ;; with the appropriate inputs and assembled the outputs into a tree data structure.
  )

;;;; ----------------------------------------------------------------
;;;; Normalized query form

;; The query syntax that we used above is actually a shorthand syntax
;; for a more verbose 'normalized' form.

;; The above query is equivalent to this query in normalized form:

(comment
  (let [qctx {:db app-db}
        q {:d2q-query-fcalls
           [{:d2q-fcall-field :myapp.person/id
             :d2q-fcall-key :myapp.person/id
             :d2q-fcall-arg nil}
            {:d2q-fcall-field :myapp.person/first-name
             :d2q-fcall-key :myapp.person/first-name
             :d2q-fcall-arg nil}
            {:d2q-fcall-field :myapp.person/last-name
             :d2q-fcall-key :myapp.person/last-name
             :d2q-fcall-arg nil}]}
        entities
        [(->Person "luke-skywalker")
         (->Person "leia-organa")]]
    @(d2q/query app-server qctx q entities))
  =>
  {:d2q-results [{:myapp.person/id "luke-skywalker",
                  :myapp.person/first-name "Luke",
                  :myapp.person/last-name "Skywalker"}
                 {:myapp.person/id "leia-organa",
                  :myapp.person/first-name "Leïa",
                  :myapp.person/last-name "Organa"}],
   :d2q-errors ()}
  )

;; The normalized query form is less concise, but more expressive.
;; For example, we can use it to customize the returned keys:

(comment
  (let [qctx {:db app-db}
        q {:d2q-query-fcalls
           [{:d2q-fcall-field :myapp.person/id
             :d2q-fcall-key :id
             :d2q-fcall-arg nil}
            {:d2q-fcall-field :myapp.person/first-name
             :d2q-fcall-key "FIRST_NAME"
             :d2q-fcall-arg nil}
            {:d2q-fcall-field :myapp.person/last-name
             :d2q-fcall-key 'last_name
             :d2q-fcall-arg nil}]}
        entities
        [(->Person "luke-skywalker")
         (->Person "leia-organa")]]
    @(d2q/query app-server qctx q entities))
  =>
  {:d2q-results [{:id "luke-skywalker",
                  "FIRST_NAME" "Luke",
                  last_name "Skywalker"}
                 {:id "leia-organa",
                  "FIRST_NAME" "Leïa",
                  last_name "Organa"}],
   :d2q-errors ()}
  )

;; With queries in normalized form, we're starting to get a glimpse of
;; the information model underlying d2q queries.
;;
;; We see that a d2q Query essentially consists of a list of _Field Calls_,
;; where each Field Call consists of
;; - a Field (:d2q-fcall-field),
;; - an optional key (:d2q-fcall-key),
;; - an optional argument (:d2q-fcall-arg).

;; For certain Fields (called ref-typed Fields), it is possible (and encouraged)
;; to nest another Query under Field Calls.

;; For instance, the following query reads the id and full name of Luke Skywalker,
;; then the first and last name of his father:

(comment "Nesting queries:"
  (let [qctx {:db app-db}
        q [:myapp.person/id
           :myapp.person/full-name
           {:d2q-fcall-field :myapp.person/father
            :d2q-fcall-subquery
            [:myapp.person/first-name
             :myapp.person/last-name]}]                     ;; <-- nesting a Query under a Field Call
        entities
        [(->Person "luke-skywalker")]]
    @(d2q/query app-server qctx q entities))
  =>
  {:d2q-results [{:myapp.person/id "luke-skywalker",
                  :myapp.person/full-name "Luke Skywalker",
                  :myapp.person/father {:myapp.person/first-name "Anakin",
                                        :myapp.person/last-name "Skywalker"}}],
   :d2q-errors ()}
  )

;; As another example, let's get the ids and full names of the parents of Leïa:

(comment "Nesting queries (cont'd)"
  (let [qctx {:db app-db}
        q [:myapp.person/id
           :myapp.person/full-name
           {:d2q-fcall-field :myapp.person/parents
            :d2q-fcall-subquery
            [:myapp.person/id
             :myapp.person/full-name]}]
        entities
        [[(->Person "leia-organa")]]]
    @(d2q/query app-server qctx q entities))
  =>
  {:d2q-results [{:myapp.person/id "leia-organa",
                  :myapp.person/full-name "Leïa Organa",
                  :myapp.person/parents [{:myapp.person/id "padme-amidala",
                                          :myapp.person/full-name "Padme Amidala"}
                                         {:myapp.person/id "anakin-skywalker",
                                          :myapp.person/full-name "Anakin Skywalker"}]}],
   :d2q-errors ()}
  )

;; Some Fields require an _argument_ when they are called.
;; As an example, let's write a query which find persons by their ids:

(comment "calling a Field with an argument:"
  (let [qctx {:db app-db}
        q [{:d2q-fcall-field :myapp.persons/person-of-id
            :d2q-fcall-arg {:myapp.person/id "luke-skywalker"}  ;; (1)
            :d2q-fcall-key "luke" ;; (2)
            :d2q-fcall-subquery [:myapp.person/id
                                 :myapp.person/full-name]}
           {:d2q-fcall-field :myapp.persons/person-of-id
            :d2q-fcall-arg {:myapp.person/id "anakin-skywalker"}
            :d2q-fcall-key "anakin"
            :d2q-fcall-subquery [:myapp.person/id
                                 :myapp.person/full-name]}]
        entities
        [nil]] ;; (3)
    @(d2q/query app-server qctx q entities))
  =>
  {:d2q-results [{"luke" {:myapp.person/id "luke-skywalker",
                          :myapp.person/full-name "Luke Skywalker"},
                  "anakin" {:myapp.person/id "anakin-skywalker",
                            :myapp.person/full-name "Anakin Skywalker"}}],
   :d2q-errors ()}

  ;; Notes:
  ;; - (1) The Field :myapp.persons/person-of-id expects an argument
  ;; of the form {:myapp.person/id PERSON-ID}
  ;; - (2) In this case, the Entity on which we call :myapp.persons/person-of-id plays no role;
  ;; so we can just define it to be nil.
  ;; - (3) In this case, it is essential to supply an explicit :d2q-fcall-key:
  ;; omitting it would expose us to collisions, and make little sense.
  )

;;;; ****************************************************************
;;;; 3. DEFINING AND IMPLEMENTING THE SERVER
;;;; ****************************************************************

;;;; ----------------------------------------------------------------
;;;; The schema

;; Here we declare formally in a _schema_ what Fields can be computed by our Query Server;
;; note that we do not declare _how_ they are computed.

;; From the above query examples, it has become clear that there are several
;; sorts of Fields:
;; - Some Fields are _parameterized_, i.e they use their argument (example: :myapp.persons/person-of-id),
;; whereas others ignore their argument (examples: :myapp.person/first-name, :myapp.person/father)
;; - Some Fields are _scalar-typed_, i.e they navigate to values which eventually end up
;; in the result data structure (examples: :myapp.person/id, :myapp.person/first-name),
;; whereas other Fields are _ref-typed_ (examples: :myapp.person/mother, :myapp.person/parents, :myapp.persons/person-of-id),
;; i.e they navigate to other Entities that are further processed by the Query Engine.
;; - Among ref-typed Fields, some are cardinality-one, i.e they navigate to just
;; one Entity (examples: :myapp.person/mother, :myapp.persons/person-of-id),
;; whereas others are cardinality-many, i.e they navigate to an order list of Entities
;; (example: :myapp.person/parents, :myapp.person/children)

;; These aspects of Fields are exactly what we specify in our `fields` schema below:

(def fields
  [;; NOTE this Field is ref-typed, cardinality-one, and parameterized
   {:d2q.field/name :myapp.persons/person-of-id
    :doc "Resolves a Person given its :myapp.person/id"
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/one}

   ;; NOTE the following 4 Fields are scalar-typed, and not parameterized
   {:d2q.field/name :myapp.person/id
    :doc "A unique identifier of this Person"
    :d2q.field/ref? false}
   {:d2q.field/name :myapp.person/first-name
    :doc "The first name of this Person"
    :d2q.field/ref? false}
   {:d2q.field/name :myapp.person/last-name
    :doc "The last name of this Person"
    :d2q.field/ref? false}
   {:d2q.field/name :myapp.person/full-name
    :doc "The full name of this Person, i.e the concatenation of her first and last names"
    :d2q.field/ref? false}

   ;; NOTE the following 4 Fields are ref-typed and not parameterized
   {:d2q.field/name :myapp.person/mother
    :doc "The biological mother of this Person"
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/one}
   {:d2q.field/name :myapp.person/father
    :doc "The biological father of this Person"
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/one}
   {:d2q.field/name :myapp.person/parents
    :doc "The biological parents of this Person (mother then father when both are known)"
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/many}

   {:d2q.field/name :myapp.person/children
    :doc "The biological children of this Person"
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/many}])


;; Some notes:
;; 1. It makes no sense for a scalar-typed Field to be called with a :d2q-fcall-subquery.
;; 2. For scalar-typed Field, the notion of cardinality makes no sense:
;; a scalar-typed Field may compute any type of values (including maps or vectors),
;; and the d2q engine won't care.

;;;; ----------------------------------------------------------------
;;;; Creating the d2q Server

;; To implement our query Server, we need to declare:
;; - What Fields can be computed (:d2q.server/fields): that's our `fields` schema above
;; - How these Fields are computed. Fields are computed by d2q _Resolvers_:
;; each Resolver is responsible for computing a subset of the Fields.
;; The work of fetching / computing the data is done by the _Resolver Function_ of the Resolver.

;; These are our Resolver Functions (will be implemented below).
(declare
  resolve-persons-by-ids
  resolve-person-fields
  resolve-person-parents
  resolve-person-children)

;; Creating the Server
(def app-server
  (d2q/server
    {:d2q.server/fields fields
     :d2q.server/resolvers
     [{:d2q.resolver/name :myapp.resolvers/person-of-id
       :d2q.resolver/field->meta {:myapp.persons/person-of-id nil}
       :d2q.resolver/compute #'resolve-persons-by-ids}      ;; NOTE: we pass our Resolver Functions as Clojure Vars (using the #' syntax) so that we can redefine the functions interactively at the REPL.
      ;;;; ----------------------------------------------------------------
      ;;;; Anatomy of a d2q Resolver:
      {
       ;; A Resolver has a name, described in :d2q.resolver/name :
       :d2q.resolver/name :myapp.resolvers/person-fields
       ;; A Resolver knows how to compute a set of Fields, described in :d2q.resolver/field->meta
       ;; Note: each Field can have _metadata_ associated with it by the Resolver, which will be passed as a convenience to the Resolver Function.
       ;; In the :d2q.resolver/field->meta map, the keys are Fields, and the values are Field metadata.
       :d2q.resolver/field->meta {:myapp.person/first-name nil, ;; for our basic example, the metadata is always nil
                                  :myapp.person/full-name nil,
                                  :myapp.person/id nil,
                                  :myapp.person/last-name nil}
       ;; A Resolver has a Resolver Function, defined in :d2q.resolver/compute
       :d2q.resolver/compute #'resolve-person-fields}
      ;;;; ----------------------------------------------------------------
      {:d2q.resolver/name :myapp.resolvers/person-parents
       :d2q.resolver/field->meta {:myapp.person/father nil, :myapp.person/mother nil, :myapp.person/parents nil}
       :d2q.resolver/compute #'resolve-person-parents}
      {:d2q.resolver/name :myapp.resolvers/person-children
       :d2q.resolver/field->meta {:myapp.person/children nil}
       :d2q.resolver/compute #'resolve-person-children}]}))

;;;; ----------------------------------------------------------------
;;;; Implementing the Resolver Functions

;; A Resolver Function essentially receives a batch of Field Calls to apply to a batch of Entities,
;; and returns the corresponding values.
;; You can think of this as filling a table, in which the columns are the Field Calls, and the rows
;; are the Entities; the job of the Resolver Function is to return the cells of the (potentially sparse) table.

;; More concretely:
;;
;; A Resolver Function receives as arguments:
;; * a Query Context `qctx` (see the query examples in the previous section)
;; * a sequence of 'Field Call tuples' `i+fcall+metas`: each element in this sequence is a 3-sized tuple, containing:
;;    1. A 'Field Call index': an integer identifying our Field Call
;;    2. The Field Call itself
;;    3. The Field Metadata (associated with the Field by the Resolver)
;; * a sequence of 'Entity Cell tuples' `j+entities`: each element in this sequence is a 2-sized tuple, containing:
;;    1. An 'Entity Cell Index': an integer identifying our Entity
;;    2. The Entity itself
;;
;; The Resolver function returns, wrapped in a Manifold Deferred, a map containing _Result Cells_;
;; each Result Cell provides the value computed for one Field Call on one Entity. A Result cell
;; is a map containing:
;; * The Entity Cell Index (:d2q-entcell-j)
;; * The Field Call Index (:d2q-fcall-i)
;; * The resolved value (:d2q-rescell-value)

;; Here's an example of how a Resolver Function gets called:
(comment "Example of" resolve-person-fields ":"
  (let [;;;; Inputs:
        ;; The Query Context
        qctx {:db app-db}
        ;; The Field Call tuples
        i+fcall+metas
        [[-1                                                ;; the Field Call Index (:d2q-fcall-i)
          {:d2q-fcall-field :myapp.person/id}               ;; the Field Call
          nil                                               ;; the Field metadata
          ]
         [-2 {:d2q-fcall-field :myapp.person/first-name} nil]
         [-3 {:d2q-fcall-field :myapp.person/last-name} nil]
         [-4 {:d2q-fcall-field :myapp.person/full-name} nil]]
        ;; The Entity Cell tuples
        j+entities
        [[1                                                 ;; The Entity Cell Index (:d2q-entcell-j)
          (->Person "luke-skywalker")                       ;; The Entity itself
          ]
         [2 (->Person "padme-amidala")]
         [3 (->Person "non-existing-person")]]]
    @(resolve-person-fields qctx i+fcall+metas j+entities)) ;; Mind the '@': we're returning a Manifold Deferred, which we dereference with @
  => ;;;; Output:
  {:d2q-res-cells [#d2q/result-cell{:d2q-entcell-j 1,       ;; the Entity Cell Index
                                    :d2q-fcall-i -1,        ;; the Field Call Index
                                    :d2q-rescell-value "luke-skywalker" ;; the computed value
                                    }
                   #d2q/result-cell{:d2q-entcell-j 1, :d2q-fcall-i -2, :d2q-rescell-value "Luke"}
                   #d2q/result-cell{:d2q-entcell-j 1, :d2q-fcall-i -3, :d2q-rescell-value "Skywalker"}
                   #d2q/result-cell{:d2q-entcell-j 1, :d2q-fcall-i -4, :d2q-rescell-value "Luke Skywalker"}
                   #d2q/result-cell{:d2q-entcell-j 2, :d2q-fcall-i -1, :d2q-rescell-value "padme-amidala"}
                   #d2q/result-cell{:d2q-entcell-j 2, :d2q-fcall-i -2, :d2q-rescell-value "Padme"}
                   #d2q/result-cell{:d2q-entcell-j 2, :d2q-fcall-i -3, :d2q-rescell-value "Amidala"}
                   #d2q/result-cell{:d2q-entcell-j 2, :d2q-fcall-i -4, :d2q-rescell-value "Padme Amidala"}]}
  )

;; Here's how this Resolver Function may be implemented:
(defn resolve-person-fields
  [{:as qctx, :keys [db]} i+fcall+metas j+entities]
  (mfd/future
    {:d2q-res-cells
     (vec
       (for [[ent-i person-entity] j+entities
             :when (contains? person-entity :person-id)     ;; ignoring Entities which are not Persons
             :let [person-id (:person-id person-entity)
                   person-row (get (:myapp.db/persons-by-id db) person-id)]
             :when (some? person-row)                       ;; ignoring Persons with non-existent ids (we could also return an Exception for this)
             [fcall-i fcall _field-meta] i+fcall+metas
             :let [field-name (:d2q-fcall-field fcall)
                   v (case field-name
                       :myapp.person/full-name
                       (str (:myapp.person/first-name person-row) " " (:myapp.person/last-name person-row))

                       (get person-row field-name))]
             :when (some? v)]
         (d2q/result-cell ent-i fcall-i v)))}))

;; Following are the implementations of the other Resolver Functions:

(defn resolve-persons-by-ids
  [{:as qctx, :keys [db]} i+fcalls j+entities]
  (mfd/future
    {:d2q-res-cells
     (vec
       (for [[fcall-i fcall] i+fcalls
             :let [person-id (-> fcall :d2q-fcall-arg :myapp.person/id)]
             :when (contains? (:myapp.db/persons-by-id db) person-id)
             [ent-i _ent] j+entities
             :let [person-ent (->Person person-id)]]
         (d2q/result-cell ent-i fcall-i person-ent)))}))

(comment "Example of" resolve-persons-by-ids ":"
  (let [qctx {:db app-db}
        i+fcall+metas
        [[-1 {:d2q-fcall-field :myapp.persons/person-of-id
              :d2q-fcall-arg {:myapp.person/id "luke-skywalker"}}
          nil]
         [-2 {:d2q-fcall-field :myapp.persons/person-of-id
              :d2q-fcall-arg {:myapp.person/id "leia-organa"}}
          nil]
         [-3 {:d2q-fcall-field :myapp.persons/person-of-id
              :d2q-fcall-arg {:myapp.person/id "non-existing-id"}}
          nil]]
        j+entities
        [[1 nil]
         [2 nil]]]
    @(resolve-persons-by-ids
       qctx i+fcall+metas j+entities))
  =>
  {:d2q-res-cells [#d2q/result-cell{:d2q-entcell-j 1,
                                    :d2q-fcall-i -1,
                                    :d2q-rescell-value #d2q.test.example.persons.Person{:person-id "luke-skywalker"}}
                   #d2q/result-cell{:d2q-entcell-j 2,
                                    :d2q-fcall-i -1,
                                    :d2q-rescell-value #d2q.test.example.persons.Person{:person-id "luke-skywalker"}}
                   #d2q/result-cell{:d2q-entcell-j 1,
                                    :d2q-fcall-i -2,
                                    :d2q-rescell-value #d2q.test.example.persons.Person{:person-id "leia-organa"}}
                   #d2q/result-cell{:d2q-entcell-j 2,
                                    :d2q-fcall-i -2,
                                    :d2q-rescell-value #d2q.test.example.persons.Person{:person-id "leia-organa"}}]}
  )

(defn resolve-person-parents
  [{:as qctx, :keys [db]} i+fcalls j+entities]
  (mfd/future
    {:d2q-res-cells
     (vec
       (for [[ent-i person-entity] j+entities
             :let [person-id (:person-id person-entity)
                   person-row (get (:myapp.db/persons-by-id db) person-id)]
             :when (some? person-row)
             :let [father-id (:myapp.person/father-id person-row)
                   mother-id (:myapp.person/mother-id person-row)]
             [fcall-i fcall] i+fcalls
             :let [field-name (:d2q-fcall-field fcall)
                   v (case field-name
                       :myapp.person/mother
                       (when (some? mother-id)
                         (->Person mother-id))
                       :myapp.person/father
                       (when (some? father-id)
                         (->Person father-id))
                       :myapp.person/parents
                       (->>
                         [(when (some? mother-id)
                            (->Person mother-id))
                          (when (some? father-id)
                            (->Person mother-id))]
                         (remove nil?)
                         vec))]
             :when (some? v)]
         (d2q/result-cell ent-i fcall-i v)))}))

(comment "Example of" resolve-person-parents ":"
  (let [qctx {:db app-db}
        i+fcall+metas
        [[-1 {:d2q-fcall-field :myapp.person/mother} nil]
         [-2 {:d2q-fcall-field :myapp.person/father} nil]
         [-3 {:d2q-fcall-field :myapp.person/parents} nil]]
        j+entities
        [[1 (->Person "luke-skywalker")]
         [2 (->Person "anakin-skywalker")]]]        ;; NOTE has no parents in our DB
    @(resolve-person-parents qctx i+fcall+metas j+entities))
  =>
  {:d2q-res-cells [#d2q/result-cell{:d2q-entcell-j 1,
                                    :d2q-fcall-i -1,
                                    :d2q-rescell-value #d2q.test.example.persons.Person{:person-id "padme-amidala"}}
                   #d2q/result-cell{:d2q-entcell-j 1,
                                    :d2q-fcall-i -2,
                                    :d2q-rescell-value #d2q.test.example.persons.Person{:person-id "anakin-skywalker"}}
                   #d2q/result-cell{:d2q-entcell-j 1,
                                    :d2q-fcall-i -3,
                                    :d2q-rescell-value [#d2q.test.example.persons.Person{:person-id "padme-amidala"}
                                                        #d2q.test.example.persons.Person{:person-id "padme-amidala"}]}
                   #d2q/result-cell{:d2q-entcell-j 2,
                                    :d2q-fcall-i -3,
                                    :d2q-rescell-value []}]}


  )

(defn resolve-person-children
  [{:as qctx, :keys [db]} i+fcalls j+entities]
  (mfd/future
    {:d2q-res-cells
     (vec
       (for [[ent-i parent-entity] j+entities
             :let [parent-id (:person-id parent-entity)]
             :when (some? parent-id)
             :let [v (->> db
                       :myapp.db/persons-by-id
                       vals
                       (filter (fn [child-row]
                                 (or
                                   (= parent-id (:myapp.person/mother-id child-row))
                                   (= parent-id (:myapp.person/father-id child-row)))))
                       (map (fn [child-row]
                              (->Person (:myapp.person/id child-row))))
                       vec)]
             [fcall-i _fcall] i+fcalls]
         (d2q/result-cell ent-i fcall-i v)))}))

(comment "Example of" resolve-person-children ":"
  (let [qctx
        {:db app-db}
        i+fcall+metas
        [[-1 {:d2q-fcall-field :myapp.person/children} nil]]
        j+entities
        [[1 (->Person "luke-skywalker")]          ;; NOTE has no children in our DB
         [2 (->Person "anakin-skywalker")]]]
    @(resolve-person-children qctx i+fcall+metas j+entities))
  =>
  {:d2q-res-cells [#d2q/result-cell{:d2q-entcell-j 1,
                                    :d2q-fcall-i -1,
                                    :d2q-rescell-value []}
                   #d2q/result-cell{:d2q-entcell-j 2,
                                    :d2q-fcall-i -1,
                                    :d2q-rescell-value [#d2q.test.example.persons.Person{:person-id "luke-skywalker"}
                                                        #d2q.test.example.persons.Person{:person-id "leia-organa"}]}]}
  )


