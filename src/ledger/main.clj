(ns ledger.main
  "JVM-only entry point: wires clj-tg-bot-api to the pure bot layer and the
   store. Runs the bot either by long-polling (default) or, when a public URL
   is available (e.g. behind a PaaS like orkestr), by webhook — an http-kit
   server that receives Telegram's POSTs. Never loaded by tests or under
   babashka."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ledger.bot :as bot]
            [ledger.store :as store]
            [marksto.clj-tg-bot-api.core :as tg]
            [marksto.clj-tg-bot-api.updates.core :as tg-updates]
            [org.httpkit.server :as http])
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
   delivers getUpdates results unnormalized, and Telegram's webhook POSTs the
   same JSON) through the pure bot layer, running its effects against the store
   and Telegram. Returns false so the long-polling library never repeats an
   update."
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

(defn- webhook-handler
  "Build an http-kit handler. GET (and anything non-POST) is a health probe;
   POSTs carrying Telegram's secret-token header are parsed and processed under
   `lock` so updates stay strictly sequential (single writer) even though
   http-kit dispatches requests concurrently. Always answers 200 to a genuine
   Telegram POST — like the long-polling path's `false` return, this tells
   Telegram not to redeliver (a redelivery could double-book an expense)."
  [cfg client secret lock]
  (fn [{:keys [request-method headers body]}]
    (cond
      (not= :post request-method)
      {:status 200 :body "bbledger"}

      (not= secret (get headers "x-telegram-bot-api-secret-token"))
      {:status 403 :body "forbidden"}

      :else
      (do (try
            (let [update (json/parse-stream (io/reader body) true)]
              (locking lock
                (process-update! cfg client update)))
            (catch Exception e
              (.printStackTrace e)))
          {:status 200 :body "ok"}))))

(defn- run-webhook!
  "Register the webhook URL with Telegram, then serve it with http-kit until
   killed. The secret guards the endpoint: Telegram echoes it in every POST's
   `X-Telegram-Bot-Api-Secret-Token` header (BBLEDGER_WEBHOOK_SECRET, or a
   fresh random one each boot). `drop-pending-updates` avoids replaying a
   backlog — including anything queued while long-polling — on (re)deploy."
  [cfg client url]
  (let [secret (or (System/getenv "BBLEDGER_WEBHOOK_SECRET") (str (random-uuid)))
        port   (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (tg/make-request! client :set-webhook
                      {:url                  url
                       :secret-token         secret
                       :allowed-updates      ["message" "edited_message"]
                       :drop-pending-updates true})
    (http/run-server (webhook-handler cfg client secret (Object.)) {:port port})
    (println (str "bbledger webhook listening on :" port " for " url))
    @(promise)))

(defn -main
  "No args: run the bot. When BBLEDGER_WEBHOOK_URL is set, serve that webhook
   (http-kit on $PORT, default 8080); otherwise long-poll. Config path from env
   BBLEDGER_CONFIG (default \"config.edn\"); token from env BBLEDGER_BOT_TOKEN.
   Arg \"summary\": send a one-shot month-to-date summary to the group and exit."
  [& args]
  (let [cfg    (load-config)
        client (->client)]
    (cond
      (= "summary" (first args))
      (do (process-update! cfg client (summary-update cfg))
          (System/exit 0))

      (System/getenv "BBLEDGER_WEBHOOK_URL")
      (run-webhook! cfg client (System/getenv "BBLEDGER_WEBHOOK_URL"))

      :else
      (do ;; single consumer thread => updates are handled strictly sequentially
        (tg-updates/setup-long-polling!
         {:long-polling {:update-handler #(process-update! cfg client %)}}
         client)
        @(promise)))))
