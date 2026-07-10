(ns ledger.integration-test
  "Bigger-scope failing-first test: fake Telegram updates driven through the
   pure bot layer and the REAL store (temp dir, real git), no network.
   Runs under bb and JVM alike; ledger.main (the only JVM-only ns) stays out."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ledger.bot :as bot]
            [ledger.bot-test :as fx]
            [ledger.core :as core]
            [ledger.store :as store]))

(defn- git [dir & args]
  (let [{:keys [exit err] :as res} (apply sh "git" "-C" (str dir) args)]
    (is (zero? exit) (str "git " (first args) ": " err))
    res))

(defn- fresh-repo []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "bbledger-it"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        f   (java.io.File. dir "household.ledger")]
    (spit f fx/rules)
    (git dir "init" "-q")
    (git dir "config" "user.email" "bot@test")
    (git dir "config" "user.name" "bbledger-test")
    (git dir "add" ".")
    (git dir "commit" "-qm" "init")
    {:dir dir :file f}))

(defn- commit-subjects [dir]
  (str/split-lines (:out (git dir "log" "--format=%s"))))

(deftest record-balance-undo-full-flow
  (let [{:keys [dir file]} (fresh-repo)
        cfg  (assoc fx/cfg :ledger-file (str file))
        sent (atom [])
        fns  {:append! #(store/append! cfg %)
              :undo!   #(store/undo! cfg)
              :send!   #(swap! sent conj %)}
        step (fn [from-id text]
               (bot/run-effects!
                (bot/handle-update cfg (store/read-ledger cfg)
                                   (fx/upd from-id -100 text))
                fns))]

    (testing "two expenses are appended, validated, and committed"
      (step 111 "100.00 Testkauf")
      (step 222 "50,00 Drogerie #Haushalt:Drogerie")
      (is (= 2 (count @sent)))
      (let [{:keys [transactions]} (core/read-str (slurp file))]
        (is (= 2 (count transactions)) "the file re-parses cleanly")
        (is (= ["Testkauf" "Drogerie"] (mapv :description transactions))))
      (is (= ["expense: Drogerie" "expense: Testkauf" "init"]
             (commit-subjects dir))))

    (testing "/bal reflects both entries (Alice is owed 10)"
      (step 111 "/bal")
      (let [reply (last @sent)]
        (is (str/includes? reply "Alice"))
        (is (str/includes? reply "10.00"))))

    (testing "/undo reverts exactly the last expense commit"
      (step 111 "/undo")
      (is (str/includes? (last @sent) "Drogerie"))
      (let [{:keys [transactions]} (core/read-str (slurp file))]
        (is (= ["Testkauf"] (mapv :description transactions))))
      (is (= 4 (count (commit-subjects dir))) "revert added one commit"))

    (testing "a second /undo refuses to touch non-bot commits"
      (step 111 "/undo")
      (is (= 4 (count (commit-subjects dir))) "no further commit")
      (let [{:keys [transactions]} (core/read-str (slurp file))]
        (is (= 1 (count transactions)) "ledger unchanged")))))
