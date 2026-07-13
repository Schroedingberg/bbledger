(ns ledger.report
  "Report engine: balance inference, auto-posting expansion, account sums.
   Rendering is not our job — the file is hledger-compatible, so ad-hoc
   reports come from hledger itself; the bot formats its own replies."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; infer-balances : fill the single elided NON-virtual posting so the
;; non-virtual postings of a txn sum to 0 in commodity "€".
;; ---------------------------------------------------------------------------
(defn infer-balances [txns]
  (mapv (fn [{:keys [postings] :as txn}]
          (let [real (remove :virtual? postings)
                sum  (reduce + 0M (keep :amount real))]
            (update txn :postings
                    (fn [ps]
                      (mapv (fn [p]
                              (if (and (not (:virtual? p)) (nil? (:amount p)))
                                (assoc p :amount (- sum) :commodity "€")
                                p))
                            ps)))))
        txns))

;; ---------------------------------------------------------------------------
;; apply-auto : expand automated-posting rules into virtual postings.
;; ---------------------------------------------------------------------------
(defn- query-matches? [query txn posting]
  (let [acct (str/join ":" (:account posting))
        desc (or (:description txn) "")]
    (every? (fn [{:keys [type pattern]}]
              (case type
                :account  (str/includes? acct pattern)
                :desc     (str/includes? desc pattern)
                :not-desc (not (str/includes? desc pattern))))
            query)))

(defn apply-auto [rules txns]
  (mapv (fn [txn]
          (reduce
           (fn [t rule]
             ;; snapshot of the txn's own (non-virtual) postings — virtual
             ;; postings already appended are skipped via the :virtual? guard.
             (let [new-ps (for [p  (:postings t)
                                :when (and (not (:virtual? p))
                                           (query-matches? (:query rule) t p))
                                rp (:postings rule)]
                            {:account   (:account rp)
                             :amount    (* (:multiplier rp) (:amount p))
                             :commodity "€"
                             :virtual?  true})]
               (update t :postings into new-ps)))
           txn rules))
        txns))

;; ---------------------------------------------------------------------------
;; account sums
;; ---------------------------------------------------------------------------
(defn account-sums
  "Sum ALL postings (real + virtual) per account vector -> {account-vec bigdec}."
  [txns]
  (reduce (fn [m {:keys [account amount]}]
            (update m account (fnil + 0M) (or amount 0M)))
          {}
          (mapcat :postings txns)))
