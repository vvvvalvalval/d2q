(ns d2q.impl.tabular-protocols)

(defprotocol IField
  (field-name [this])
  (field-scalar? [this])
  (field-many? [this])
  (field-table-resolver [this]))

(defprotocol ITabularResolver
  (tr-name [this])
  (resolve-table [this qctx f+args o+is]))

(defprotocol IServer
  (transform-entities [this qctx query o+is])
  (field-by-name [this field-name]))

