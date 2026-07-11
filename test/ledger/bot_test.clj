(ns ledger.bot-test
  "Failing-first spec for the pure bot layer (CONTRACT.md Phase 2)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ledger.bot :as bot]))

(def cfg
  {:chat-id          -100
   :ledger-file      "/tmp/unused.ledger"
   :users            {111 "Alice", 222 "Bob"}
   :default-category ["Sonstiges"]
   :tz               "Europe/Berlin"})

(defn upd
  "Raw Telegram update. 1783602000 = 2026-07-09T13:00Z = 15:00 Europe/Berlin."
  [from-id chat-id text]
  {:update_id 1
   :message {:message_id 10
             :date       1783602000
             :from       {:id from-id}
             :chat       {:id chat-id}
             :text       text}})

(def rules
  "The real income-ratio rules from sample.ledger."
  (str "= Expenses\n"
       "    (Verrechnung:Alice)    *-0.6\n"
       "    (Verrechnung:Bob)   *-0.4\n"
       "\n"
       "= Assets:Alice:Cash not:desc:Kaution\n"
       "    (Verrechnung:Alice)    *-1\n"
       "= Assets:Bob:Cash not:desc:Kaution\n"
       "    (Verrechnung:Bob)   *-1\n"))

(def ledger-fixture
  "One 100€ expense paid by Alice => settlement Alice +40.00 / Bob -40.00."
  (str rules
       "\n"
       "2026-07-01 Testkauf\n"
       "  Assets:Alice:Cash\n"
       "  Expenses:Umzug:Test  100€\n"))

;; --------------------------------------------------------------------------
;; Config schema
;; --------------------------------------------------------------------------
(deftest config-schema-catches-misconfiguration
  (is (nil? (bot/config-error cfg)))
  (doseq [broken [(assoc cfg :chat-id "-100")            ; string, not int
                  (update cfg :users assoc "333" "Eve")  ; string user-id
                  (assoc cfg :tz "Europe/Berlim")        ; zone typo
                  (assoc cfg :default-category [])
                  (dissoc cfg :ledger-file)]]
    (is (some? (bot/config-error broken)) (pr-str broken))))

;; --------------------------------------------------------------------------
;; parse-msg
;; --------------------------------------------------------------------------
(deftest parse-msg-accepts-strict-amount-triggers
  (testing "dot decimals"
    (let [{:keys [amount description category]} (bot/parse-msg "45.60 Router")]
      (is (== 45.60M amount))
      (is (= "Router" description))
      (is (nil? category))))
  (testing "comma decimals"
    (is (== 12.30M (:amount (bot/parse-msg "12,30 Drogerie")))))
  (testing "#category token is extracted and removed from the description"
    (let [{:keys [description category]}
          (bot/parse-msg "45.60 Router #Umzug:Infrastruktur")]
      (is (= ["Umzug" "Infrastruktur"] category))
      (is (= "Router" description)))))

(deftest parse-msg-rejects-non-triggers
  (doseq [text ["2 Minuten bin ich da"   ; amount without decimals
                "hallo"
                "/bal"
                "ca. 45.60 Router"]]     ; amount not leading
    (is (nil? (bot/parse-msg text)) (pr-str text))))

(deftest parse-msg-tolerates-unicode-and-leading-whitespace
  ;; phone keyboards insert non-breaking spaces that render exactly like " "
  (doseq [text ["35.72\u00A0Rewe #Lebensmittel:Rewe"   ; NBSP after amount
                "35.72\u202FRewe #Lebensmittel:Rewe"   ; narrow NBSP
                " 35.72 Rewe #Lebensmittel:Rewe"]]     ; leading space
    (let [{:keys [amount description category]} (bot/parse-msg text)]
      (is (== 35.72M amount) (pr-str text))
      (is (= "Rewe" description) (pr-str text))
      (is (= ["Lebensmittel" "Rewe"] category) (pr-str text)))))

;; --------------------------------------------------------------------------
;; handle-update
;; --------------------------------------------------------------------------
(deftest expense-message-records-with-payer-from-sender
  (let [{:keys [record reply]}
        (bot/handle-update cfg ledger-fixture (upd 222 -100 "12,30 Drogerie"))]
    (is (map? record))
    (is (= "2026-07-09" (:date record)) "date from message ts in config tz")
    (is (= "Drogerie" (:description record)))
    (is (= [["Assets" "Bob" "Cash"] ["Expenses" "Sonstiges"]]
           (mapv :account (:postings record)))
        "payer mapped from sender-id, default category applied")
    (is (== -12.30M (:amount (first (:postings record)))))
    (is (str/includes? reply "Drogerie"))))

(deftest expense-message-honors-category-token
  (let [{:keys [record]}
        (bot/handle-update cfg ledger-fixture
                           (upd 111 -100 "45.60 Router #Umzug:Infrastruktur"))]
    (is (= ["Expenses" "Umzug" "Infrastruktur"]
           (:account (second (:postings record)))))))

(deftest sanitizes-descriptions-the-ledger-format-cannot-carry
  (testing "\";\" (ledger comment start) is squeezed out, no silent truncation"
    (let [{:keys [record]}
          (bot/handle-update cfg ledger-fixture
                             (upd 222 -100 "12,30 Drogerie; Shampoo für Bob"))]
      (is (= "Drogerie Shampoo für Bob" (:description record)))))
  (testing "multiline Telegram messages become single-line descriptions"
    (let [{:keys [record]}
          (bot/handle-update cfg ledger-fixture (upd 111 -100 "45.60 Router\nWLAN neu"))]
      (is (= "Router WLAN neu" (:description record))))))

(deftest invalid-expense-warns-instead-of-recording-or-throwing
  (let [{:keys [record reply]}
        (bot/handle-update cfg ledger-fixture (upd 111 -100 "0,00 Kaffee"))]
    (is (nil? record))
    (is (str/starts-with? reply "⚠"))))

(deftest ignores-wrong-chat-unknown-sender-and-chatter
  (is (nil? (bot/handle-update cfg ledger-fixture (upd 111 -999 "45.60 Router")))
      "wrong chat")
  (is (nil? (bot/handle-update cfg ledger-fixture (upd 333 -100 "45.60 Router")))
      "unknown sender")
  (is (nil? (bot/handle-update cfg ledger-fixture (upd 111 -100 "2 Minuten bin ich da")))
      "no trigger"))

(deftest bal-command-replies-with-settlement
  (doseq [text ["/bal" "/bal@bbledger_bot"]]   ; group chats append @botname
    (let [{:keys [record reply]}
          (bot/handle-update cfg ledger-fixture (upd 111 -100 text))]
      (is (nil? record))
      (is (str/includes? reply "Alice"))
      (is (str/includes? reply "Bob"))
      (is (str/includes? reply "40.00")))))

(deftest summary-command-replies
  (let [{:keys [reply]} (bot/handle-update cfg ledger-fixture (upd 111 -100 "/summary"))]
    (is (string? reply))
    (is (str/includes? reply "100") "month-to-date total includes the 100€ txn")))

(deftest undo-and-help-commands
  (is (:undo? (bot/handle-update cfg ledger-fixture (upd 111 -100 "/undo"))))
  (is (string? (:reply (bot/handle-update cfg ledger-fixture (upd 111 -100 "/help"))))))

;; --------------------------------------------------------------------------
;; run-effects!
;; --------------------------------------------------------------------------
(defn- recorder []
  (let [calls (atom [])]
    [calls {:append! #(swap! calls conj [:append %])
            :undo!   (fn [] (swap! calls conj [:undo]) "Drogerie")
            :send!   #(swap! calls conj [:send %])}]))

(deftest run-effects-record-then-reply
  (let [[calls fns] (recorder)]
    (bot/run-effects! {:record ::txn :reply "ok"} fns)
    (is (= [[:append ::txn] [:send "ok"]] @calls))))

(deftest run-effects-append-failure-sends-warning-instead
  (let [sent (atom [])]
    (bot/run-effects! {:record ::txn :reply "ok"}
                      {:append! (fn [_] (throw (ex-info "invalid" {})))
                       :undo!   (fn [])
                       :send!   #(swap! sent conj %)})
    (is (= 1 (count @sent)))
    (is (not= "ok" (first @sent)) "the success reply must not be sent")))

(deftest run-effects-undo-reports-what-was-removed
  (let [[calls fns] (recorder)]
    (bot/run-effects! {:undo? true} fns)
    (is (= [:undo] (first @calls)))
    (is (str/includes? (second (second @calls)) "Drogerie"))))

(deftest run-effects-nil-is-a-no-op
  (is (nil? (bot/run-effects! nil {:send! (fn [_] (throw (ex-info "must not send" {})))
                                   :append! (fn [_]) :undo! (fn [])}))))
