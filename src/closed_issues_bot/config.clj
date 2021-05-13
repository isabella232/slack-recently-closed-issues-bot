(ns closed-issues-bot.config
  (:require [clojure.string :as str]
            [environ.core :as env]))

(defonce ^:private options
  (atom nil))

;; for REPL use.
(defn set-option! [k v]
  (swap! options assoc k v))

(defn set-options! [m]
  (reset! options m))

(defn- option
  ([k parse-fn]
   (option k parse-fn nil))

  ([k parse-fn not-found]
   (get @options k (let [v (get env/env k ::not-found)]
                     (if (= v ::not-found)
                       not-found
                       ((or parse-fn identity) v))))))

(defn- option-or-throw [k parse-fn]
  (let [v (option k parse-fn ::not-found)]
    (when (= v ::not-found)
      (throw (ex-info (format "%s is a required option. Set it with config/set-option!" k)
                      {:option k})))
    v))

(defn- cli-spec [symb docstring options]
  (let [cli-option-name           (str symb)
        cli-arg-str               (str/upper-case (last (str/split cli-option-name #"-")))]
    (into
     [nil (format "--%s %s" cli-option-name cli-arg-str) (str/replace docstring #"\s+" " ")
      :id (keyword symb)]
     (mapcat identity options))))

(defmacro ^:private defoption
  [option-name docstring & {:keys [default], :as options}]
  `(defn ~(vary-meta option-name assoc ::cli-spec (cli-spec option-name docstring options))
     []
     ~(if default
        `(option ~(keyword option-name) ~(:parse-fn options) ~default)
        `(option-or-throw ~(keyword option-name) ~(:parse-fn options)))))

(defoption slack-oauth-token
  "Slack OAuth Token")

(defoption github-token
  "GitHub Access Token")

(defoption slack-channel
  "Slack Channel")

(defoption github-repo
  "GitHub Repository to look for closed issues in."
  :default "metabase/metabase")

(defoption days
  "Max number of days since an issue was closed for it to be included in the message."
  :default    14
  :parse-fn #(Integer/parseUnsignedInt %))

(defoption slack-bot-name
  "Name to use for the Slack Bot when it posts."
  :default "GH Closed Issues With No Milestone Bot")

(defoption slack-bot-emoji
  "Emoji to use for the Slack Bot when it posts."
  :default ":sad:")

(defoption excluded-github-labels
  "GitHub labels that mean an issue should be ignored. Use commas to separate labels in the CLI."
  :default #{".Documentation"
             ".Duplicate"
             ".Unable to Reproduce"
             ".Won't Fix"
             "Type:Question"}
  :parse-fn #(set (str/split % #",")))

(def cli-option-specs
  (vec (for [varr (vals (ns-publics *ns*))
             :let [cli-spec (::cli-spec (meta varr))]
             :when cli-spec]
         cli-spec)))
