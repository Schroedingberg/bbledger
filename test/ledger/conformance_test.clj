(ns ledger.conformance-test
  "Conformance test: bbledger must reproduce hledger's account balances on
   sample.ledger, both with and without automated-posting expansion (--auto).

   hledger is the reference oracle. We compare its machine-readable CSV
   (`bal --flat --no-total -O csv`, each account's own/exclusive balance)
   against bbledger's per-account posting sums."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [ledger.parse :as parse]
            [ledger.report :as report]))

(def ledger-file "sample.ledger")

;; --- helpers -------------------------------------------------------------

(defn- amount->bigdec [s]
  ;; "€-13396.53" / "€6000.00" -> bigdec
  (bigdec (str/replace s #"[^0-9.\-]" "")))

(defn- simple-csv
  "Parse simple CSV (no embedded commas/quotes — true for hledger account/amount
   output) into rows of strings with surrounding quotes stripped."
  [s]
  (for [line (str/split-lines (str/trim s))]
    (mapv #(str/replace % #"^\"|\"$" "") (str/split line #","))))

(defn- hledger-balances
  "{account-string -> bigdec} from hledger, omitting zero balances."
  [auto?]
  (let [args (cond-> ["hledger" "-f" ledger-file "bal" "--flat" "--no-total" "-O" "csv"]
               auto? (conj "--auto"))
        {:keys [out exit err]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "hledger failed: " err) {})))
    (into {} (for [[acct bal] (rest (simple-csv out))]   ; drop "account","balance" header
               [acct (amount->bigdec bal)]))))

(defn- bb-balances
  "{account-string -> bigdec} from bbledger, omitting zero balances."
  [auto?]
  (let [{:keys [rules transactions]} (parse/parse-file ledger-file)
        txns (cond->> (report/infer-balances transactions)
               auto? (report/apply-auto rules))]
    (->> (mapcat :postings txns)
         (reduce (fn [m {:keys [account amount]}]
                   (update m (str/join ":" account) (fnil + 0M) (or amount 0M)))
                 {})
         (remove (fn [[_ v]] (zero? v)))
         (into {}))))

(defn- num=
  "Equal to the cent. hledger's CSV is rounded to display precision (2 dp);
   bbledger keeps full bigdec precision, so we compare at 2 dp."
  [a b]
  (let [r #(.setScale (bigdec %) 2 java.math.RoundingMode/HALF_UP)]
    (zero? (.compareTo (r a) (r b)))))

(defn- diff
  "Human-readable differences between reference and ours (for failure output)."
  [ref ours]
  (let [ks (into (sorted-set) (concat (keys ref) (keys ours)))]
    (for [k ks
          :let [r (get ref k) o (get ours k)]
          :when (not (and r o (num= r o)))]
      (format "  %-40s hledger=%s  bbledger=%s" k r o))))

(defn- assert-conformance [auto?]
  (let [ref (hledger-balances auto?) ours (bb-balances auto?)]
    (testing (str "accounts match (auto=" auto? ")")
      (is (= (set (keys ref)) (set (keys ours)))
          (str "account sets differ:\n" (str/join "\n" (diff ref ours)))))
    (testing (str "amounts match (auto=" auto? ")")
      (is (every? (fn [k] (num= (ref k) (get ours k 0M))) (keys ref))
          (str "amounts differ:\n" (str/join "\n" (diff ref ours)))))))

;; --- tests ---------------------------------------------------------------

(deftest balances-no-auto   (assert-conformance false))
(deftest balances-with-auto (assert-conformance true))

(deftest totals
  (testing "expenses & assets totals equal hledger"
    (let [b (bb-balances true)
          sum (fn [pred] (reduce + 0M (vals (filter (fn [[k _]] (pred k)) b))))]
      (is (num= 5146.13M (sum #(str/starts-with? % "Expenses"))))
      (is (num= -5146.13M (sum #(str/starts-with? % "Assets"))))
      (is (num= 1037.92M (+ (get b "Verrechnung:Alice" 0M)
                            (get b "Verrechnung:Alice:Kaution" 0M)))))))
