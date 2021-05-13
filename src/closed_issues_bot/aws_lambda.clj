(ns closed-issues-bot.aws-lambda
  (:gen-class :implements [com.amazonaws.services.lambda.runtime.RequestHandler])
  (:require [closed-issues-bot.core :as core]))

(defn -handleRequest [_ _ _]
  (core/post-issues-message!)
  "200 OK")
