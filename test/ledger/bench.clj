(ns ledger.bench
  "Timing benchmark: bbledger vs hledger on sample.ledger.

   Three honest measurements:
   - hledger        : native binary, full process wall time (incl. its startup)
   - bbledger (proc): `bb ledger ...` subprocess wall time (incl. SCI startup)
   - bbledger (calc): in-process parse+report only (no startup) — the apples-to-
                      apples figure for the actual work."
  (:require [clojure.java.shell :as shell]
            [ledger.parse :as parse]
            [ledger.report :as report]))

(def ledger-file "sample.ledger")

(defn- ms-per [n f]
  (dotimes [_ 3] (f))                       ; warmup
  (let [start (System/nanoTime)]
    (dotimes [_ n] (f))
    (/ (- (System/nanoTime) start) 1e6 n)))

(defn- calc []
  (let [{:keys [rules transactions]} (parse/parse-file ledger-file)]
    (-> transactions report/infer-balances
        (->> (report/apply-auto rules))
        (report/render {:tree? true :commodity "€"}))))

(defn run [& _]
  (let [hledger #(shell/sh "hledger" "-f" ledger-file "bal" "--auto")
        bbproc  #(shell/sh "bb" "ledger" "bal" "-f" ledger-file "--auto")
        h  (ms-per 10 hledger)
        bp (ms-per 5  bbproc)
        bc (ms-per 50 calc)]
    (println "\nbbledger benchmark — `bal --auto` on sample.ledger\n")
    (printf "  %-22s %8.2f ms/run\n" "hledger (process)" h)
    (printf "  %-22s %8.2f ms/run\n" "bbledger (process)" bp)
    (printf "  %-22s %8.2f ms/run\n" "bbledger (calc only)" bc)
    (println)
    (printf "  startup overhead in bbledger process: ~%.0f ms\n" (- bp bc))
    (println)))
