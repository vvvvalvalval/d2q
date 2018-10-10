(ns d2q.test.example.persons
  (:require [d2q.api :as d2q]
            [manifold.deferred :as mfd]
            [midje.sweet :refer :all]))

;; If you want to experiment with the examples at the REPL,
;; start by loading this entire file in the REPL,
;; then you can evaluate the forms in the (comment ...) blocks.

;;;; ****************************************************************
;;;; DOMAIN
;;;; ****************************************************************

;; Our domain consists of persons, their names, and family bonds.

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
;;;; QUERYING
;;;; ****************************************************************

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

;;;; Normalized query form

;; The query syntax that we used above is actually a shorthand syntax
;; for a more verbosed 'normalized' form.

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
;; - an optional argument (:d2q-fcall-key).

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

;; From the above examples, it has become clear that there are several
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

;; These aspects of Fields are exactly what we specified in our `fields` schema above.

;; Some notes:
;; 1. It makes no sense for a scalar-typed Field to be called with a :d2q-fcall-subquery.
;; 2. For scalar-typed attribute, the notion of cardinality makes no sense:
;; a scalar-typed attribute may compute any type of values (including maps or vectors),
;; and the d2q engine won't care.


;;;; ****************************************************************
;;;; DEFINING AND IMPLEMENTING THE SERVER
;;;; ****************************************************************




(def fields
  [;; NOTE this Field is ref-typed, cardinality-one, and parameterized
   {:d2q.field/name :myapp.persons/person-of-id
    :doc "Resolves a Person given its :myapp.person/id"
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/one
    :d2q.field/resolver :myapp.resolvers/person-of-id}

   ;; NOTE the following 4 Fields are scalar-typed, and not parameterized
   {:d2q.field/name :myapp.person/id
    :doc "A unique identifier of this Person"
    :d2q.field/ref? false
    :d2q.field/resolver :myapp.resolvers/person-fields}
   {:d2q.field/name :myapp.person/first-name
    :doc "The first name of this Person"
    :d2q.field/ref? false
    :d2q.field/resolver :myapp.resolvers/person-fields}
   {:d2q.field/name :myapp.person/last-name
    :doc "The last name of this Person"
    :d2q.field/ref? false
    :d2q.field/resolver :myapp.resolvers/person-fields}
   {:d2q.field/name :myapp.person/full-name
    :doc "The full name of this Person, i.e the concatenation of her first and last names"
    :d2q.field/ref? false
    :d2q.field/resolver :myapp.resolvers/person-fields}

   ;; NOTE the following 4 Fields are ref-typed and not parameterized
   {:d2q.field/name :myapp.person/mother
    :doc "The biological mother of this Person"
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/one
    :d2q.field/resolver :myapp.resolvers/person-parents}
   {:d2q.field/name :myapp.person/father
    :doc "The biological father of this Person"
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/one
    :d2q.field/resolver :myapp.resolvers/person-parents}
   {:d2q.field/name :myapp.person/parents
    :doc "The biological parents of this Person (mother then father when both are known)"
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/many
    :d2q.field/resolver :myapp.resolvers/person-parents}

   {:d2q.field/name :myapp.person/children
    :doc "The biological children of this Person"
    :d2q.field/ref? true
    :d2q.field/cardinality :d2q.field.cardinality/many
    :d2q.field/resolver :myapp.resolvers/person-children}])

(defn resolve-person-fields
  [{:as qctx, :keys [db]} i+fcalls j+entities]
  (mfd/future
    {:d2q-res-cells
     (vec
       (for [[ent-i person-entity] j+entities
             :let [person-id (:person-id person-entity)
                   person-row (get (:myapp.db/persons-by-id db) person-id)]
             :when (some? person-id)
             [fcall-i fcall] i+fcalls
             :let [field-name (:d2q-fcall-field fcall)
                   v (if (= field-name :myapp.person/full-name)
                       (str (:myapp.person/first-name person-row) " " (:myapp.person/last-name person-row))
                       (get person-row field-name))]
             :when (some? v)]
         (d2q/result-cell ent-i fcall-i v)))}))

(comment "Example of" resolve-person-fields ":"
  (let [qctx {:db app-db}
        i+fcalls
        [[-1 {:d2q-fcall-field :myapp.person/id}]
         [-2 {:d2q-fcall-field :myapp.person/first-name}]
         [-3 {:d2q-fcall-field :myapp.person/last-name}]
         [-4 {:d2q-fcall-field :myapp.person/full-name}]]
        j+entities
        [[1 (->Person "luke-skywalker")]
         [2 (->Person "padme-amidala")]
         [3 (->Person "non-existing-person")]]]
    @(resolve-person-fields qctx i+fcalls j+entities))
  =>
  {:d2q-res-cells [#d2q/result-cell{:d2q-entcell-i 1, :d2q-fcall-i -1, :d2q-rescell-value "luke-skywalker"}
                   #d2q/result-cell{:d2q-entcell-i 1, :d2q-fcall-i -2, :d2q-rescell-value "Luke"}
                   #d2q/result-cell{:d2q-entcell-i 1, :d2q-fcall-i -3, :d2q-rescell-value "Skywalker"}
                   #d2q/result-cell{:d2q-entcell-i 1, :d2q-fcall-i -4, :d2q-rescell-value "Luke Skywalker"}
                   #d2q/result-cell{:d2q-entcell-i 2, :d2q-fcall-i -1, :d2q-rescell-value "padme-amidala"}
                   #d2q/result-cell{:d2q-entcell-i 2, :d2q-fcall-i -2, :d2q-rescell-value "Padme"}
                   #d2q/result-cell{:d2q-entcell-i 2, :d2q-fcall-i -3, :d2q-rescell-value "Amidala"}
                   #d2q/result-cell{:d2q-entcell-i 2, :d2q-fcall-i -4, :d2q-rescell-value "Padme Amidala"}
                   #d2q/result-cell{:d2q-entcell-i 3, :d2q-fcall-i -4, :d2q-rescell-value " "}]}
  )


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
        i+fcalls
        [[-1 {:d2q-fcall-field :myapp.persons/person-of-id
              :d2q-fcall-arg {:myapp.person/id "luke-skywalker"}}]
         [-2 {:d2q-fcall-field :myapp.persons/person-of-id
              :d2q-fcall-arg {:myapp.person/id "leia-organa"}}]
         [-3 {:d2q-fcall-field :myapp.persons/person-of-id
              :d2q-fcall-arg {:myapp.person/id "non-existing-id"}}]]
        j+entities
        [[1 nil]
         [2 nil]]]
    @(resolve-persons-by-ids
       qctx i+fcalls j+entities))
  =>
  {:d2q-res-cells [#d2q/result-cell{:d2q-entcell-i 1,
                                    :d2q-fcall-i -1,
                                    :d2q-rescell-value #d2q.test.example.persons.Person{:person-id "luke-skywalker"}}
                   #d2q/result-cell{:d2q-entcell-i 2,
                                    :d2q-fcall-i -1,
                                    :d2q-rescell-value #d2q.test.example.persons.Person{:person-id "luke-skywalker"}}
                   #d2q/result-cell{:d2q-entcell-i 1,
                                    :d2q-fcall-i -2,
                                    :d2q-rescell-value #d2q.test.example.persons.Person{:person-id "leia-organa"}}
                   #d2q/result-cell{:d2q-entcell-i 2,
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
             :when (some? person-id)
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
        i+fcalls
        [[-1 {:d2q-fcall-field :myapp.person/mother}]
         [-2 {:d2q-fcall-field :myapp.person/father}]
         [-3 {:d2q-fcall-field :myapp.person/parents}]]
        j+entities
        [[1 (->Person "luke-skywalker")]
         [2 (->Person "anakin-skywalker")]]]        ;; NOTE has no parents in our DB
    @(resolve-person-parents qctx i+fcalls j+entities))
  =>
  {:d2q-res-cells [#d2q/result-cell{:d2q-entcell-i 1,
                                    :d2q-fcall-i -1,
                                    :d2q-rescell-value #d2q.test.example.persons.Person{:person-id "padme-amidala"}}
                   #d2q/result-cell{:d2q-entcell-i 1,
                                    :d2q-fcall-i -2,
                                    :d2q-rescell-value #d2q.test.example.persons.Person{:person-id "anakin-skywalker"}}
                   #d2q/result-cell{:d2q-entcell-i 1,
                                    :d2q-fcall-i -3,
                                    :d2q-rescell-value [#d2q.test.example.persons.Person{:person-id "padme-amidala"}
                                                        #d2q.test.example.persons.Person{:person-id "padme-amidala"}]}
                   #d2q/result-cell{:d2q-entcell-i 2,
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
        i+fcalls
        [[-1 {:d2q-fcall-field :myapp.person/children}]]
        j+entities
        [[1 (->Person "luke-skywalker")]          ;; NOTE has no children in our DB
         [2 (->Person "anakin-skywalker")]]]
    @(resolve-person-children qctx i+fcalls j+entities))
  =>
  {:d2q-res-cells [#d2q/result-cell{:d2q-entcell-i 1,
                                    :d2q-fcall-i -1,
                                    :d2q-rescell-value []}
                   #d2q/result-cell{:d2q-entcell-i 2,
                                    :d2q-fcall-i -1,
                                    :d2q-rescell-value [#d2q.test.example.persons.Person{:person-id "luke-skywalker"}
                                                        #d2q.test.example.persons.Person{:person-id "leia-organa"}]}]}
  )

;;;; putting it all together

(def app-server
  (d2q/server
    {:d2q.server/fields fields
     :d2q.server/resolvers
     [{:d2q.resolver/name :myapp.resolvers/person-of-id
       :d2q.resolver/compute #'resolve-persons-by-ids}
      {:d2q.resolver/name :myapp.resolvers/person-fields
       :d2q.resolver/compute #'resolve-person-fields}
      {:d2q.resolver/name :myapp.resolvers/person-parents
       :d2q.resolver/compute #'resolve-person-parents}
      {:d2q.resolver/name :myapp.resolvers/person-children
       :d2q.resolver/compute #'resolve-person-children}]}))

