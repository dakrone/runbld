(ns runbld.test.build-test
  (:require [clojure.test :refer :all]
            [runbld.opts :as opts])
  (:require [runbld.build :as build]
            :reload-all))

(deftest build-info
  (let [profiled-opts ((build/wrap-build-meta
                        (build/wrap-merge-profile
                         identity))
                       (opts/parse-args ["-c" "test/runbld.yaml"
                                         "/path/to/script.bash"]))]
    (is (= "test/dev.mustache"
           (-> profiled-opts :opts :email :template)))))