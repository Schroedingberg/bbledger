(ns ledger.bot
  "Pure Telegram-bot decision layer: raw update maps in, effect descriptions
   out (see CONTRACT.md Phase 2). The only side effects happen in run-effects!
   through injected functions."
  (:require [clojure.string :as str]
            [ledger.core :as core]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.math RoundingMode)
           (java.time Instant ZoneId)))

(def Config
  "Shape of config.edn, checked once at startup (ledger.main/load-config)."
  [:map
   [:chat-id :int]
   [:ledger-file :string]
   [:users [:map-of :int [:fn {:error/message "should be a person name"}
                          (every-pred string? (complement str/blank?))]]]
   [:default-category [:vector {:min 1} :string]]
   [:tz [:fn {:error/message "should be a valid java.time zone id"}
         #(try (some? (ZoneId/of %)) (catch Exception _ false))]]])

(defn config-error
  "Humanized description of what's wrong with cfg, or nil when valid."
  [cfg]
  (some-> (m/explain Config cfg) me/humanize))

(def ^:private help-text
  (str "Send \"12,30 Beschreibung #Kategorie:Sub\" to record an expense.\n"
       "/bal – settlement  /summary – month-to-date  /undo – revert last"))

(defn parse-msg
  "\"45.60 Router #Umzug:Kueche\" -> {:amount 45.60M :description \"Router\"
   :category [\"Umzug\" \"Kueche\"]}. Strict trigger: leading amount with
   decimals (dot or comma). Returns nil when the text doesn't trigger."
  [s]
  (when-let [[_ amt rst] (re-matches #"(?s)(\d+[.,]\d+)\s+(.*)" s)]
    {:amount      (bigdec (str/replace amt "," "."))
     :description (str/trim (str/replace rst #"\s*#\S+" ""))
     :category    (some-> (re-find #"#(\S+)" rst) second (str/split #":"))}))

(defn- fmt-amt [amt]
  (.toPlainString (.setScale ^BigDecimal amt 2 RoundingMode/HALF_UP)))

(defn- fmt-settlement [settlement]
  (str/join "\n" (for [[person amt] (sort settlement)]
                   (str person ": " (fmt-amt amt)))))

(defn- fmt-summary [{:keys [by-category total]} from to]
  (str/join "\n" (concat [(str "Summary " from " – " to)]
                         (for [[cat amt] (sort by-category)]
                           (str (str/join ":" cat) "  €" (fmt-amt amt)))
                         [(str "Total  €" (fmt-amt total))])))

(defn handle-update
  "Telegram update map (wire shape, snake_case keywords) -> effect description
   ({:record txn :reply s} | {:reply s} | {:undo? true} | nil)."
  [{:keys [chat-id users default-category tz]} ledger-text update]
  (let [{{sender :id} :from {chat :id} :chat :keys [text date]} (:message update)
        payer (get users sender)]
    (when (and (= chat-id chat) payer text)
      (let [day (str (.toLocalDate (.atZone (Instant/ofEpochSecond date)
                                            (ZoneId/of tz))))
            cmd (when (str/starts-with? text "/")   ; "/bal@bbledger_bot" -> "/bal"
                  (first (str/split text #"[@\s]")))]
        (case cmd
          "/bal"     {:reply (fmt-settlement (core/settlement (core/read-str ledger-text)))}
          "/summary" (let [from (str (subs day 0 8) "01")]
                       {:reply (fmt-summary (core/summary (core/read-str ledger-text)
                                                          {:from from :to day})
                                            from day)})
          "/undo"    {:undo? true}
          "/help"    {:reply help-text}
          (when-let [{:keys [amount description category]} (parse-msg text)]
            (let [category    (or category default-category)
                  ;; ";" and newlines can't ride in a ledger description
                  description (-> description
                                  (str/replace #"[;\r\n]" " ")
                                  (str/replace #"\s+" " ")
                                  str/trim)]
              (try
                {:record (core/expense {:date day :payer payer :category category
                                        :amount amount :description description})
                 :reply  (str "✓ " description " €" (fmt-amt amount)
                              " (" (str/join ":" category) ", " payer ")")}
                (catch clojure.lang.ExceptionInfo _
                  {:reply (str "⚠ not recorded: " text)})))))))))

(defn run-effects!
  "Execute an effect description via injected side-effecting fns
   {:append! (fn [txn]) :undo! (fn []) :send! (fn [text])}."
  [{:keys [record reply undo?]} {:keys [append! undo! send!]}]
  (cond
    record (try (append! record)
                (send! reply)
                (catch Exception e
                  (send! (str "⚠ not recorded: " (ex-message e)))))
    undo?  (send! (if-let [desc (undo!)]
                    (str "↩ removed " desc)
                    "nothing to undo"))
    reply  (send! reply))
  nil)
