(defproject vvvvalvalval/d2q "0.0.1-SNAPSHOT"
  :description "An expressive toolkit for building efficient GraphQL-like query servers"
  :url "https://github.com/vvvvalvalval/d2q"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [manifold "0.1.6"]]
  :profiles
  {:dev
   {:dependencies
    [[midje "1.7.0"]
     [vvvvalvalval/scope-capture "0.1.4"]
     [vvvvalvalval/scope-capture-nrepl "0.2.0"]
     [criterium "0.4.4"]
     [com.taoensso/tufte "1.4.0"]
     [vvvvalvalval/supdate "0.2.1"]]
    :repl-options {:nrepl-middleware [sc.nrepl.middleware/wrap-letsc]}}})
