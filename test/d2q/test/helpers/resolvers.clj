(ns d2q.test.helpers.resolvers
  (:require [clojure.test :refer :all]
            [midje.sweet :refer :all]
            [d2q.helpers.resolvers :as d2q-res]
            [d2q.test.test-utils :as tu]
            [d2q.api :as d2q]
            [vvvvalvalval.supdate.api :as supd]))

(fact "into-resolver-result"
  (fact "with transducer"
    (->
      (d2q-res/into-resolver-result
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
      tu/errors->ex-data)

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
      (d2q-res/into-resolver-result
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
      tu/errors->ex-data)
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

(fact "entities-independent-resolver"
  (->
    @((d2q-res/entities-independent-resolver
        (fn square-root [_qctx i+fcalls]
          (d2q-res/into-resolver-result
            (map (fn [[i {:as fcall, x :d2q-fcall-arg}]]
                   (try
                     (assert (number? x))
                     (assert (not (neg? x)))
                     (d2q/result-cell -1 i
                       (Math/sqrt (double x)))
                     (catch Throwable err
                       (ex-info
                         (str "Failed to take square root of " (pr-str x))
                         {:d2q-fcall-i i}
                         err)))))
            i+fcalls)))
       :qctx
       [[0 {:d2q-fcall-arg 0.0}]
        [1 {:d2q-fcall-arg 4.0}]
        [2 {:d2q-fcall-arg -1.0}]
        [3 {:d2q-fcall-arg nil}]]
       [[10 :entity-1]
        [20 :entity-2]])
    tu/errors->ex-data
    (supd/supdate {:d2q-res-cells set}))
  => '{:d2q-res-cells #{#d2q/result-cell{:d2q-entcell-i 20, :d2q-fcall-i 1, :d2q-rescell-value 2.0}
                        #d2q/result-cell{:d2q-entcell-i 20, :d2q-fcall-i 0, :d2q-rescell-value 0.0}
                        #d2q/result-cell{:d2q-entcell-i 10, :d2q-fcall-i 0, :d2q-rescell-value 0.0}
                        #d2q/result-cell{:d2q-entcell-i 10, :d2q-fcall-i 1, :d2q-rescell-value 2.0}},
       :d2q-errors [{:error/type clojure.lang.ExceptionInfo,
                     :error/message "Failed to take square root of -1.0",
                     :error/data {:d2q-fcall-i 2},
                     :error/cause {:error/type java.lang.AssertionError,
                                   :error/message "Assert failed: (not (neg? x))",
                                   :error/data nil}}
                    {:error/type clojure.lang.ExceptionInfo,
                     :error/message "Failed to take square root of nil",
                     :error/data {:d2q-fcall-i 3},
                     :error/cause {:error/type java.lang.AssertionError,
                                   :error/message "Assert failed: (number? x)",
                                   :error/data nil}}]}
  )
