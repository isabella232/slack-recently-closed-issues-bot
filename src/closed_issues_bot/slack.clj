(ns closed-issues-bot.slack
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [closed-issues-bot.config :as config]))

(def ^:private ^String api-base-url "https://slack.com/api")

(defn- request-headers []
  {"Accept"        "application/json; charset=utf-8"
   "Content-Type"  "application/json; charset=utf-8"
   ;; See https://api.slack.com/authentication/oauth-v2#using
   "Authorization" (format "Bearer %s" (config/slack-oauth-token))})

(defn- default-request-options []
  {:headers        (request-headers)
   :conn-timeout   10000
   :socket-timeout 10000})

(defn- handle-response [response]
  (let [response-body (-> response
                          :body
                          (json/parse-string true))]
    (when-not (:ok response-body)
      (throw (ex-info (format "Slack API responded with error %s" (pr-str (:error response-body)))
                      {:response response-body})))
    response-body))

(defn- http-request [f endpoint & [request-body]]
  (let [url     (str api-base-url "/" (name endpoint))
        request (merge
                 (default-request-options)
                 (when request-body
                   {:body (json/generate-string request-body)}))]
    (try
      (handle-response  (f url request))
      (catch Throwable e
        (throw (ex-info (ex-message e)
                        {:url url, :request-body request-body}
                        e))))))

(defn- GET [endpoint]
  (http-request http/get endpoint))

(defn- POST [endpoint request-body]
  (http-request http/post endpoint request-body))

;; see https://api.slack.com/methods/chat.postMessage
(defn post-chat-message!
  "Calls Slack API `chat.postMessage` endpoint and posts a message to a channel."
  [channel-id blocks]
  (POST "chat.postMessage"
        {:channel    channel-id
         :username   (config/slack-bot-name)
         :icon_emoji (config/slack-bot-emoji)
         :blocks     blocks}))

(defn- users-list []
  (:members (GET "users.list")))

(def ^:private full-name->username*
  (delay
    (into {} (for [user (users-list)
                   :when (seq (:real_name user))]
               [(:real_name user) (:name user)]))))

(defn- levenshtein-distance ^Integer [^CharSequence s1 ^CharSequence s2]
  (try
    (.apply (org.apache.commons.text.similarity.LevenshteinDistance/getDefaultInstance) s1 s2)
    (catch Throwable e
      (throw (ex-info (str "Error calculating Levenshtein distance: " (ex-message e))
                      {:s1 s1, :s2 s2}
                      e)))))

(defn full-name->username
  "Given someone's IRL full name, find the Slack username with the same full name. Uses Levenshtein distance to find the
  closest match (people's GH and Slack usernames don't always match 1:1).

    (full-name->username \"Cam Saul\") ;-> \"cam\""
  [full-name]
  {:pre [(string? full-name)]}
  (let [[[_ username]] (sort-by first
                                (fn [s1 s2]
                                  (compare
                                   (levenshtein-distance full-name s1)
                                   (levenshtein-distance full-name s2)))
                                @full-name->username*)]
    username))
