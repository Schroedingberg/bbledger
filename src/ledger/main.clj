(ns ledger.main
  "JVM-only entry point: wires clj-tg-bot-api long-polling to the pure bot
   layer and the store. Never loaded by tests or under babashka."
  (:require [clojure.edn :as edn]
            [ledger.bot :as bot]
            [ledger.store :as store]
            [marksto.clj-tg-bot-api.core :as tg]
            [marksto.clj-tg-bot-api.updates.core :as tg-updates])
  (:gen-class))

(defn- load-config []
  (let [path (or (System/getenv "BBLEDGER_CONFIG") "config.edn")
        cfg  (edn/read-string (slurp path))]
    (if-let [errors (bot/config-error cfg)]
      (throw (ex-info (str "invalid config " path ": " errors) {:errors errors}))
      cfg)))

(defn- ->client []
  (tg/->client {:bot-token (or (System/getenv "BBLEDGER_BOT_TOKEN")
                               (throw (ex-info "BBLEDGER_BOT_TOKEN is not set" {})))}))

(defn- process-update!
  "Feed one raw update (wire shape, snake_case keywords — clj-tg-bot-api
   delivers getUpdates results unnormalized) through the pure bot layer,
   running its effects against the store and Telegram. Returns false so
   the library never repeats an update."
  [cfg client update]
  (bot/run-effects!
   (bot/handle-update cfg (store/read-ledger cfg) update)
   {:append! #(store/append! cfg %)
    :undo!   #(store/undo! cfg)
    :send!   #(tg/make-request! client :send-message
                                {:chat-id (:chat-id cfg) :text %})
    :delete! #(tg/make-request! client :delete-message
                                {:chat-id (:chat-id cfg) :message-id %})})
  false)

(defn- summary-update
  "Synthetic /summary update from a known user so the one-shot summary
   reuses the bot's own month-to-date window and formatting."
  [cfg]
  {:update_id 0
   :message {:message_id 0
             :date       (quot (System/currentTimeMillis) 1000)
             :from       {:id (key (first (:users cfg)))}
             :chat       {:id (:chat-id cfg)}
             :text       "/summary"}})

(defn -main
  "No args: run the bot (config path from env BBLEDGER_CONFIG, default
   \"config.edn\"; token from env BBLEDGER_BOT_TOKEN). Arg \"summary\":
   send a one-shot month-to-date summary to the group and exit. Arg
   \"check\": health probe for deploy gating — validate config + token via
   getMe (doesn't disturb a polling bot), exit 0 ok / 1 broken."
  [& args]
  (let [cfg    (load-config)
        client (->client)]
    (case (first args)
      "summary" (do (process-update! cfg client (summary-update cfg))
                    (System/exit 0))
      "check"   (System/exit (try (tg/make-request! client :get-me) 0
                                  (catch Exception e
                                    (binding [*out* *err*]
                                      (println "check failed:" (ex-message e)))
                                    1)))
      (do ;; single consumer thread => updates are handled strictly sequentially
        (tg-updates/setup-long-polling!
         {:long-polling {:update-handler #(process-update! cfg client %)}}
         client)
        @(promise)))))
