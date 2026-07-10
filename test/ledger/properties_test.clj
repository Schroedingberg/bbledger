(ns ledger.properties-test
  "Property-based tests (test.check) for invariants that must hold for ANY
   ledger, not just sample.ledger. Run via clojure.test.check/quick-check
   (defspec's namespace isn't bundled in bb, so we wrap quick-check in is)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ledger.core :as core]
            [ledger.parse :as parse]
            [ledger.report :as report]))

;; --- helpers -------------------------------------------------------------

(defn- bd= [a b] (zero? (.compareTo (bigdec a) (bigdec b))))
(defn- zero-bd? [x] (clojure.core/zero? (.compareTo (bigdec x) 0M)))

(defn passes?
  "Run a property; on failure print the shrunk counterexample."
  [n property]
  (let [r (tc/quick-check n property)]
    (or (:pass? r)
        (do (println "  counterexample:" (pr-str (:shrunk r))) false))))

;; --- generators ----------------------------------------------------------

(def gen-letter (gen/elements (map char (range (int \a) (inc (int \z))))))
(def gen-segment (gen/fmap str/join (gen/vector gen-letter 1 6)))
(def gen-account (gen/vector gen-segment 1 3))

;; 2-decimal bigdec in a realistic money range
(def gen-amount (gen/fmap #(.movePointLeft (bigdec %) 2) (gen/choose -100000000 100000000)))

(defn- real-posting [acct amt] {:account acct :amount amt :commodity "€" :virtual? false})

;; a transaction whose single elided posting must be inferred
(def gen-txn-1elided
  (gen/let [accts (gen/vector gen-account 1 4)
            amts  (gen/vector gen-amount (count accts))
            last  gen-account]
    {:date "2026-01-01" :description "t"
     :postings (conj (mapv real-posting accts amts)
                     {:account last :amount nil :commodity nil :virtual? false})}))

;; an already-balanced transaction (no elision)
(def gen-balanced-txn
  (gen/let [accts (gen/vector gen-account 1 4)
            amts  (gen/vector gen-amount (count accts))]
    {:date "2026-01-01" :description "t"
     :postings (conj (mapv real-posting accts amts)
                     (real-posting ["Equity" "Close"] (- (reduce + 0M amts))))}))

(def gen-rule
  (gen/let [pat   gen-segment
            posts (gen/vector (gen/tuple gen-account gen-amount) 1 3)]
    {:query    [{:type :account :pattern pat}]
     :postings (mapv (fn [[a m]] {:account a :multiplier m :virtual? true}) posts)}))

;; --- properties ----------------------------------------------------------

(deftest infer-balances-zeroes-the-real-postings
  (is (passes? 300
        (prop/for-all [txn gen-txn-1elided]
          (let [ps (->> (report/infer-balances [txn]) first :postings (remove :virtual?))]
            (zero-bd? (reduce + 0M (map :amount ps))))))))

(deftest infer-balances-is-a-noop-when-nothing-is-elided
  (is (passes? 200
        (prop/for-all [txn gen-balanced-txn]
          (= txn (first (report/infer-balances [txn])))))))

(deftest apply-auto-only-appends-virtual-postings
  (is (passes? 200
        (prop/for-all [txns  (gen/vector gen-balanced-txn 0 5)
                       rules (gen/vector gen-rule 0 3)]
          (every? (fn [[in out]]
                    (let [n (count (:postings in))]
                      (and (= (:postings in) (vec (take n (:postings out))))  ; originals untouched
                           (every? :virtual? (drop n (:postings out))))))     ; additions are virtual
                  (map vector txns (report/apply-auto rules txns)))))))

(deftest auto-posting-amount-is-multiplier-times-matched
  (is (passes? 300
        (prop/for-all [amt gen-amount mult gen-amount]
          (let [txn  {:date "d" :description "x" :postings [(real-posting ["Expenses" "X"] amt)]}
                rule {:query [{:type :account :pattern "Expenses"}]
                      :postings [{:account ["V"] :multiplier mult :virtual? true}]}
                v    (->> (report/apply-auto [rule] [txn]) first :postings (filter :virtual?) first)]
            (bd= (:amount v) (* mult amt)))))))

(deftest a-balanced-ledger-conserves-to-zero
  (is (passes? 200
        (prop/for-all [txns (gen/vector gen-txn-1elided 0 6)]
          (let [total (->> (report/infer-balances txns)
                           (mapcat :postings)
                           (remove :virtual?)
                           (map :amount)
                           (reduce + 0M))]
            (zero-bd? total))))))

;; --- core expense roundtrip ----------------------------------------------

(def gen-pos-amount
  (gen/fmap #(.movePointLeft (bigdec %) 2) (gen/choose 1 100000000)))

;; anything the ledger line format can carry: umlauts, digits, punctuation —
;; but not ";" (comment start) or newlines, which core/expense must reject
(def gen-desc
  (gen/fmap (comp str/trim str/join)
            (gen/vector (gen/elements (seq "abcwzäöüß ABZ 019.,!?()-€&+#")) 0 24)))

(def gen-expense-params
  (gen/let [payer gen-segment
            cat   gen-account
            amt   gen-pos-amount
            desc  gen-desc
            day   (gen/choose 1 28)]
    {:date        (format "2026-07-%02d" day)
     :payer       (str/capitalize payer)
     :category    cat
     :amount      amt
     :description desc}))

(deftest expense-renders-and-reads-back-identically
  (is (passes? 300
        (prop/for-all [params gen-expense-params]
          (let [txn (core/expense params)
                p   (-> (core/read-str (core/txn->str txn)) :transactions first)]
            (and (= (:date txn) (:date p))
                 (= (:description txn) (:description p))
                 (= (mapv :account (:postings txn)) (mapv :account (:postings p)))
                 (every? true? (map #(bd= (:amount %1) (:amount %2))
                                    (:postings txn) (:postings p)))))))))

(deftest parser-round-trips-any-amount
  (is (passes? 300
        (prop/for-all [amt     gen-amount
                       suffix? gen/boolean
                       acct    gen-account]
          (let [cell (if suffix? (str amt "€") (str "€" amt))
                text (str "2026-01-01 t\n  " (str/join ":" acct) "  " cell "\n")
                p    (-> (parse/parse-string text) :transactions first :postings first)]
            (and (= acct (:account p)) (bd= amt (:amount p))))))))
