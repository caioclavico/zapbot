(ns odisseu.cutover.blocks-admins-drill
  "M8.7 — ensaios bloqueio/admins + durable-op (Platform descartável)."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [odisseu.cutover.blocks-admins-durable :as ba]
            [odisseu.cutover.durability-policy :as policy]
            [odisseu.cutover.op-journal :as journal]
            [odisseu.persistence.datomic :as persistence]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.infrastructure.migration.pipeline :as platform-pipe]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.migration.backup :as backup]
            [odisseu.cutover.import :as cut-import])
  (:import [java.net HttpURLConnection URL]
           [java.nio.charset StandardCharsets]
           [java.time Instant]))

(def chat "chat-m87-syn@g.us")
(def chat-b "chat-b-m87@g.us")
(def player "player-m87@c.us")
(def platform-db "m87-platform")
(def pokemon-db "m87-pokemon")

(defn- assert-disposable! [p]
  (when (re-find #"(?i)prod|oracle|live|m53-real|m55-real" (str p))
    (throw (ex-info "m87_path_blocked" {:path p}))))

(defn- wipe-dir! [path]
  (let [f (io/file (persistence/resolve-storage-dir path))]
    (when (.exists f)
      (doseq [c (reverse (file-seq f))]
        (.delete c)))))

(defn- ctx [id]
  (let [base (str ".datomic-local/m87-" id)
        c {:id id
           :system (str "odisseu-m87-" id)
           :plat-live (str base "-plat")
           :plat-bak (str base "-plat-bak")
           :plat-rec (str base "-plat-rec")
           :poke-live (str base "-poke")
           :journal (str base "-journal")
           :maint (str base "-maint")}]
    (doseq [p (vals (dissoc c :id :system))]
      (assert-disposable! p)
      (.mkdirs (io/file (persistence/resolve-storage-dir p))))
    (journal/wipe! (:journal c))
    (wipe-dir! (:maint c))
    (.mkdirs (io/file (:maint c)))
    c))

(defn- durable
  ([c] (durable c {}))
  ([c extra]
   (merge {:mode :optional
           :journal-dir (:journal c)
           :maint-dir (:maint c)
           :policy :strict}
          extra)))

(defn- open! [c]
  (platform-pipe/open-disposable-store! (:plat-live c) (:system c) platform-db))

(defn- reopen! [c]
  (let [client (persistence/client {:system (:system c) :storage-dir (:plat-live c)})
        conn (persistence/ensure-db! client platform-db)]
    (odisseu.platform.infrastructure.datomic.store/make-store conn)))

(defn- reopen-rec! [c]
  (let [client (persistence/client {:system (:system c) :storage-dir (:plat-rec c)})
        conn (persistence/ensure-db! client platform-db)]
    (odisseu.platform.infrastructure.datomic.store/make-store conn)))

(defn- boom [msg] (fn [] (throw (ex-info msg {}))))

(defn- caught-code [thunk]
  (try (thunk) nil
       (catch Exception ex
         (or (err/error-code ex) (.getMessage ex)))))

(defn- bot? [s cid]
  (boolean (:bot? (ports/get-chat-block s cid))))

(defn- cmds [s cid]
  (set (map str (:comandos (ports/get-chat-block s cid)))))

(defn- admin? [s cid pid]
  (boolean (ports/known-admin? s cid [pid])))

;;; ——— cenários ———

(defn scenario-normal!
  []
  (let [c (ctx "normal") s (open! c)
        r1 (ba/set-bot-blocked! s (durable c) chat true "m87-bot1")
        r2 (ba/block-commands! s (durable c) chat ["naval"] "m87-cmd1")
        r3 (ba/remember-admin! s (durable c) chat player "m87-adm1")]
    {:scenario :normal
     :ok? (and (bot? s chat)
               (contains? (cmds s chat) "naval")
               (admin? s chat player)
               (false? (:duplicate r1))
               (false? (:duplicate r2))
               (false? (:duplicate r3)))}))

(defn scenario-retry!
  []
  (let [c (ctx "retry") s (open! c)
        _ (ba/block-commands! s (durable c) chat ["quiz"] "m87-r1")
        r2 (ba/block-commands! s (durable c) chat ["quiz"] "m87-r1")]
    {:scenario :retry
     :ok? (and (true? (:duplicate r2))
               (= #{"quiz"} (cmds s chat)))}))

(defn scenario-payload-mismatch!
  []
  (let [c (ctx "mismatch") s (open! c)
        _ (ba/set-bot-blocked! s (durable c) chat true "m87-same")
        code (caught-code
              (fn [] (ba/set-bot-blocked! s (durable c) chat false "m87-same")))]
    {:scenario :payload-mismatch
     :ok? (and (bot? s chat)
               (or (= "platform_receipt_payload_mismatch" code)
                   (= "journal_operation_id_payload_mismatch" code)
                   (re-find #"payload_mismatch|journal_operation_id" (str code))))}))

(defn scenario-opposing-order!
  "block→unblock e remember→forget: estado final coerente; não ressuscita."
  []
  (let [c (ctx "oppose") s (open! c)
        _ (ba/block-commands! s (durable c) chat ["naval"] "m87-b1")
        _ (ba/unblock-commands! s (durable c) chat ["naval"] "m87-u1")
        _ (ba/remember-admin! s (durable c) chat player "m87-p1")
        _ (ba/forget-admin! s (durable c) chat player "m87-d1")
        _ (ba/set-bot-blocked! s (durable c) chat true "m87-bt")
        _ (ba/set-bot-blocked! s (durable c) chat false "m87-bu")]
    {:scenario :opposing-order
     :ok? (and (not (contains? (cmds s chat) "naval"))
               (not (admin? s chat player))
               (false? (bot? s chat)))}))

(defn scenario-chat-isolation!
  []
  (let [c (ctx "iso") s (open! c)
        _ (ba/block-commands! s (durable c) chat ["naval"] "m87-ia")
        _ (ba/block-commands! s (durable c) chat-b ["quiz"] "m87-ib")
        _ (ba/remember-admin! s (durable c) chat player "m87-iaa")]
    {:scenario :chat-isolation
     :ok? (and (contains? (cmds s chat) "naval")
               (not (contains? (cmds s chat) "quiz"))
               (contains? (cmds s chat-b) "quiz")
               (admin? s chat player)
               (not (admin? s chat-b player)))}))

(defn scenario-before-prepare!
  []
  (let [c (ctx "bprep") s (open! c)
        code (caught-code
              (fn [] (ba/block-commands!
                      s (durable c {:hooks {:before-prepare (boom "x")}})
                      chat ["naval"] "m87-bp")))]
    {:scenario :before-prepare
     :ok? (and (empty? (cmds s chat)) (some? code)
               (empty? (journal/list-entries (:journal c))))}))

(defn scenario-after-commit-journal-fail!
  []
  (let [c (ctx "jfail") s (open! c)
        cache (atom {:blocks {}})
        code (caught-code
              (fn []
                (let [r (ba/block-commands!
                         s (durable c {:hooks {:before-journal (boom "j")}})
                         chat ["naval"] "m87-jf")]
                  ;; só atualizaria cache em sucesso — aqui não chega
                  (swap! cache assoc :blocks (:state r)))))
        recon (ba/reconcile-uncertain! s (:journal c) (:maint c) "m87-jf")]
    {:scenario :after-commit-journal-fail
     :ok? (and (= :durability_compromised code)
               (contains? (cmds s chat) "naval")
               (empty? (:blocks @cache)) ;; cache Node não atualizado
               (= :reconciled (:status recon))
               (false? (:effect-reapplied? recon))
               (true? (policy/maintenance? (:maint c))))}))

(defn scenario-journal-unavailable!
  []
  (let [c (ctx "junavail") s (open! c)
        code (caught-code
              (fn [] (ba/set-bot-blocked!
                      s {:mode :optional
                         :journal-dir "/nonexistent/m87-j"
                         :maint-dir (:maint c)
                         :policy :strict}
                      chat true "m87-ju")))]
    {:scenario :journal-unavailable
     :ok? (and (some? code) (false? (bot? s chat)))}))

(defn scenario-required-strict-missing!
  []
  (let [c (ctx "req") s (open! c)
        code (caught-code
              (fn [] (ba/remember-admin!
                      s {:mode :required-strict :maint-dir (:maint c)}
                      chat player "m87-req")))]
    {:scenario :required-strict-missing
     :ok? (and (= :durability_journal_required code)
               (not (admin? s chat player)))}))

(defn scenario-http-lost!
  []
  (let [c (ctx "http") s (open! c)
        code (caught-code
              (fn [] (ba/block-commands!
                      s (durable c {:hooks {:before-http (boom "h")}})
                      chat ["velha"] "m87-h")))
        r2 (ba/block-commands! s (durable c) chat ["velha"] "m87-h")]
    {:scenario :http-lost
     :ok? (and (some? code)
               (contains? (cmds s chat) "velha")
               (true? (:duplicate r2)))}))

(defn scenario-jvm-reopen!
  []
  (let [c (ctx "reopen") s1 (open! c)
        _ (ba/remember-admin! s1 (durable c) chat player "m87-ro")
        s2 (reopen! c)
        r (ba/remember-admin! s2 (durable c) chat player "m87-ro")]
    {:scenario :jvm-reopen
     :ok? (and (admin? s2 chat player) (true? (:duplicate r)))}))

(defn scenario-volume-replay-order!
  []
  (let [c (ctx "volume") s (open! c)
        _ (ba/block-commands! s (durable c) chat ["naval"] "m87-pre")
        wm (journal/watermark (:journal c))
        _ (backup/snapshot-storage! (:plat-live c) (:plat-bak c))
        ;; pós-backup: block quiz, unblock naval, remember, forget
        _ (ba/block-commands! s (durable c) chat ["quiz"] "m87-post-b")
        _ (ba/unblock-commands! s (durable c) chat ["naval"] "m87-post-u")
        _ (ba/remember-admin! s (durable c) chat player "m87-post-p")
        _ (ba/forget-admin! s (durable c) chat player "m87-post-d")
        live-cmds (cmds s chat)
        live-adm (admin? s chat player)
        _ (wipe-dir! (:plat-live c))
        _ (wipe-dir! (:plat-rec c))
        _ (.mkdirs (io/file (persistence/resolve-storage-dir (:plat-rec c))))
        _ (backup/restore-storage! (:plat-rec c) (:plat-bak c))
        s2 (reopen-rec! c)
        after-restore-cmds (cmds s2 chat)
        replay (ba/replay-journal-after! s2 (:journal c) wm)
        after (cmds s2 chat)
        again (ba/replay-journal-after! s2 (:journal c) wm)]
    {:scenario :volume-replay-order
     :live-cmds live-cmds
     :after after
     :ok? (and (= #{"naval"} after-restore-cmds) ;; só pré-backup
               (= live-cmds after)
               (= #{"quiz"} after) ;; naval removido, quiz presente
               (false? (admin? s2 chat player)) ;; forget venceu
               (false? live-adm)
               (every? :duplicate again)
               (some #(false? (:duplicate %)) replay))}))

(defn scenario-concurrent!
  []
  (let [c (ctx "conc") s (open! c)
        _ (ba/block-commands! s (durable c) chat ["naval"] "m87-c1")
        _ (ba/block-commands! s (durable c) chat ["quiz"] "m87-c2")]
    {:scenario :concurrent
     :ok? (= #{"naval" "quiz"} (cmds s chat))}))

(defn scenario-bridge-and-uncertain-cache!
  []
  (let [c (ctx "bridge")
        plat (open! c)
        poke (cut-import/open-pokemon-store! (:poke-live c) (:system c) pokemon-db)
        _ (trainer/create-trainer! poke {:chat-id chat :player-id player})
        hooks (ba/make-hooks (durable c))
        srv (bridge/start!
             {:host "127.0.0.1" :port 0 :token "m87-token"
              :store poke :platform-store plat
              :platform-hooks hooks})
        url (str (:base-url srv) "/v1/platform/blocks/commands")
        body (str "{\"chatId\":\"" chat
                  "\",\"action\":\"block\",\"commands\":[\"naval\"],"
                  "\"operationId\":\"m87-br\"}")
        conn (doto ^HttpURLConnection (.openConnection (URL. url))
               (.setRequestMethod "POST")
               (.setDoOutput true)
               (.setRequestProperty "Authorization" "Bearer m87-token")
               (.setRequestProperty "Content-Type" "application/json"))
        _ (with-open [os (.getOutputStream conn)]
            (.write os (.getBytes body StandardCharsets/UTF_8)))
        status (.getResponseCode conn)
        ;; simula Node: só aplica wire se HTTP 200
        node-cache (atom nil)
        _ (when (= 200 status)
            (reset! node-cache (cmds plat chat)))
        ;; operação uncertain: Node não deve atualizar
        uncertain-cache (atom {:admins #{}})
        code (caught-code
              (fn []
                (ba/remember-admin!
                 plat (durable c {:hooks {:before-journal (boom "j")}})
                 chat player "m87-unc")
                (swap! uncertain-cache update :admins conj player)))
        _ (bridge/stop! srv)]
    {:scenario :bridge-and-uncertain-cache
     :ok? (and (= 200 status)
               (= #{"naval"} @node-cache)
               (true? (:durable-blocks-admins? srv))
               (= :durability_compromised code)
               (empty? (:admins @uncertain-cache))
               (admin? plat chat player) ;; SoT tem, cache não
               )}))

(defn run-all!
  [{:keys [out-dir] :or {out-dir "target/m8-7"}}]
  (let [root (io/file (persistence/project-root) out-dir)
        _ (.mkdirs root)
        results
        [(scenario-normal!)
         (scenario-retry!)
         (scenario-payload-mismatch!)
         (scenario-opposing-order!)
         (scenario-chat-isolation!)
         (scenario-before-prepare!)
         (scenario-after-commit-journal-fail!)
         (scenario-journal-unavailable!)
         (scenario-required-strict-missing!)
         (scenario-http-lost!)
         (scenario-jvm-reopen!)
         (scenario-volume-replay-order!)
         (scenario-concurrent!)
         (scenario-bridge-and-uncertain-cache!)]
        ok? (every? :ok? results)
        report
        {:milestone "M8.7"
         :at (str (Instant/now))
         :coverage ba/coverage
         :coverage-matrix ba/coverage-matrix
         :results (mapv #(select-keys % [:scenario :ok?]) results)
         :all-ok? ok?
         :rollback-recon-available? false
         :cutover-ready? false
         :exactly-once-claimed? false
         :rpo-zero-claimed? false
         :ok? ok?}]
    (spit (str (io/file root "report.edn")) (with-out-str (pprint/pprint report)))
    report))
