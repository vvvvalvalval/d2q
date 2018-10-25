(defproject vvvvalvalval/d2q "0.1.0"
  :description "An expressive, efficient, generally applicable engine for implementing graph-pulling API servers in Clojure."
  :url "https://github.com/vvvvalvalval/d2q"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :profiles
  {:dev
   {:lein-tools-deps/config {:aliases [:dev]}
    :repl-options {:nrepl-middleware [sc.nrepl.middleware/wrap-letsc]}}}

  :plugins [[lein-tools-deps "0.4.1"]]
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :lein-tools-deps/config {:config-files [:install :user :project]}
  )
