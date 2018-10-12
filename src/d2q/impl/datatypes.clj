(ns d2q.impl.datatypes)

(defprotocol IMergeInResultArray
  (merge-in-result-array [this arr]))

(defrecord FinalResultCell
  [d2q-fcall-key
   ^int d2q-entcell-j
   d2q-rescell-value]
  IMergeInResultArray
  (merge-in-result-array [this arr]
    (let [^objects a arr
          i d2q-entcell-j]
      (aset a i
        (assoc! (aget a i)
          d2q-fcall-key d2q-rescell-value)))))
