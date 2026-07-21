(ns ledger.store-test
  "Persistence-layer roundtrips against real temp git repos: whatever the bot
   appends must read back identically, undo must be append's exact inverse
   (once — HEAD-only by design), and a failed validation must leave no trace."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ledger.bot-test :as fx]
            [ledger.core :as core]
            [ledger.properties-test :as props]
            [ledger.store :as store]))

(defn- git [dir & args] (apply sh "git" "-C" (str dir) args))

(defn- fresh-repo [initial-content]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "bbledger-store"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        f   (java.io.File. dir "household.ledger")]
    (spit f initial-content)
    (doseq [args [["init" "-q"] ["config" "user.email" "bot@test"]
                  ["config" "user.name" "test"] ["add" "."] ["commit" "-qm" "init"]]]
      (apply git dir args))
    {:dir dir :file f :cfg {:ledger-file (str f)}}))

(defn- fresh-repo-with-remote
  "Like fresh-repo, but with a bare `origin` the working repo tracks — so
   store/push! has somewhere to push. Returns {:bare :cfg} (bare is the origin)."
  [initial-content]
  (let [tmp  #(.toFile (java.nio.file.Files/createTempDirectory
                        "bbledger-store" (make-array java.nio.file.attribute.FileAttribute 0)))
        bare (tmp)
        work (tmp)
        f    (java.io.File. work "household.ledger")]
    (apply sh "git" "init" "-q" "--bare" "-b" "main" [(str bare)])
    (spit f initial-content)
    (doseq [args [["init" "-q" "-b" "main"] ["config" "user.email" "bot@test"]
                  ["config" "user.name" "test"] ["remote" "add" "origin" (str bare)]
                  ["add" "."] ["commit" "-qm" "init"] ["push" "-q" "origin" "main"]]]
      (apply git work args))
    {:bare bare :cfg {:ledger-file (str f)}}))

(defn- commit-count [dir]
  (count (str/split-lines (:out (git dir "log" "--format=%s")))))

(defn- essence
  "The parts of a txn that must survive a write/read cycle. Amounts are
   normalized (stripTrailingZeros) so scale differences don't hide behind =."
  [{:keys [date description postings]}]
  {:date date :description description
   :postings (mapv (fn [{:keys [account amount]}]
                     [account (.stripTrailingZeros ^BigDecimal amount)])
                   postings)})

(deftest every-appended-expense-reads-back-identically
  (is (props/passes? 12
        (prop/for-all [params-seq   (gen/vector props/gen-expense-params 1 5)
                       trailing-nl? gen/boolean]
          (let [initial (cond-> fx/rules (not trailing-nl?) str/trimr)
                {:keys [dir file cfg]} (fresh-repo initial)
                txns (mapv core/expense params-seq)]
            (run! #(store/append! cfg %) txns)
            (let [{:keys [transactions]} (core/read-str (slurp file))]
              (and (= (mapv essence txns) (mapv essence transactions))
                   (= (+ 1 (count txns)) (commit-count dir)))))))))

(deftest undo-is-the-exact-inverse-of-the-last-append
  (is (props/passes? 12
        (prop/for-all [params-seq (gen/vector props/gen-expense-params 1 4)]
          (let [{:keys [file cfg]} (fresh-repo fx/rules)
                [prior [last-params]] (split-at (dec (count params-seq)) params-seq)]
            (run! #(store/append! cfg (core/expense %)) prior)
            (let [before (slurp file)]
              (store/append! cfg (core/expense last-params))
              (store/undo! cfg)
              (and (= before (slurp file))
                   ;; HEAD is now the revert commit -> a second undo refuses
                   (nil? (store/undo! cfg))
                   (= before (slurp file)))))))))

(deftest push-mirrors-the-latest-commit-to-origin
  (let [{:keys [bare cfg]} (fresh-repo-with-remote fx/rules)
        subjects #(:out (git bare "log" "--format=%s"))]
    (store/append! cfg (core/expense {:date "2026-07-09" :payer "Alice"
                                      :category ["Sonstiges"] :amount 12.30M
                                      :description "Router"}))
    (is (not (str/includes? (subjects) "expense: Router"))
        "append! commits locally; origin unchanged until push!")
    (store/push! cfg)
    (is (str/includes? (subjects) "expense: Router")
        "push! propagated the expense commit to origin")))

(deftest push-tolerates-a-missing-remote
  ;; a plain fresh-repo has no origin; push! must swallow the failure (offline
  ;; behavior) so the local commit still stands
  (let [{:keys [cfg]} (fresh-repo fx/rules)]
    (store/append! cfg (core/expense {:date "2026-07-09" :payer "Bob"
                                      :category ["Sonstiges"] :amount 1M
                                      :description "x"}))
    (is (nil? (store/push! cfg)))))

(deftest failed-validation-leaves-file-and-history-untouched
  (let [{:keys [dir file cfg]} (fresh-repo fx/rules)
        initial (slurp file)
        ;; hand-built txn that bypasses core/expense validation: its rendering
        ;; injects a garbage line, so the whole-file re-parse must fail
        evil {:date "2026-07-09" :description "evil\ngarbage line"
              :postings [{:account ["Assets" "Alice" "Cash"] :amount -1M
                          :commodity "€" :virtual? false}
                         {:account ["Expenses" "Sonstiges"] :amount 1M
                          :commodity "€" :virtual? false}]}]
    (is (thrown? Exception (store/append! cfg evil)))
    (is (= initial (slurp file)) "file restored byte-for-byte")
    (is (= 1 (commit-count dir)) "no commit created")
    (testing "the store still works afterwards"
      (store/append! cfg (core/expense {:date "2026-07-09" :payer "Alice"
                                        :category ["Sonstiges"] :amount 1M
                                        :description "ok"}))
      (is (= 2 (commit-count dir))))))
