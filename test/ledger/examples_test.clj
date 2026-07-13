(ns ledger.examples-test
  "Illustrative, documentation-style examples of how each piece behaves.
   Read these top-to-bottom to understand the data shapes and the engine."
  (:require [clojure.test :refer [deftest is]]
            [ledger.parse :as parse]
            [ledger.report :as report]))

;; --- parsing -------------------------------------------------------------

(defn- one-amount [posting-text]
  (-> (parse/parse-string (str "2026-01-01 t\n  A:B  " posting-text "\n"))
      :transactions first :postings first :amount))

(deftest parses-a-whole-transaction
  (is (= {:date "2026-06-26" :description "Kueche"
          :postings [{:account ["Assets" "Bob" "Cash"] :amount -8927M :commodity "€" :virtual? false}
                     {:account ["Expenses" "Umzug" "IKEA"] :amount 8927M :commodity "€" :virtual? false}]}
         (-> "2026-06-26 Kueche\n  Assets:Bob:Cash  €-8927\n  Expenses:Umzug:IKEA  8927€\n"
             parse/parse-string :transactions first))))

(deftest commodity-may-be-prefix-or-suffix
  (is (= -50M   (one-amount "€-50")))
  (is (= 50M    (one-amount "50€")))
  (is (= 70.53M (one-amount "70.53€"))))

(deftest an-omitted-amount-parses-as-nil
  (is (nil? (one-amount ""))))                ; "  A:B" with no amount → elided

(deftest parses-an-automated-rule
  (is (= {:query    [{:type :account :pattern "Expenses"}]
          :postings [{:account ["Verrechnung" "Alice"] :multiplier -0.6M :virtual? true}]}
         (-> "= Expenses\n  (Verrechnung:Alice)  *-0.6\n"
             parse/parse-string :rules first))))

(deftest a-rule-query-can-exclude-by-description
  (is (= [{:type :account :pattern "Assets:Alice:Cash"}
          {:type :not-desc :pattern "Kaution"}]
         (-> "= Assets:Alice:Cash not:desc:Kaution\n  (V:A)  *-1\n"
             parse/parse-string :rules first :query))))

;; --- report engine -------------------------------------------------------

(deftest infers-the-missing-amount-so-the-entry-balances
  (let [txn {:date "d" :description ""
             :postings [{:account ["Assets" "Alice" "Cash"]  :amount nil  :commodity nil :virtual? false}
                        {:account ["Expenses" "Umzug" "IKEA"] :amount 120M :commodity "€" :virtual? false}]}]
    (is (= -120M (-> (report/infer-balances [txn]) first :postings first :amount)))))

(deftest an-auto-rule-splits-an-expense-by-ratio
  (let [rule {:query    [{:type :account :pattern "Expenses"}]
              :postings [{:account ["Verrechnung" "Alice"]  :multiplier -0.6M :virtual? true}
                         {:account ["Verrechnung" "Bob"] :multiplier -0.4M :virtual? true}]}
        txn  {:date "d" :description "Kueche"
              :postings [{:account ["Expenses" "X"] :amount 100M  :commodity "€" :virtual? false}
                         {:account ["Assets" "Cash"] :amount -100M :commodity "€" :virtual? false}]}
        virt (->> (report/apply-auto [rule] [txn]) first :postings (filter :virtual?))]
    (is (= [["Verrechnung" "Alice"] ["Verrechnung" "Bob"]] (map :account virt)))
    (is (== -60M (:amount (first virt))))
    (is (== -40M (:amount (second virt))))))

(deftest not-desc-excludes-the-matching-transaction
  (let [rule {:query    [{:type :account :pattern "Assets:Alice:Cash"}
                         {:type :not-desc :pattern "Kaution"}]
              :postings [{:account ["V" "A"] :multiplier -1M :virtual? true}]}
        with (fn [desc] {:date "d" :description desc
                         :postings [{:account ["Assets" "Alice" "Cash"] :amount -100M :commodity "€" :virtual? false}]})
        added (fn [txn] (count (filter :virtual? (:postings (first (report/apply-auto [rule] [txn]))))))]
    (is (= 1 (added (with "Miete"))))         ; ordinary payment → rule fires
    (is (= 0 (added (with "Kaution"))))))     ; excluded by not:desc
