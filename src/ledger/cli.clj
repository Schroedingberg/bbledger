(ns ledger.cli
  "CLI for bbledger — babashka.cli dispatch table over the report engine."
  (:require [babashka.cli :as cli]
            [ledger.parse :as parse]
            [ledger.report :as report]))

(def ^:private spec
  {:file {:coerce :string :alias :f :default "sample.ledger" :desc "Ledger file to read"}
   :auto {:coerce :boolean :default false :desc "Expand automated-posting rules"}
   :tree {:coerce :boolean :default true  :desc "Tree-style account display"}})

;; bal also accepts a trailing positional ACCOUNT filter -> :accounts ["..."]
(def ^:private bal-spec
  (assoc spec :accounts {:coerce [] :desc "Account filter (substring)"}))

(defn- load-txns [{:keys [file auto]}]
  (let [{:keys [rules transactions]} (parse/parse-file file)]
    (cond->> (report/infer-balances transactions)
      auto (report/apply-auto rules))))

(defn- bal [{:keys [opts]}]
  (print (report/render (load-txns opts)
                        {:tree? (:tree opts) :commodity "€" :accounts (:accounts opts)})))

(defn- bs [{:keys [opts]}]
  (print (report/balance-sheet (load-txns opts) {:tree? (:tree opts) :commodity "€"})))

(defn- is [{:keys [opts]}]
  (print (report/income-statement (load-txns opts) {:tree? (:tree opts) :commodity "€"})))

(defn- help [_]
  (println (str "bbledger — tiny hledger clone\n"
                "\nUsage: bb ledger <command> [options] [ACCOUNT]\n"
                "\nCommands:\n"
                "  bal, balance           Balance report (optional ACCOUNT filter)\n"
                "  bs,  balancesheet      Balance sheet (Assets/Liabilities/Equity)\n"
                "  is,  incomestatement   Income statement (Income/Expenses)\n"
                "\nOptions:\n"
                (cli/format-opts {:spec bal-spec}))))

(def ^:private table
  [{:cmds ["bal"]             :fn bal :spec bal-spec :args->opts [:accounts]}
   {:cmds ["balance"]         :fn bal :spec bal-spec :args->opts [:accounts]}
   {:cmds ["bs"]              :fn bs  :spec spec}
   {:cmds ["balancesheet"]    :fn bs  :spec spec}
   {:cmds ["is"]              :fn is  :spec spec}
   {:cmds ["incomestatement"] :fn is  :spec spec}
   {:cmds []                  :fn help}])

(defn -main [& args]
  (cli/dispatch table (vec args) {:prog "ledger"}))
