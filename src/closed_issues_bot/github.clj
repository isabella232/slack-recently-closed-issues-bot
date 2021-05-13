(ns closed-issues-bot.github
  (:require [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [closed-issues-bot.config :as config]
            [java-time :as t]))

(defn- base-url []
  (str "https://api.github.com/repos/" (config/github-repo)))

(defn- request-headers []
  {"Content-Type"  "application/json"
   "Authorization" (format "token %s" (config/github-token))
   "Accept"        "application/vnd.github.v3+json"})

(defmulti ^:private ->query-param
  {:arglists '([x])}
  class)

(defmethod ->query-param Object
  [x]
  x)

(defmethod ->query-param clojure.lang.Keyword
  [k]
  (name k))

(defmethod ->query-param java.time.OffsetDateTime
  [t]
  (str (t/with-offset-same-instant t (t/zone-offset 0))))

(defn- GET
  {:arglists '([endpoint] [endpoint query-parameters])}
  [endpoint & [query-parameters]]
  (let [url     (if (str/starts-with? endpoint "http")
                  endpoint
                  (str (base-url) endpoint))
        headers (request-headers)
        request {:headers        headers
                 :conn-timeout   10000
                 :socket-timeout 10000
                 :query-params   (into {} (for [[k v] query-parameters]
                                            [(->query-param k) (->query-param v)]))}]
    (try
      (-> (http/get url request)
          :body
          (json/parse-string true))
      (catch Throwable e
        (throw (ex-info (ex-message e) {:url url, :request request} e))))))

(defn- paged-GET
  {:arglists '([endpoint] [endpoint options] [endpoint options query-parameters])}
  [endpoint
   & [{:keys [page max-results parse pred page-size]
       :or   {page 1, max-results 100, parse identity, pred identity, page-size 100}}
      query-parameters]]
  (lazy-seq
   (let [query-parameters* (assoc query-parameters
                                  :page     page
                                  :per_page page-size)]
     (when-let [all-items (or (not-empty (GET endpoint query-parameters*))
                              (println "No more items to fetch."))]
       (let [items (take max-results (filter pred (map parse all-items)))]
         (println (format "%s page %d: fetched %d/%d, skipped %d, kept %d"
                          endpoint
                          page
                          (count all-items)
                          max-results
                          (- (count all-items) (count items))
                          (count items)))
         (lazy-cat
          items
          (when (and (or (>= (count all-items) page-size)
                         (println (format "%s: Not fetching next page because current page returned less that %d items"
                                          endpoint
                                          page-size)))
                     (or (< (count items) max-results)
                         (println (format "%s: Not fetching next page because we have reached desired number of results"
                                          endpoint))))
            (paged-GET
             endpoint
             {:page        (inc page)
              :max-results (- max-results (count items))
              :parse       parse
              :pred        pred
              :page-size   page-size}
             query-parameters))))))))

;; https://docs.github.com/en/rest/reference/issues#list-repository-issues
(defn- GET-recent-closed-issues-with-no-milestone*
  [days options query-params]
  {:pre [(integer? days)]}
  (paged-GET
   "/issues"
   options
   (merge
    {:state     :closed
     :sort      :updated
     :milestone :none
     :since     (t/minus (t/offset-date-time) (t/days days))}
    query-params)))

;; https://docs.github.com/en/rest/reference/issues#list-issue-events
(defn- GET-issue-events [issue-number]
  [issue-number]
  {:pre [(integer? issue-number)]}
  (paged-GET (format "/issues/%d/events" issue-number)))

(defn- parse-user [user]
  (:login user))

(defn- parse-event [event]
  {:pre [(map? event)]}
  (let [event-type (->> event
                        :event
                        csk/->kebab-case
                        (keyword "event"))]
    (merge
     {:type event-type}
     (condp = event-type
       :event/closed
       {:closed-by (parse-user (:actor event))}

       nil))))

(defn- parse-issue [issue]
  {:pre [(map? issue)]}
  (merge
   (select-keys issue [:title :number])
   {::parsed?      true
    :url           (:html_url issue)
    :creator       (parse-user (:user issue))
    :assignees     (set (map parse-user (:assignees issue)))
    :closed-at     (some-> (:closed_at issue) t/offset-date-time)
    :labels        (set (map :name (:labels issue)))
    :events        (delay (map parse-event (GET-issue-events (:number issue))))
    :pull-request? (boolean (:pull_request issue))
    :milestone (-> issue :milestone :title)}))

(defn- issue-is-pull-request? [issue]
  (when (:pull-request? issue)
    "Issue is a pull request."))

(defn- issue-older-than-n-days? [n-days {:keys [closed-at], :as issue}]
  {:pre [(integer? n-days) (::parsed? issue)]}
  (let [t-n-days-ago (t/minus (t/offset-date-time) (t/days n-days))]
    (when (t/before? closed-at t-n-days-ago)
      (let [duration (t/duration closed-at t-n-days-ago)]
        (format "Issue was closed more than %d days ago (closed %d days, %d hours ago)"
                n-days
                (.toDays duration)
                (mod (.toHours duration) 24))))))

(defn- issue-has-excluded-label? [{labels-set :labels, :as issue}]
  {:pre [(::parsed? issue) (set labels-set)]}
  (some
   (fn [label]
     (when (contains? labels-set label)
       (format "Issue has excluded label %s." (pr-str label))))
   (config/excluded-github-labels)))

(defn- issue-marked-as-duplicate? [{:keys [events], :as issue}]
  {:pre [(::parsed? issue) (delay? events)]}
  (let [event-types-set (set (map :type @events))]
    (when (contains? event-types-set :event/marked-as-duplicate)
      "Issue is tagged as a duplicate.")))

(defn- skip-issue?
  "Whether to skip `issue`. If we should skip it, returns the reason why."
  [days issue]
  {:pre [(integer? days) (::parsed? issue)]}
  (some
   (fn [pred]
     (when-let [reason (pred issue)]
       (assert (string? reason) "Issue skip predicates should return a string explain why the issue should be skipped.")
       (println (format "Skipping issue %d. Reason: %s" (:number issue) reason))
       true))
   [issue-is-pull-request?
    (partial issue-older-than-n-days? days)
    issue-has-excluded-label?
    issue-marked-as-duplicate?]))

(defn- GET-recent-closed-issues-with-no-milestone [{:keys [days], :or {days 10}, :as options}
                                                   & [query-params]]
  (GET-recent-closed-issues-with-no-milestone*
   days
   (merge
    {:parse parse-issue
     :pred  (complement (partial skip-issue? days))}
    (dissoc options :days))
   query-params))

(def ^:private ^{:arglists '([username])} user-info
  (memoize
   (fn [username]
     (GET (format "https://api.github.com/users/%s" username)))))

(defn- issue-closer
  "The username that closed `issue`."
  [issue]
  {:pre [(::parsed? issue)]}
  (let [username (some
                  (fn [event]
                    (when (= (:type event) :event/closed)
                      (:closed-by event)))
                  @(:events issue))]
    {:github-username username
     :full-name       (:name (user-info username))}))

(defn issues [& {:as options}]
  (vec (for [issue (GET-recent-closed-issues-with-no-milestone options)]
         (assoc issue :closed-by (issue-closer issue)))))
