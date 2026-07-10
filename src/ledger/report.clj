(ns ledger.report
  "Report engine: balance inference, auto-posting expansion, and rendering."
  (:require [clojure.string :as str]))

(def ^:private RM java.math.RoundingMode/HALF_UP)

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
;; rendering helpers
;; ---------------------------------------------------------------------------
(defn account-sums
  "Sum ALL postings (real + virtual) per account vector -> {account-vec bigdec}."
  [txns]
  (reduce (fn [m {:keys [account amount]}]
            (update m account (fnil + 0M) (or amount 0M)))
          {}
          (mapcat :postings txns)))

(defn- fmt [amt]
  (str (.setScale (bigdec amt) 2 RM) " €"))

(defn- rollups
  "map of every account-prefix -> rolled-up total (own + descendants)."
  [sums]
  (reduce (fn [m [acct amt]]
            (reduce (fn [m n] (update m (subvec acct 0 n) (fnil + 0M) amt))
                    m (range 1 (inc (count acct)))))
          {} sums))

(defn- rows
  "Build display rows {:amt :name :depth} for tree? or flat output."
  [sums tree?]
  (if tree?
    (let [tot         (rollups sums)
          ;; show accounts with their own (direct) amount; zero-own parents
          ;; collapse into their child, hledger-style. Displayed amount is the
          ;; rolled-up total (own + descendants).
          interesting (->> sums (keep (fn [[a v]] (when-not (zero? v) a))) set)]
      (for [acct (sort-by (partial str/join ":") interesting)]
        (let [parent (some (fn [n] (let [a (subvec acct 0 n)]
                                     (when (interesting a) a)))
                           (range (dec (count acct)) 0 -1))
              depth  (count (filter #(interesting (subvec acct 0 %))
                                    (range 1 (count acct))))]
          {:amt   (tot acct)
           :name  (str/join ":" (subvec acct (count (or parent []))))
           :depth depth})))
    (for [acct (sort-by (partial str/join ":") (keys sums))
          :when (not (zero? (sums acct)))]
      {:amt (sums acct) :name (str/join ":" acct) :depth 0})))

(defn- render-block
  "Right-aligned amount column, indented names, separator and total line."
  [sums tree?]
  (let [rs    (rows sums tree?)
        total (reduce + 0M (vals sums))
        astrs (conj (mapv (comp fmt :amt) rs) (fmt total))
        w     (apply max 0 (map count astrs))
        pad   (fn [s] (str (apply str (repeat (- w (count s)) \space)) s))
        body  (map (fn [r] (str (pad (fmt (:amt r))) "  "
                                (apply str (repeat (* 2 (:depth r)) \space))
                                (:name r)))
                   rs)]
    (str/join "\n" (concat body
                           [(apply str (repeat w \-))
                            (pad (fmt total))]))))

;; ---------------------------------------------------------------------------
;; public renderers
;; ---------------------------------------------------------------------------
(defn render [txns {:keys [tree? accounts]}]
  (let [all  (account-sums txns)
        sums (if (seq accounts)
               (into {} (filter (fn [[acct _]]
                                  (let [a (str/join ":" acct)]
                                    (some #(str/includes? a %) accounts)))
                                all))
               all)]
    (render-block sums (boolean tree?))))

(defn- sectioned [txns tree? sections & [net-sign]]
  (let [all (account-sums txns)
        secs (for [[label types] sections]
               (let [s (into {} (filter (fn [[a _]] (types (first a))) all))]
                 {:label label :sums s :total (reduce + 0M (vals s))}))
        ;; net-sign -1 for the income statement: profit = -(revenues + expenses)
        ;; in stored signs (credits negative, debits positive).
        net  (* (bigdec (or net-sign 1)) (reduce + 0M (map :total secs)))]
    (str (str/join "\n\n"
                   (for [{:keys [label sums]} secs]
                     (str label "\n" (render-block sums (boolean tree?)))))
         "\n\nNet: " (fmt net))))

(defn balance-sheet [txns {:keys [tree?]}]
  (sectioned txns tree?
             [["Assets"      #{"Assets"}]
              ["Liabilities" #{"Liabilities"}]
              ["Equity"      #{"Equity"}]]))

(defn income-statement [txns {:keys [tree?]}]
  (sectioned txns tree?
             [["Revenues" #{"Income" "Revenues"}]
              ["Expenses" #{"Expenses"}]]
             -1))
