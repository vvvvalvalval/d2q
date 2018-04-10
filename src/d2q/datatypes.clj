(ns d2q.datatypes)

;; ------------------------------------------------------------------------------
;; Query datatypes

(defrecord Query
  [d2q-query-id
   d2q-query-fcalls
   d2q-rev-query-path])

(defrecord FieldCall
  [d2q-fcall-field
   ;; NOTE maybe we need another index than d2q-fcall-key, e.g d2q-fcall-j which would be an integer
   d2q-fcall-key
   d2q-fcall-arg
   d2q-fcall-subquery
   ;; NOTE this is a source path (Val, 05 Apr 2018) TODO check it's done correctly (Val, 05 Apr 2018)
   d2q-fcall-rev-query-path])

;; ------------------------------------------------------------------------------
;; Resolvers datatypes

(defrecord ResultCell
  [^int d2q-entcell-i
   ^int d2q-fcall-i
   d2q-rescell-value])

(defrecord ResolverResult
  [d2q-res-cells
   d2q-errors])
