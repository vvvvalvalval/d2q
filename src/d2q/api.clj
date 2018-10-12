(ns d2q.api
  "Demand-driven querying à la GraphQL / Datomic Pull"
  (:require [manifold.deferred :as mfd]
            [d2q.impl]
            [d2q.datatypes]))

;; TODO improvements (Val, 15 Mar 2018)
;; 1. √ Remove specific authorization model - replace by a query-level resolution step
;; 2. √ Subquery preview in resolvers? May require to change the format of resolvers.
;; 3. √ Partial errors => resolvers return a map with {:data :errors} key
;; 4. Maybe skip the normalization of query - should be done by the caller
;; 5. Mutual recursion (requires to change the query format - but how? specified at the Field Call or the Query ?)
;; 6. Source mapping - keep track of (reversed) query-path and data-path
;; 7. √ Maybe faster processing via records ?
;; 8. √ Early subquery substitution
;; 9. Support Conditional Queries ? - dispatch based on an attribute, applying queries that will be applied only to a subset of the entities
;; 10. Field meta
;; 11. Post process entities e.g have a function (ent-cell, result-map) -> result-map
;; 12. 'Flattening' / 'merging' for cardinality-one, ref-typed attributes

;; ------------------------------------------------------------------------------
;; Server-side API

(defn default-transform-entities-fn
  [qctx q ent-sel]
  (mfd/success-deferred
    {:d2q-ent-selection ent-sel
     :d2q-errors []
     :d2q-early-results nil}))

(defn server
  "Constructs a d2q Server, based on an application-specific description of what data Fields are available and how they should be computed.

  The returned Server can then be passed to `#'d2q.api/query` to execute d2q Queries.

  `opts` must be a map with the following keys:

  * :d2q.server/fields (required) describes the schema of the data served by the server,
   in the form of a seq of maps representing d2q Fields.
  * :d2q.server/resolvers (required) describes how data is fetched / computed,
   in the form of a seq of of maps representing d2q Resolvers.
  * :d2q.server/transform-entities-fn (optional) is a function implementing the d2q Transform-Entities execution phase,
  which provides the opportunity to process each Entity Selection before it is passed to Resolvers.


  ## About Fields:

  A d2q Field is a very simple schema element:

  * a Field has a unique name (:d2q.field/name),
  * a Field is either scalar-typed (`:d2q.field/ref? false`) or ref-typed (`:d2q.field/ref? true`),
  * a ref-typed Field can be cardinality-one (`:d2q.field/cardinality :d2q.field.cardinality/one`)
   or cardinality-many (`:d2q.field/cardinality :d2q.field.cardinality/many`)

  Scalar-typed Fields evaluate to the terminal values which will appear in the d2q Query Result, whereas
  ref-typed Fields evaluate to other Entities, which will then be further processed by the query engine.

  Notes:

  1. While most real-world Scalar Fields will evaluate to numbers / strings / booleans / keywords / etc.,
  there are no type constraints on the values taken by a Scalar Field: any value is allowed,
  in particular Clojure collections and `nil`.
  2. Ref-typed Fields evaluate to Entities (or seqs of Entities), which can also be of any type: the appropriate
  type for an Entity is whatever makes sense to the Resolvers which will process it in your application.


  ## About Resolvers:

  d2q Resolvers are essentially named (:d2q.resolver/name) functions (:d2q.resolver/compute),
  dedicated to a set of d2q Fields (:d2q.resolver/field->meta),
  which asynchronously compute a (potentially sparse) table of requested data:
  given a Query Context, a list of M Field Calls, and a batch of N entities (called an Entity Selection),
  a Resolver will compute up to MxN Result Cells, where each Result Cell represents the value taken
  by one Field Call on one Entity in the given Query Context.

  In a pseudo type notation, the signature of a :d2q.resolver/compute function would be:

  (QCtx, [[FieldCallIndex, FieldCall, FieldMeta]], [[EntityIndex, Entity]])
  -> manifold.Deferred<{:d2q-res-cells  [{:d2q-entcell-j     EntityIndex,
                                          :d2q-fcall-i       FieldCallIndex,
                                          :d2q-rescell-value V}],
                        :d2q-errors     [Throwable]}>

  :d2q.resolver/field->meta must be a map, which keys are the names (:d2q.field/name) of the Fields
  that the Resolver knows how to compute, and the values are optional metadata, which as a convenience
  for implementing resolvers are passed to the resolver functions alongside with the Field Calls: here
  you can put any Field-specific configuration to help you resolve it. Each Field must be computed by
  exactly one Resolver.

  Notes:

  1. Resolvers are asynchronous, therefore the returned result should be wrapped in a Manifold Deferred.
  2. The Resolver compute function may throw an Exception, or retured a Deferred that will fail;
   however, the more recommanded approach is to put the errors in the :d2q-errors collection,
   so that a partial result may be returned.
  3. A Resolver should be stateless and context-free: database connections / authentication context / etc.
   should be read either from the Query Context or from the Entities.
  4. A Resolver will never be called with an empty Entity Selection, or an empty list of Field Calls.
  5. To achieve better performance (and concision) than using plain Clojure maps, #'d2q.api/result-cell
   may optionally be called to fabricate the Result Cells.


  ## About the Transform-Entities phase:

  The Transform-Entities phase allows to abort, enrich, or short-circuit the processing of Entities before
  they are passed to Resolvers:

  * Abort: some Entities may be removed from the Entity Selection, and some errors may be added to the result.
  * Enrich: some Entities in the Entity selection may be transformed (e.g with some cached values which will be used
  by several downstream Resolvers)
  * Short-circuit: a final query result may be provided directly for some Entities. This can be useful for caching,
  as well as deferring execution to other query engines than the one provided by d2q.

  In a pseudo type notation, the signature of the :d2q.server/transform-entities-fn function would be:

  (QCtx, Query, [[EntityIndex, Entity]])
  -> manifold.Deferred<{:d2q-ent-selection [[EntityIndex, Entity]],
                        :d2q-errors        [Throwable]                         ;; TODO inconsistent naming (Val, 07 Apr 2018)
                        :d2q-early-results [{:d2q-entcell-j     EntityIndex    ;; TODO maybe also use a tuple here? (Val, 07 Apr 2018)
                                             :d2q-rescell-value V}]}>

  Notes:

  1. The behavior is undefined if a given EntityIndex is returned under both :d2q-ent-selection and :d2q-early-results
  2. By default, the Transform Entities phase is implemented by #'d2q.api/default-transform-entities-fn, which simply
  conveys the Entity Selection it receives without adding any errors or early results."
  [{:as opts
    :keys [d2q.server/fields
           d2q.server/resolvers
           d2q.server/transform-entities-fn]
    :or {d2q.server/transform-entities-fn default-transform-entities-fn}}]
  ;; TODO validation (Val, 07 Apr 2018)
  (d2q.impl/server resolvers fields transform-entities-fn))

;;;; helpers

(defn result-cell
  "A representation of d2q Result Cells more efficient than using a plain Clojure map.
  Should be called from a d2q Resolver's :d2q.resolver/compute function."
  [d2q-entcell-j d2q-fcall-i d2q-rescell-value]
  {:pre [(integer? d2q-entcell-j)
         (integer? d2q-fcall-i)]}
  (d2q.datatypes/->ResultCell d2q-entcell-j d2q-fcall-i d2q-rescell-value))






;; ------------------------------------------------------------------------------
;; Client-side API

(defn query
  "Runs a d2q Query on a batch of Entities.

  Given the arguments:

  * `svr`: a d2q Server, as is returned by #'d2q.api/server
  * `qctx`: a Query Context; may be any value which makes sense to the application,
   will be passed as-is to Resolvers, typically to hold database connections
   and context about the query.
  * `q`: a d2q Query
  * `ents`: a sequence of Entities; an Entity may be any value
   which makes sense to the application.

  Evaluates `q` in the context of `qctx` on Entities `ents`,
  orchestrating execution with the data schema (Fields)
  and data-fetching functions (Resolvers) that were registered to `svr`,
  combining the collected data and errors into a result.

  Returns a Manifold Deferred, which will be realized
  by a map containing results and errors.

  In a pseudo type notation, the signature of `#'d2q.api/query` would be:

  (Server, Qctx, Query, [Entity])
  -> manifold.Deferred<{:d2q-results [QueryResult]
                        :d2q-errors  [Throwable]}>


  ## About Queries:

  A Query is a nested data structure, which essentially describes requested data.

  A Query is made of Field Calls (:d2q-query-fcalls),
  each Field Call referring to a Field (:d2q-fcall-field), a key (:d2q-fcall-key),
  an optional argument (:d2q-fcall-arg),
  and for ref-typed Fields, a sub-Query (:d2q-fcall-subquery).

  Query and Field Calls are Clojure maps, and may be annotated with additional keys,
  which can then be used by Resolvers, as well as for post-processing
  (e.g error reporting / source mapping).

  ### Concise Query form:

  As a convenience, Field Calls can be expressed simply as keywords, i.e
  `:my.app/my-field` is a shorthand for:

  {:d2q-fcall-field :my.app/my-field
   :d2q-fcall-key   :my.app/my-field
   :d2q-fcall-arg   nil}

  Likewise, when no additional annotation is desired, Queries
  can be expressed simply as sequences of Field Calls, i.e:

  [my.app/field1 :my.app/field2 ...]

  is a shorthand for

  {:d2q-query-fcalls
   [my.app/field1 :my.app/field2 ...]}

  Note that Queries and Field Calls are passed to Resolvers
  in their normalized form, not their original form.


  ## About Query Results:

  Each Query Result will be a nested data structure (a map), corresponding positionally
  to one of the passed Entities; in this nested data structure:

  * the keys will be the values taken by `:d2q-fcall-key` in the Query;
  * the values will correspond to the values taken by `:d2q-rescell-value` in Resolvers;
  * the maps will correspond to Entities, which are computed for ref-typed Fields;
  * the vectors will correspond to cardinality-many relationships between Entities,
  which are computed for cardinality-many ref-typed Fields;
  * the terminal values will have been computed for scalar-typed Fields.

  In particular, note that Entities do NOT appear in the final results: they are only used
  as intermediaries to compute scalar values, which get assembled into maps and vectors.
  "
  [svr qctx q ents]
  (d2q.impl/query svr qctx q ents))

