(ns runbld.build
  (:require [clojure.java.io :as io]
            [elasticsearch.document :as doc]
            [environ.core :as environ]
            [runbld.schema :refer :all]
            [runbld.util.data :refer [deep-merge-with deep-merge]]
            [runbld.util.date :as date]
            [runbld.scheduler :as scheduler]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+]]))

(defn make-rand-uuid []
  (.toUpperCase
   (first
    (clojure.string/split
     (str (java.util.UUID/randomUUID))
     (re-pattern "-")))))

(defn make-id []
  (format "%s-%s"
          (date/yyyymmdd-hhmmss)
          (make-rand-uuid)))

(defn split-job-name
  [s]
  (when s
    (let [delim "+"
          [job-name org project branch job-name-extra]
          (re-find
           (re-pattern
            (format
             "^([^%s]+)\\%s([^%s]+)\\%s([^%s]+)\\%s?([^%s]*)?$"
             delim delim delim delim delim delim delim)) s)]
      {:job-name job-name
       :org org
       :project project
       :branch branch
       :job-name-extra job-name-extra
       :org-project-branch (format "%s/%s#%s" org project branch)})))

(defn query-for-commit [keyword-mapping? intake-job]
  (let [clauses (if keyword-mapping?
                  ;; post-5.0 mapping for string fields
                  [{:term {:build.job-name.keyword intake-job}}
                   {:term {:process.status.keyword "SUCCESS"}}]
                  ;; pre-5.0 mapping for string fields
                  [{:term {:build.job-name intake-job}}
                   {:term {:process.status "SUCCESS"}}])]
    {:body
     {:query
      {:bool
       {:filter clauses}},
      :sort {:process.time-end {:order "desc"}},
      :size 1}}))

(defn commit-id [keyword-mapping? es-conn idx intake-job]
  (let [q (query-for-commit keyword-mapping? intake-job)
        doc (doc/search es-conn idx q)]
    (-> doc
        :hits
        :hits
        first
        :_source
        :vcs
        :commit-id)))

(defn last-good-commit
  "For a given project and branch, returns the last commit ID known to have
  passed the intake job."
  [{:keys [job-name] :as opts}]
  (let [es-conn (-> opts :es :conn)
        idx (str (-> opts :es :build-index) "-*")
        {org :org
         project :project
         branch :branch} (split-job-name job-name)
        intake-job (apply str (interpose "+" [org
                                              project
                                              branch
                                              "multijob-intake"]))
        commit (fn [k?]
                 (commit-id k? es-conn idx intake-job))]
    (or (commit false)
        (commit true))))

(s/defn wrap-build-meta :- OptsWithBuild
  [proc :- clojure.lang.IFn]
  (fn [opts]
    (proc
     (assoc opts
            :id (make-id)
            :build (merge (:build opts)
                          (split-job-name (:job-name opts))
                          (scheduler/as-map (:scheduler opts)))))))
