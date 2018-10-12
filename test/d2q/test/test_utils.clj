(ns d2q.test.test-utils
  (:require [clojure.test :refer :all]))

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

