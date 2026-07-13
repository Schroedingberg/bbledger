(ns ledger.bot
  "Pure Telegram-bot decision layer: raw update maps in, effect descriptions
   out (schema Effect below). The only side effects happen in run-effects!
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

(def Effect
  "Shape of a non-nil handle-update result. Closed, so a key typo (:replly)
   fails loudly in run-effects! instead of being silently ignored."
  [:map {:closed true}
   [:record {:optional true} :some]  ; txn shape is store/append!'s concern
   [:reply {:optional true} [:or :string [:sequential :string]]]
   [:undo? {:optional true} :boolean]
   [:delete-msg {:optional true} :int]])

(def ^:private help-text
  (str "Send \"12,30 Beschreibung #Kategorie:Sub\" (or \"50€ Pizza\") to record an expense.\n"
       "Recorded messages are deleted — the ✓ reply is the record. Edits are\n"
       "ignored: to correct, /undo and send a new message.\n"
       "/history to see all transactions (CAUTION, can be big!)\n"
       "/bal – settlement  /summary – month-to-date  /undo – revert last"))

(defn- expense-intent?
  "Loose \"was that meant as an expense?\" detector for loud failures:
   a decimal amount anywhere, or text opening with a number."
  [text]
  (or (re-find #"\d+[.,]\d+" text)
      (re-matches #"(?s)\s*€?\s*\d+\s.*" text)))

(def ^:private nudge-text
  (str "⚠ not recorded — start with the amount, with decimals or €: "
       "\"12,30 Essen #Kategorie:Sub\" or \"50€ Pizza\""))

(defn parse-msg
  "\"45.60 Router #Umzug:Kueche\" -> {:amount 45.60M :description \"Router\"
   :category [\"Umzug\" \"Kueche\"]}. Strict trigger: leading amount with
   decimals (dot or comma) or with a € on either side (\"50€ Pizza\",
   \"€50 Pizza\") — a bare integer (\"2 Minuten bin ich da\") never records.
   Unicode spaces (NBSP & friends, which phone keyboards insert and which
   render like \" \") are normalized before matching."
  [s]
  (let [s (str/trim (str/replace s #"\p{Zs}" " "))]
    (when-let [[_ amt rst]
               (or (re-matches #"(?s)(\d+[.,]\d+)\s*€?\s+(.*)" s)     ; 12,30 / 45.60€
                   (re-matches #"(?s)€\s*(\d+(?:[.,]\d+)?)\s+(.*)" s) ; €50 / €12,30
                   (re-matches #"(?s)(\d+)\s*€\s+(.*)" s))]           ; 50€ / 50 €
      {:amount      (bigdec (str/replace amt "," "."))
       :description (str/trim (str/replace rst #"\s*#\S+" ""))
       :category    (some-> (re-find #"#(\S+)" rst) second (str/split #":"))})))

(defn- chunk-lines
  "Split s at line boundaries into chunks under 3500 chars, so each fits one
   Telegram message (sendMessage caps at 4096). Rejoining with \\n restores s
   (minus a trailing newline)."
  [s]
  (reduce (fn [chunks line]
            (let [cur (peek chunks)]
              (if (and cur (< (+ (count cur) 1 (count line)) 3500))
                (conj (pop chunks) (str cur "\n" line))
                (conj chunks line))))
          []
          (str/split-lines s)))

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
   ({:record txn :reply s :delete-msg id} | {:reply s-or-seq} | {:undo? true}
   | nil); see schema Effect. /history pre-chunks its reply into a seq.
   Amount-looking text that doesn't parse gets a ⚠ nudge instead of silence;
   so do edits of amount-looking messages (edits never record)."
  [{:keys [chat-id users default-category tz]} ledger-text update]
  (let [edited? (some? (:edited_message update))
        {{sender :id} :from {chat :id} :chat :keys [text date message_id]}
        (or (:message update) (:edited_message update))
        payer (get users sender)]
    (when (and (= chat-id chat) payer text)
      (if edited?
        (when (expense-intent? text)
          {:reply "⚠ edits are ignored — send the expense as a new message"})
        (let [day (str (.toLocalDate (.atZone (Instant/ofEpochSecond date)
                                              (ZoneId/of tz))))
              cmd (when (str/starts-with? text "/")   ; "/bal@bbledger_bot" -> "/bal"
                    (first (str/split text #"[@\s]")))]
          (case cmd
            "/bal"     {:reply (fmt-settlement (core/settlement (core/read-str ledger-text)))}
            "/history" {:reply (chunk-lines ledger-text)}
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
              (when (expense-intent? text)
                {:reply nudge-text}))))))))

(defn- warn!
  "Last-resort failure logging to stderr. Deliberately never send!s — a send!
   that throws while Telegram is down must not escape into the polling loop."
  [context e]
  (binding [*out* *err*]
    (println "⚠ bbledger:" context "—" (ex-message e))))

(defn run-effects!
  "Execute an effect description via injected side-effecting fns
   {:append! (fn [txn]) :undo! (fn []) :send! (fn [text]) :delete! (fn [msg-id])}.
   Throws on an effect with unknown keys (schema Effect). A :reply seq is sent
   as one message per chunk. :delete-msg is honored only after a successful
   record; a delete failure (e.g. bot lacks admin rights) warns but never fails
   the record. Only an append! failure may claim \"not recorded\" — once
   append! returned, later failures are logged, never sent as ⚠. No injected
   fn's exception escapes to the caller."
  [{:keys [record reply undo? delete-msg] :as effect}
   {:keys [append! undo! send! delete!] :as fns}]
  (assert (every? fns [:append! :undo! :send! :delete!]) "run-effects! injected fn missing")
  (when-let [errors (some->> effect (m/explain Effect) me/humanize)]
    (throw (ex-info (str "invalid effect: " errors) {:effect effect})))
  (letfn [(send-warned! [text context]
            (try (send! text)
                 (catch Exception e (warn! context e))))]
    (cond
      record (if-not (try (append! record) true
                          (catch Exception e
                            (send-warned! (str "⚠ not recorded: " (ex-message e))
                                          "failure reply undeliverable")
                            false))
               nil ; not recorded, ⚠ sent (or logged) — nothing to confirm/delete
               (do (send-warned! reply "recorded, but ✓ confirmation undeliverable")
                   (when delete-msg
                     (try (delete! delete-msg)
                          (catch Exception _
                            (send-warned! (str "⚠ recorded, but couldn't delete your message"
                                               " — make me an admin with 'delete messages'")
                                          "delete warning undeliverable"))))))
      undo?  (try (send! (if-let [desc (undo!)]
                           (str "↩ removed " desc)
                           "nothing to undo"))
                  (catch Exception e (warn! "undo failed" e)))
      reply  (try (run! send! (if (string? reply) [reply] reply))
                  (catch Exception e (warn! "reply failed" e)))))
  nil)
