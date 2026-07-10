(ns ledger.core
  "Public API of the bbledger business logic. Pure: ledger text or data in,
   data out. Shapes and signatures are frozen in CONTRACT.md (Phase 2).
   Rendering and I/O live in the adapter namespaces (bot, store, main, cli)."
  (:require [clojure.string :as str]
            [ledger.parse :as parse]
            [ledger.report :as report]
            [malli.core :as m]
            [malli.error :as me]))

(defn read-str
  "Parse ledger text -> {:rules [...] :transactions [...]}, balances inferred."
  [s]
  (update (parse/parse-string s) :transactions report/infer-balances))

(def Expense
  "Input contract of expense — the write boundary of the core API."
  [:map
   [:date [:re #"^\d{4}-\d{2}-\d{2}$"]]
   [:payer [:fn {:error/message "should be a non-blank string"}
            (every-pred string? (complement str/blank?))]]
   [:category [:vector {:min 1} :string]]
   [:amount [:fn {:error/message "should be a positive decimal"}
             (every-pred decimal? pos?)]]
   ;; ";" starts a comment in the ledger format and newlines end the header
   ;; line — such a description cannot round-trip
   [:description {:optional true} [:maybe [:re #"^[^;\r\n]*$"]]]])

(defn expense
  "Build a balanced two-posting expense txn from
   {:date :payer :category :amount :description}. Throws ex-info on input
   not conforming to the Expense schema."
  [{:keys [date payer category amount description] :as m}]
  (when-let [errors (some-> (m/explain Expense m) me/humanize)]
    (throw (ex-info (str "invalid expense: " errors) m)))
  {:date        date
   :description (or description "")
   :postings    [{:account ["Assets" payer "Cash"] :amount (- amount)
                  :commodity "€" :virtual? false}
                 {:account (into ["Expenses"] category) :amount amount
                  :commodity "€" :virtual? false}]})

(defn txn->str
  "Render a txn as a canonical ledger block (prefix commodity, two-space
   posting indent). Round-trips through read-str."
  [{:keys [date description postings]}]
  (->> postings
       (map (fn [{:keys [account amount virtual?]}]
              (str "  " (cond->> (str/join ":" account) virtual? (format "(%s)"))
                   "  €" (.toPlainString amount))))
       (cons (str/trimr (str date " " description)))
       (str/join "\n")))

(defn balances
  "Sum postings per account -> {account-vec bigdec}.
   opts: {:auto? bool :accounts [substr ...]}."
  [{:keys [rules transactions]} {:keys [auto? accounts]}]
  (let [sums (report/account-sums (cond->> transactions
                                    auto? (report/apply-auto rules)))]
    (if (seq accounts)
      (into {} (filter (fn [[acct _]]
                         (let [a (str/join ":" acct)]
                           (some #(str/includes? a %) accounts)))
                       sums))
      sums)))

(defn settlement
  "Net Verrechnung per person with rules applied -> {\"Alice\" 39.11M ...}.
   Negative = that person owes."
  [{:keys [rules transactions]}]
  (->> (report/apply-auto rules transactions)
       report/account-sums
       (filter #(= "Verrechnung" (first (key %))))
       (reduce (fn [m [acct amt]] (update m (second acct) (fnil + 0M) amt)) {})))

(defn summary
  "Period report. opts {:from :to} (inclusive ISO dates, nil = unbounded) ->
   {:settlement <all-time> :by-category {[segs] amt} :total amt}."
  [{:keys [transactions] :as ledger} {:keys [from to]}]
  (let [in-range? #(and (or (nil? from) (<= (compare from %) 0))
                        (or (nil? to) (<= (compare % to) 0)))
        by-cat    (->> transactions
                       (filter (comp in-range? :date))
                       (mapcat :postings)
                       (filter #(= "Expenses" (first (:account %))))
                       (reduce (fn [m {:keys [account amount]}]
                                 (update m (subvec account 1) (fnil + 0M) amount))
                               {}))]
    {:settlement  (settlement ledger)
     :by-category by-cat
     :total       (reduce + 0M (vals by-cat))}))
