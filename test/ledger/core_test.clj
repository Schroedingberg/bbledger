(ns ledger.core-test
  "Spec for the ledger.core public API.
   Amounts are bigdec: compare with ==, never =."
  (:require [clojure.test :refer [deftest is testing]]
            [ledger.core :as core]))

(def fixture
  "Real income-ratio rules from sample.ledger + three txns.
   Expected settlement, by hand:
     Testkauf  (Alice pays 100): V:Alice -60 +100, V:Bob -40
     Drogerie  (Bob pays 50): V:Alice -30,     V:Bob -20 +50
     Kaution-Anteil (real postings): V:Alice +10,     V:Bob -10
     => Alice 20 (owed), Bob -20 (owes)"
  (str "= Expenses\n"
       "    (Verrechnung:Alice)    *-0.6\n"
       "    (Verrechnung:Bob)   *-0.4\n"
       "\n"
       "= Assets:Alice:Cash not:desc:Kaution\n"
       "    (Verrechnung:Alice)    *-1\n"
       "= Assets:Bob:Cash not:desc:Kaution\n"
       "    (Verrechnung:Bob)   *-1\n"
       "\n"
       "2026-07-01 Testkauf\n"
       "  Assets:Alice:Cash\n"
       "  Expenses:Umzug:Test  100€\n"
       "\n"
       "2026-07-05 Drogerie\n"
       "  Assets:Bob:Cash\n"
       "  Expenses:Haushalt:Drogerie  50€\n"
       "\n"
       "2026-07-06 Kaution-Anteil\n"
       "  Verrechnung:Bob:Kaution   €-10\n"
       "  Verrechnung:Alice:Kaution     €10\n"))

(deftest read-str-parses-and-infers
  (let [{:keys [rules transactions]} (core/read-str fixture)]
    (is (= 3 (count rules)))
    (is (= 3 (count transactions)))
    (testing "every elided amount is filled: real postings sum to zero"
      (doseq [{:keys [postings]} transactions
              :let [real (remove :virtual? postings)]]
        (is (every? :amount real))
        (is (== 0 (reduce + (map :amount real))))))))

(deftest expense-builds-a-balanced-txn
  (let [txn (core/expense {:date "2026-07-09" :payer "Alice"
                           :category ["Umzug" "Kueche"] :amount 45.60M
                           :description "Router"})]
    (is (= "2026-07-09" (:date txn)))
    (is (= "Router" (:description txn)))
    (is (= [["Assets" "Alice" "Cash"] ["Expenses" "Umzug" "Kueche"]]
           (mapv :account (:postings txn))))
    (is (== -45.60M (:amount (first (:postings txn)))))
    (is (== 45.60M (:amount (second (:postings txn)))))
    (is (not-any? :virtual? (:postings txn)))))

(deftest expense-rejects-invalid-input
  (let [valid {:date "2026-07-09" :payer "Alice"
               :category ["Sonstiges"] :amount 1M :description "x"}]
    (doseq [bad [(assoc valid :amount 0M)
                 (assoc valid :amount -5M)
                 (assoc valid :payer "")
                 (assoc valid :category [])
                 (assoc valid :date "9.7.2026")
                 ;; ";" starts a comment in the ledger format, newlines break
                 ;; the block — a desc containing them cannot round-trip
                 (assoc valid :description "Drogerie; Shampoo")
                 (assoc valid :description "Router\nWLAN")
                 ;; category segments join into an account name — ";", ":",
                 ;; parens and whitespace would silently corrupt the posting
                 (assoc valid :category ["Evil;Cat"])
                 (assoc valid :category ["A:B"])
                 (assoc valid :category ["A B"])
                 (assoc valid :category ["(A)"])]]
      (is (thrown? clojure.lang.ExceptionInfo (core/expense bad))
          (pr-str bad)))))

(deftest txn->str-round-trips-through-read-str
  (let [txn    (core/expense {:date "2026-07-09" :payer "Bob"
                              :category ["Umzug" "Moebel"] :amount 314.06M
                              :description "Stühle für die Küche"})
        parsed (-> (core/read-str (core/txn->str txn)) :transactions first)]
    (is (= (:date txn) (:date parsed)))
    (is (= (:description txn) (:description parsed)))
    (is (= (mapv :account (:postings txn)) (mapv :account (:postings parsed))))
    (is (every? true? (map #(== (:amount %1) (:amount %2))
                           (:postings txn) (:postings parsed))))))

(deftest settlement-splits-by-income-ratio
  (let [s (core/settlement (core/read-str fixture))]
    (is (= #{"Alice" "Bob"} (set (keys s))))
    (is (== 20M (s "Alice")))
    (is (== -20M (s "Bob")))
    (testing "zero-sum between the two of us"
      (is (== 0 (reduce + (vals s)))))))

(deftest summary-filters-period-but-settles-all-time
  (let [ledger (core/read-str fixture)]
    (testing "bounded period"
      (let [{:keys [settlement by-category total]}
            (core/summary ledger {:from "2026-07-01" :to "2026-07-04"})]
        (is (== 100M (get by-category ["Umzug" "Test"])))
        (is (nil? (get by-category ["Haushalt" "Drogerie"])))
        (is (== 100M total))
        (testing "settlement ignores the period bounds"
          (is (== 20M (settlement "Alice"))))))
    (testing "unbounded"
      (let [{:keys [by-category total]} (core/summary ledger {})]
        (is (== 50M (get by-category ["Haushalt" "Drogerie"])))
        (is (== 150M total))))))
