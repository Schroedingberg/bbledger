(ns ledger.parse
  "Parser for the hledger-subset journal. Loads resources/ledger.bnf via
   instaparse-bb, groups source lines into blocks, then parses each line.

   Output shapes — the data contract every other namespace consumes:

     txn          {:date \"2026-06-26\"            ; ISO string
                   :description \"Kueche\"         ; \"\" when absent
                   :postings [posting ...]}
     posting      {:account [\"Assets\" \"Bob\" \"Cash\"] ; split on \":\"
                   :amount -8927M                  ; bigdec, nil when elided
                   :commodity \"€\"                ; nil when elided
                   :virtual? false}                ; true when in (parens)
     rule         {:query [term ...] :postings [rule-posting ...]}
     term         {:type :account | :desc | :not-desc, :pattern \"...\"}
     rule-posting {:account [segs] :multiplier -0.6M :virtual? true}

   Amounts are bigdec, accounts vectors of string segments, dates strings
   (compare lexicographically); single commodity \"€\" throughout."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [instaparse.core :as insta]))

(def ^:private parser
  (insta/parser (slurp (io/resource "ledger.bnf"))))

(def ^:private xf
  {:date        str
   :description str/trim
   :header      (fn [date & more] {:date date :description (or (first more) "")})
   :segment     str
   :account     (fn [& segs] (vec segs))
   :real        (fn [acct] {:account acct :virtual? false})
   :virtual     (fn [acct] {:account acct :virtual? true})
   :number      bigdec
   :commodity   str
   :prefixed    (fn [c n] {:commodity c :amount n})
   :suffixed    (fn [n c] {:commodity c :amount n})
   :amount      identity
   :posting     (fn [acct & more]
                  (let [amt (first more)]
                    (assoc acct :amount (:amount amt) :commodity (:commodity amt))))
   :word        str
   :notdesc     (fn [w] {:type :not-desc :pattern w})
   :desc        (fn [w] {:type :desc :pattern w})
   :acct        (fn [w] {:type :account :pattern w})
   :term        identity
   :query       (fn [& terms] (vec terms))
   :mult        identity
   :ruleposting (fn [acct mult] {:account (:account acct)
                                 :multiplier mult
                                 :virtual? true})})

(defn- parse-line [line start]
  (let [tree (insta/parse parser (str/trim line) :start start)]
    (when (insta/failure? tree)
      (throw (ex-info (str "parse error on line: " (pr-str line))
                      {:line line :failure tree})))
    (insta/transform xf tree)))

(defn- strip-comment
  "Drop a trailing inline comment (`; ...`) and trailing whitespace."
  [line]
  (str/trimr (str/replace line #";.*$" "")))

(defn- comment-line? [line]
  (str/starts-with? (str/triml line) ";"))

(defn- group-blocks
  "Split source into ordered blocks: {:type :txn|:rule :head line :body [line...]}.
   A date or `=` line opens a block; indented lines append to the current block;
   blank and full-comment lines are skipped (and close any open block)."
  [text]
  (loop [lines (str/split-lines text), cur nil, out []]
    (if-let [raw (first lines)]
      (let [line (strip-comment raw)]
        (cond
          (str/blank? raw)        (recur (rest lines) nil (cond-> out cur (conj cur)))
          (comment-line? raw)     (recur (rest lines) cur out)
          (str/blank? line)       (recur (rest lines) cur out)
          (str/starts-with? line "=")
          (recur (rest lines) {:type :rule :head line :body []} (cond-> out cur (conj cur)))
          (re-find #"^\d{4}-\d{2}-\d{2}" line)
          (recur (rest lines) {:type :txn :head line :body []} (cond-> out cur (conj cur)))
          :else ;; indented -> posting line for current block
          (recur (rest lines) (update cur :body conj line) out)))
      (cond-> out cur (conj cur)))))

(defn- build-txn [{:keys [head body]}]
  (assoc (parse-line head :header)
         :postings (mapv #(parse-line % :posting) body)))

(defn- build-rule [{:keys [head body]}]
  {:query    (parse-line (str/replace head #"^=\s*" "") :query)
   :postings (mapv #(parse-line % :ruleposting) body)})

(defn parse-string [s]
  (let [blocks (group-blocks s)]
    {:rules        (mapv build-rule (filter #(= :rule (:type %)) blocks))
     :transactions (mapv build-txn  (filter #(= :txn  (:type %)) blocks))}))

(defn parse-file [path]
  (parse-string (slurp path)))
