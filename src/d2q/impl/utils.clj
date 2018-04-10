(ns d2q.impl.utils)

(defn index-by
  [kf coll]
  (persistent!
    (reduce (fn [tm v]
              (assoc! tm
                (kf v)
                v))
      (transient {}) coll)))

(defn index-and-map-by
  [kf vf coll]
  (persistent!
    (reduce (fn [tm v]
              (assoc! tm
                (kf v)
                (vf v)))
      (transient {}) coll)))

(defmacro doarr-indexed!
  "Runs an sequence of expressions `body` across an array `a`,
  binding `idx` to the current index and `e` to the current element.

  You should make sure that the runtime type of array a can be inferred, since
  clojure.core/aget will be called."
  [[[idx e] a] & body]
  `(let [a# ~a
         l# (alength a#)]
     (loop [~idx 0]
       (when (< ~idx l#)
         (let [~e (aget a# ~idx)]
           ~@body)
         (recur (unchecked-inc-int ~idx))))))

(comment
  (doarr-indexed!
    [[i e] (to-array (range 10))]
    (println i e))

  )

(defmacro areduce-iv
  "A more ergonomic version of areduce"
  [[[acc idx e] a] init expr]
  `(let [a# ~a
         l# (alength a#)]
     (loop  [i# 0 acc# ~init]
       (if (< i# l#)
         (let [~idx i#
               ~acc acc#
               ~e (aget a# i#)]
           (recur (unchecked-inc-int i#) ~expr))
         acc#))))

(comment
  (areduce-iv [[acc i v] (to-array (range 10))]
    0 (int (+ acc (- v))))
  => -45
  )


(defmacro amap-indexed
  [[[idx e] a] & body]
  `(let [a# ~a]
     (amap a# i# _#
       (let [~idx i#
             ~e (aget a# i#)]
         ~@body))))

(defn obj-array-with
  "Creates an n-sized array of Objects filled with initial value init-v"
  [n init-v]
  (let [a (object-array n)
        l (alength a)]
    (loop  [idx 0]
      (if (< idx l)
        (do
          (aset a idx init-v)
          (recur (unchecked-inc-int idx)))
        a))))

(comment
  (vec
    (amap-indexed [[i e] (to-array (range 10))]
      [i (- e)]))
  => [[0 0] [1 -1] [2 -2] [3 -3] [4 -4] [5 -5] [6 -6] [7 -7] [8 -8] [9 -9]]
  )


(defn get-safe*
  [m k map-expr]
  (if (contains? m k)
    (get m k)
    (throw (ex-info
             (str "Missing key " (pr-str k) " in " (pr-str map-expr))
             {:map-expr map-expr
              :m m
              :k k}))))

(defmacro get-safe
  [m k]
  `(get-safe* ~m ~k (quote ~m)))

