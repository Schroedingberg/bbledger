(ns ledger.webhook-test
  "JVM-only integration test for the webhook transport in ledger.main: a real
   http-kit server on an ephemeral port receiving real HTTP POSTs, the real
   store (temp dir, real git) and the real bot/core pipeline behind it. Only
   Telegram's outbound client is stubbed (redef of make-request!), and the
   handler is driven directly so the networked setWebhook is bypassed.

   Not part of the bb suite (bb.edn lists namespaces explicitly): ledger.main
   is the one JVM-only namespace, so a test of it can only run under JVM
   (clojure -M:test auto-discovers it)."
  (:require [cheshire.core :as json]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ledger.bot-test :as fx]
            [ledger.main :as main]
            [marksto.clj-tg-bot-api.core :as tg]
            [org.httpkit.server :as http])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

(def ^:private secret "s3cr3t")

(defn- fresh-repo
  "Temp git repo seeded with fx/ledger-fixture (rules + one 100€ Alice expense);
   returns {:dir :cfg} with cfg's :ledger-file pointing into it."
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "bbledger-wh" (make-array java.nio.file.attribute.FileAttribute 0)))
        f   (java.io.File. dir "household.ledger")]
    (spit f fx/ledger-fixture)
    (doseq [a [["init" "-q"] ["config" "user.email" "bot@test"]
               ["config" "user.name" "bbledger-test"] ["add" "."] ["commit" "-qm" "init"]]]
      (apply sh "git" "-C" (str dir) a))
    {:dir dir :cfg (assoc fx/cfg :ledger-file (str f))}))

(defn- subjects [dir]
  (str/split-lines (:out (sh "git" "-C" (str dir) "log" "--format=%s"))))

(defn- rm-rf [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf (.listFiles f)))
  (.delete f))

(defn- with-webhook
  "Stand up the real webhook handler on a loopback-only ephemeral port with
   Telegram's outbound client stubbed (each make-request! captured as
   [method params] in `sent`). Calls (f {:keys [port sent dir]}); always stops
   the server and deletes the temp repo. Self-contained: no external network
   (localhost only, Telegram stubbed), no writes outside its own temp dir."
  [f]
  (let [{:keys [dir cfg]} (fresh-repo)
        sent    (atom [])
        handler (#'main/webhook-handler cfg ::client false secret (Object.))
        stop    (http/run-server handler {:port 0 :ip "127.0.0.1"})
        port    (:local-port (meta stop))]
    (try
      (with-redefs [tg/make-request! (fn [_ method params]
                                       (swap! sent conj [method params]) {:ok true})]
        (f {:port port :sent sent :dir dir}))
      (finally (stop) (rm-rf dir)))))

(def ^:private client (HttpClient/newHttpClient))

(defn- send-req [^HttpRequest req]
  (let [resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)}))

(defn- http-get [url]
  (send-req (-> (HttpRequest/newBuilder (URI/create url)) (.GET) (.build))))

(defn- post
  "POST body (map -> JSON, or a raw string) to the webhook, with the given
   secret-token header (nil = omit it). Returns {:status :body}."
  [port secret-hdr body]
  (let [body (if (string? body) body (json/generate-string body))
        b    (-> (HttpRequest/newBuilder (URI/create (str "http://localhost:" port "/tg")))
                 (.header "content-type" "application/json")
                 (.POST (HttpRequest$BodyPublishers/ofString body)))]
    (when secret-hdr (.header b "x-telegram-bot-api-secret-token" secret-hdr))
    (send-req (.build b))))

(defn- reply-of [sent]
  (->> @sent (filter #(= :send-message (first %))) first second :text))

(deftest records-an-expense-over-http
  (with-webhook
    (fn [{:keys [port sent dir]}]
      (let [resp (post port secret (fx/upd 111 -100 "12,30 Router #Haushalt:Drogerie"))]
        (is (= 200 (:status resp)))
        (testing "the expense is a real git commit in the ledger"
          (is (some #{"expense: Router"} (subjects dir))))
        (testing "a ✓ reply and a delete of the sender's message were sent"
          (let [methods (set (map first @sent))]
            (is (contains? methods :send-message))
            (is (contains? methods :delete-message)))
          (let [reply (reply-of sent)]
            (is (str/starts-with? reply "✓"))
            (is (str/includes? reply "Router"))
            (is (str/includes? reply "Haushalt:Drogerie"))))))))

(deftest a-command-is-answered-over-http
  (with-webhook
    (fn [{:keys [port sent dir]}]
      ;; the seeded fixture settles Alice +40 / Bob -40
      (let [resp (post port secret (fx/upd 111 -100 "/bal"))]
        (is (= 200 (:status resp)))
        (is (str/includes? (reply-of sent) "Alice"))
        (is (= ["init"] (subjects dir)) "a query records nothing")))))

(deftest rejects-wrong-secret-and-serves-health
  (with-webhook
    (fn [{:keys [port sent dir]}]
      (testing "GET is a health probe"
        (is (= 200 (:status (http-get (str "http://localhost:" port "/"))))))
      (testing "POST without the secret header is refused"
        (is (= 403 (:status (post port nil (fx/upd 111 -100 "12,30 X"))))))
      (testing "POST with the wrong secret is refused"
        (is (= 403 (:status (post port "nope" (fx/upd 111 -100 "12,30 X"))))))
      (is (empty? @sent) "nothing reached Telegram")
      (is (= ["init"] (subjects dir)) "nothing was recorded"))))

(deftest ignores-foreign-chat-and-unknown-sender
  (with-webhook
    (fn [{:keys [port sent dir]}]
      (testing "wrong chat -> silently ignored, still 200"
        (is (= 200 (:status (post port secret (fx/upd 111 -999 "12,30 X"))))))
      (testing "unknown sender in the right chat -> silently ignored, still 200"
        (is (= 200 (:status (post port secret (fx/upd 999 -100 "12,30 X"))))))
      (is (empty? @sent))
      (is (= ["init"] (subjects dir))))))

(deftest malformed-body-answers-200-and-changes-nothing
  ;; a bad body must not 5xx: Telegram redelivers on failure, which could
  ;; double-book. The handler catches, logs to stderr (suppressed here), 200s.
  (with-webhook
    (fn [{:keys [port sent dir]}]
      (let [orig System/err]
        (System/setErr (java.io.PrintStream. (java.io.ByteArrayOutputStream.)))
        (try
          (is (= 200 (:status (post port secret "not json"))))
          (finally (System/setErr orig))))
      (is (empty? @sent))
      (is (= ["init"] (subjects dir))))))
