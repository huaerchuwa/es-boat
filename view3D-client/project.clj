(defproject view3d "0.0.1-SNAPSHOT"
  :description "FIXME: write this!"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [cljs-ajax "0.5.4"]]
  :plugins [[lein-cljsbuild "1.1.3"]]
  :hooks [leiningen.cljsbuild]
  :cljsbuild {
    :builds [{
        :source-paths ["src/cljs"]
        :compiler {
          :output-to "../pro-server/resources/public/js/terrain3d.js"
          :optimizations :simple
          :pretty-print true}}]})
