(ns closed-issues-bot.core
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :as tools.cli]
            [closed-issues-bot.config :as config]
            [closed-issues-bot.github :as github]
            [closed-issues-bot.slack :as slack]
            [closed-issues-bot.slack.blocks :as slack.blocks]))

(defn boilerplate []
  (str
   "_The following GitHub issues in `metabase/metabase` were recently closed, but have not been added to a milestone. "
   "Closed issues should be added with a milestone so people who view the issue can see what version the fix will "
   "ship with, and so we know when we shipped a fix in case regressions pop up in the future. "
   "Issue milestones are also used to automatically generate release notes when we ship new releases._\n"
   "\n"
   "_Please add these issues to a milestone, or tag them as duplicates, or add one of the following labels: "
   (str/join ", " (for [label (sort (config/excluded-github-labels))]
                    (format "`%s`" label)))
   "_"))

(defn issue-block [issue]
  [(slack.blocks/markdown-section
    (format "<%s|*Issue #%d* %s>"
            (:url issue)
            (:number issue)
            (:title issue)))
   (slack.blocks/context-section
    (slack.blocks/markdown
     (format "_Closed at %s by *@%s*._"
             (.format (java.time.format.DateTimeFormatter/ofLocalizedDateTime java.time.format.FormatStyle/SHORT)
                      (:closed-at issue))
             (let [{:keys [full-name github-username]} (:closed-by issue)]
               (or (when full-name
                     (slack/full-name->username full-name))
                   github-username)))))])

(defn issues-blocks [issues]
  (let [issues (sort-by :number issues)]
    (list*
     (slack.blocks/header "Recently Closed Issues with no Milestone")
     (slack.blocks/markdown-section (boilerplate))
     slack.blocks/divider
     (mapcat issue-block issues))))

(defn post-issues-message! [channel & options]
  (if-let [issues (not-empty (apply github/issues options))]
    (do
      (println (format "Posting message with %d issues..." (count issues)))
      (slack/post-chat-message!
       channel
       (issues-blocks issues)))
    (println "No matching issues.")))

(defn -main [& args]
  (let [result     (tools.cli/parse-opts args (cons ["-h" "--help"] config/cli-option-specs))
        print-help (fn []
                     (println "\nValid options:\n")
                     (println (:summary result))
                     (println "\nOptions with no default value are required."))]
    (when-let [errors (not-empty (:errors result))]
      (println "Errors parsing command-line arguments:")
      (doseq [error errors]
        (println error))
      (print-help)
      (System/exit 1))
    (when (get-in result [:options :help])
      (print-help)
      (System/exit 0))
    (config/set-options! (:options result)))
  (try
    (post-issues-message! (config/slack-channel) :days (config/recent-days-threshold))
    (println "Done.")
    (System/exit 0)
    (catch Throwable e
      (println "Command failed with error:" (ex-message e))
      (println)
      (binding [pprint/*print-right-margin* 120]
        (pprint/pprint (Throwable->map e)))
      (System/exit 2))))
