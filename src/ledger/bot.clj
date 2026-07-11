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
       "Recorded messages are deleted — the ✓ reply is the record. Edits are\n"
       "ignored: to correct, /undo and send a new message.\n"
       "/history to see all transactions (CAUTION, can be big!)\n"
       "/bal – settlement  /summary – month-to-date  /undo – revert last"))

(def ^:private amount-like
  "Loose \"was that meant as an expense?\" detector for loud failures."
  #"\d+[.,]\d+")

(defn parse-msg
  "\"45.60 Router #Umzug:Kueche\" -> {:amount 45.60M :description \"Router\"
   :category [\"Umzug\" \"Kueche\"]}. Strict trigger: leading amount with
   decimals (dot or comma). Returns nil when the text doesn't trigger.
   Unicode spaces (NBSP & friends, which phone keyboards insert and which
   render like \" \") are normalized before matching."
  [s]
  (let [s (str/trim (str/replace s #"\p{Zs}" " "))]
    (when-let [[_ amt rst] (re-matches #"(?s)(\d+[.,]\d+)\s+(.*)" s)]
      {:amount      (bigdec (str/replace amt "," "."))
       :description (str/trim (str/replace rst #"\s*#\S+" ""))
       :category    (some-> (re-find #"#(\S+)" rst) second (str/split #":"))})))

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
   ({:record txn :reply s :delete-msg id} | {:reply s} | {:undo? true} | nil).
   Amount-looking text that doesn't parse gets a ⚠ nudge instead of silence;
   so do edits of amount-looking messages (edits never record)."
  [{:keys [chat-id users default-category tz]} ledger-text update]
  (let [edited? (some? (:edited_message update))
        {{sender :id} :from {chat :id} :chat :keys [text date message_id]}
        (or (:message update) (:edited_message update))
        payer (get users sender)]
    (when (and (= chat-id chat) payer text)
      (if edited?
        (when (re-find amount-like text)
          {:reply "⚠ edits are ignored — send the expense as a new message"})
        (let [day (str (.toLocalDate (.atZone (Instant/ofEpochSecond date)
                                              (ZoneId/of tz))))
              cmd (when (str/starts-with? text "/")   ; "/bal@bbledger_bot" -> "/bal"
                    (first (str/split text #"[@\s]")))]
          (case cmd
            "/bal"     {:reply (fmt-settlement (core/settlement (core/read-str ledger-text)))}
            "/history" {:reply ledger-text}
            "/summary" (let [from (str (subs day 0 8) "01")]
                         {:reply (fmt-summary (core/summary (core/read-str ledger-text)
                                                            {:from from :to day})
                                              from day)})
            "/undo"    {:undo? true}
            "/help"    {:reply help-text}
            (if-let [{:keys [amount description category]} (parse-msg text)]
              (let [category    (or category default-category)
                    ;; ";" and newlines can't ride in a ledger description
                    description (-> description
                                    (str/replace #"[;\r\n]" " ")
                                    (str/replace #"\s+" " ")
                                    str/trim)]
                (try
                  {:record     (core/expense {:date day :payer payer :category category
                                              :amount amount :description description})
                   :reply      (str "✓ " description " €" (fmt-amt amount)
                                    " (" (str/join ":" category) ", " payer ")")
                   :delete-msg message_id}
                  (catch clojure.lang.ExceptionInfo _
                    {:reply (str "⚠ not recorded: " text)})))
              (when (re-find amount-like text)
                {:reply (str "⚠ not recorded — expenses start with the amount: "
                             "\"12,30 Beschreibung #Kategorie:Sub\"")}))))))))

(defn run-effects!
  "Execute an effect description via injected side-effecting fns
   {:append! (fn [txn]) :undo! (fn []) :send! (fn [text]) :delete! (fn [msg-id])}.
   :delete-msg is honored only after a successful record; a delete failure
   (e.g. bot lacks admin rights) warns but never fails the record."
  [{:keys [record reply undo? delete-msg]} {:keys [append! undo! send! delete!]}]
  (cond
    record (try (append! record)
                (send! reply)
                (when delete-msg
                  (try (delete! delete-msg)
                       (catch Exception _
                         (send! (str "⚠ recorded, but couldn't delete your message"
                                     " — make me an admin with 'delete messages'")))))
                (catch Exception e
                  (send! (str "⚠ not recorded: " (ex-message e)))))
    undo?  (send! (if-let [desc (undo!)]
                    (str "↩ removed " desc)
                    "nothing to undo"))
    reply  (send! reply))
  nil)
