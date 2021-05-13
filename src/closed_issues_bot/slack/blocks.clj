(ns closed-issues-bot.slack.blocks)

(defn plain-text [message]
  {:type  :plain_text
   :text  message
   :emoji true})

(defn markdown [message]
  {:type :mrkdwn
   :text message})

(defn header [message]
  {:type :header
   :text (plain-text message)})

(def divider
  {:type :divider})

;; (defn plain-text-section [message]
;;   {:type :section
;;    :text (plain-text message)})

(defn markdown-section [message]
  {:type :section
   :text (markdown message)})

;; (defn url-button [message url]
;;   {:type :button
;;    :text (plain-text message)
;;    :url  url})

;; (defn url-buttons [message->url]
;;   {:type     :actions
;;    :elements (for [[message url] message->url]
;;                (url-button message url))})

(defn context-section [& items]
  {:type     :context
   :elements items})
