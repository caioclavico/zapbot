(ns pokemon.tests.missions-a432-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.battle :as battle]
            [pokemon.application.capture :as capture]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym]
            [pokemon.application.mission-queries :as missions]
            [pokemon.application.ports :as ports]
            [pokemon.application.pvp :as pvp]
            [pokemon.application.reward :as reward]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.mission-progress :as progress]
            [pokemon.infrastructure.datomic.mission-events :as events]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.tests.missions-a431-test :as foundation])
  (:import [java.time Instant]
           [java.nio.file Files]))

(def instant (Instant/parse "2026-10-20T14:00:00Z"))
(def opts {:instant instant :timezone "America/Sao_Paulo"})
(def rng {:accuracy 0 :crit 99 :damage-var 0 :flee 99 :dodge 99
          :impede-para 99 :cura-sono 0 :cura-gelo 0 :cura-confusao 0
          :auto-confusao 99 :status 99 :status-golpe 99 :contagio 99 :wild-move 0})
(def strong {:nome "Strong" :species "strong" :raridade "comum"
             :hp 200 :ataque 300 :defesa 100 :atq-esp 300 :def-esp 100 :veloc 100
             :tipos ["normal"] :nivel 20
             :golpes [{:nome-exibicao "Tackle" :tipo "normal" :poder 100 :classe :fisico :precisao 100}]})
(def wild (assoc strong :nome "Wild" :species "wild" :hp 1 :ataque 1 :veloc 1
                 :tipos ["water" "flying"]))

(defn fresh []
  (let [{s :store :as env} (foundation/fresh)]
    (doseq [player ["ash" "misty"]]
      (reward/admin-seed-reward! s {:operation-id (str "seed-" player) :chat-id "chat" :player-id player
                                   :save-id "a" :item {:item-id "pokebola" :qty 20}}))
    env))
(defn snapshot [s player slot] (missions/read-missions s "chat" player slot opts))
(defn daily [s player index] (get-in (snapshot s player "a") [:daily :missions index :progresso]))
(defn weekly [s player index] (get-in (snapshot s player "a") [:weekly :missions index :progresso]))
(defn event-count [conn]
  (count (d/q '[:find ?e :where [?e :mission-event/key]] (d/db conn))))
(defn caught! [s op & [player]]
  (capture/attempt-capture! s "chat" (or player "ash")
                            {:operation-id op :pokemon wild :bola "pokebola" :roll 0 :fuga-roll 99}))
(defn start! [s id]
  (collection/adicionar-pokemon! s "chat" "ash" strong 200 nil)
  (battle/start-wild-battle! s "chat" "ash" {:operation-id id :battle-id id :collection-index 0
                                      :pokemon-x strong :pokemon-o wild :hp-x 200 :hp-o 1}))
(defn win! [s id op]
  (battle/confirm-turn! s "chat" "ash" {:operation-id op :battle-id id
                                        :expected-turn-index 0 :action :attack :move-index 0 :rng rng}))
(defn code [f] (try (f) nil (catch Exception ex (or (err/error-code ex) :exception))))

(deftest rules-match-master-and-ignore-unrelated-events
  (doseq [[type daily-id weekly-id] [[:wild-defeated "selvagens" nil]
                                    [:wild-captured "capturas" "tipos"]
                                    [:pvp-won "vitorias" "pvp"]
                                    [:gym-won nil "ginasios"]
                                    [:gift-sent "presentes" "presentes"]]]
    (let [r (progress/apply-event {} {} 1 {:type type :day "2026-10-20" :week "2026-10-19"
                                           :values ["water" "flying"]})]
      (when daily-id (is (= 1 (get-in r [:daily "progresso" daily-id]))))
      (when weekly-id
        (is (= (if (#{"tipos" "ginasios"} weekly-id) ["water" "flying"] 1)
               (get-in r [:weekly weekly-id]))))))
  (doseq [type [:hunt-started :capture-failed :raid-won :evolved :move-learned :item-used :fled]]
    (is (nil? (progress/apply-event {} {} 1 {:type type})))))

(deftest confirmed-capture-two-missions-and-limits
  (binding [events/*clock* (constantly instant)]
    (let [{s :store :keys [conn]} (fresh)]
      (dotimes [i 4] (is (true? (:capturou? (caught! s (str "capture-" i))))))
      (is (= 2 (daily s "ash" 1)))
      (is (= 2 (weekly s "ash" 1)))
      (is (= "completed" (get-in (snapshot s "ash" "a") [:daily :missions 1 :estado])))
      (is (= 0 (daily s "ash" 0)))
      (is (= 4 (event-count conn)))
      (let [event (d/pull (d/db conn)
                          '[:mission-event/data-edn {:mission-event/save [:save/key]}
                            {:mission-event/operation [:operation-receipt/id]}]
                          [:mission-event/key (pr-str ["direct-capture" "capture-0"])])
            data (edn/read-string (:mission-event/data-edn event))]
        (is (= "capture-0" (get-in event [:mission-event/operation :operation-receipt/id])))
        (is (= "chat|ash|a" (get-in event [:mission-event/save :save/key])))
        (is (= ["2026-10-20" "2026-10-19" "ash" "a"]
               (mapv data [:day :week :player-id :save-id]))))
      (is (zero? (:xp-missoes (ports/get-save s "chat" "ash" "a")))))
    (let [{s :store :keys [conn]} (fresh)
          failed (capture/attempt-capture! s "chat" "ash" {:operation-id "fail" :pokemon wild
                                                            :bola "pokebola" :roll 99 :fuga-roll 99})]
      (is (false? (:capturou? failed)))
      (is (= 0 (event-count conn)))
      (is (= 0 (daily s "ash" 1))))))

(deftest counter-limits-and-distinct-sets-match-master
  (doseq [[type id cap] [[:pvp-won "pvp" 3] [:gift-sent "presentes" 10]]]
    (let [result (reduce (fn [state _]
                           (progress/apply-event (:daily state) (:weekly state) 1
                                                 {:type type :day "2026-10-20" :week "2026-10-19"}))
                         {} (range 15))]
      (is (= cap (get-in result [:weekly id])))
      (is (= (if (= type :pvp-won) 1 2)
             (get-in result [:daily "progresso" (if (= type :pvp-won) "vitorias" "presentes")])))))
  (let [values ["water" "fire" "grass" "normal" "flying" "electric"]
        result (progress/apply-event {} {} 1 {:type :wild-captured :values (conj values "water")
                                              :day "2026-10-20" :week "2026-10-19"})]
    (is (= values (get-in result [:weekly "tipos"])))))

(deftest retry-concurrency-and-save-isolation
  (binding [events/*clock* (constantly instant)]
    (let [{s :store :keys [conn client]} (fresh)
          gate (promise)
          tasks (mapv (fn [_] (future @gate (caught! s "same-capture"))) (range 2))]
      (deliver gate true)
      (is (= [false true] (sort (mapv #(boolean (:duplicate @%)) tasks))))
      (is (= 1 (event-count conn)))
      (is (= 1 (count (ports/list-collection s "chat" "ash" "a"))))
      (let [before (snapshot s "ash" "a")
            reopened (trainer/bootstrap! (store/make-store (persistence/ensure-db! client "missions")))]
        (is (true? (:duplicate (caught! reopened "same-capture"))))
        (is (= before (snapshot reopened "ash" "a"))))
      (is (= 0 (daily s "misty" 1)))
      (is (= 0 (get-in (snapshot s "ash" "b") [:daily :missions 1 :progresso])))
      (let [frozen (writer/freeze-writer s "chat" "ash")]
        (trainer/switch-save! s "chat" "ash" "b")
        (is (= :stale_writer_fence
               (code #(capture/attempt-capture! s frozen {:operation-id "stale" :pokemon wild
                                                          :bola "pokebola" :roll 0}))))))
    (let [{s :store :keys [conn]} (fresh)
          gate (promise)
          tasks (mapv (fn [i] (future @gate (caught! s (str "different-" i)))) (range 2))]
      (deliver gate true)
      (doseq [task tasks] (is (true? (:capturou? @task))))
      (is (= 2 (event-count conn)))
      (is (= 2 (daily s "ash" 1))))))

(deftest wild-victory-and-encounter-capture-have-stable-source
  (binding [events/*clock* (constantly instant)]
    (let [{s :store :keys [conn]} (fresh)]
      (start! s "encounter")
      (is (= 0 (event-count conn)))
      (let [r (win! s "encounter" "win")]
        (is (= :win (get-in r [:battle :outcome])))
        (is (= 1 (daily s "ash" 0))))
      (is (true? (:duplicate (win! s "encounter" "win"))))
      (is (some? (code #(win! s "encounter" "another-win-id"))))
      (is (= 1 (daily s "ash" 0)))
      (let [request {:operation-id "encounter-capture" :battle-id "encounter" :bola "pokebola" :roll 0}
            r (capture/attempt-encounter-capture! s "chat" "ash" request)]
        (is (true? (:capturou? r)))
        (is (true? (:duplicate (capture/attempt-encounter-capture! s "chat" "ash" request))))
        (is (some? (code #(capture/attempt-encounter-capture! s "chat" "ash"
                                                                          (assoc request :operation-id "another-cap-id"))))))
      (is (= 1 (daily s "ash" 1)))
      (is (= 2 (weekly s "ash" 1)))
      (is (= 2 (event-count conn))))))

(deftest atomic-failure-before-and-after-commit
  (binding [events/*clock* (constantly instant)]
    (let [{s :store :keys [conn]} (fresh)
          transact d/transact
          original (snapshot s "ash" "a")]
      (testing "falha antes do commit não deixa ação, evento ou progresso pendente"
        (with-redefs [d/transact (fn [& _] (throw (ex-info "simulated-before-commit" {})))]
          (is (some? (code #(caught! s "recover-before")))))
        (is (= original (snapshot s "ash" "a")))
        (is (= 0 (event-count conn)))
        (is (empty? (ports/list-collection s "chat" "ash" "a"))))
      (testing "retry recupera a operação que nunca foi confirmada"
        (is (true? (:capturou? (caught! s "recover-before")))))
      (testing "queda de resposta após commit: caller recupera recibo sem reexecutar"
        (with-redefs [d/transact (fn [& args] (apply transact args)
                                 (throw (ex-info "simulated-after-commit" {})))]
          (is (true? (:duplicate (caught! s "recover-after")))))
        (is (true? (:duplicate (caught! s "recover-after"))))
        (is (= 2 (event-count conn)))
        (is (= 2 (count (ports/list-collection s "chat" "ash" "a"))))
        (is (= 2 (daily s "ash" 1)))))))

(deftest periods-and-old-retry-do-not-contaminate-new-period
  (let [{s :store :keys [conn]} (fresh)
        sunday (Instant/parse "2026-10-19T02:59:59Z")
        monday (Instant/parse "2026-10-19T03:00:00Z")]
    (binding [events/*clock* (constantly sunday)] (caught! s "sunday"))
    (binding [events/*clock* (constantly monday)] (caught! s "monday"))
    (let [current (missions/read-missions s "chat" "ash" nil {:instant monday})]
      (is (= 1 (get-in current [:daily :missions 1 :progresso])))
      (is (= "2026-10-19" (get-in current [:weekly :period])))
      (binding [events/*clock* (constantly instant)]
        (is (true? (:duplicate (caught! s "sunday")))))
      (is (= current (missions/read-missions s "chat" "ash" nil {:instant monday}))))
    (binding [events/*clock* (constantly sunday)]
      (is (= :mission_period_conflict (code #(caught! s "old-uncommitted")))))
    (is (= 2 (event-count conn)))
    (is (= 2 (count (ports/list-collection s "chat" "ash" "a"))))))

(deftest normal-pvp-victory-counts-only-winner-and-not-cancel
  (binding [events/*clock* (constantly instant)]
    (let [{s :store :keys [conn]} (fresh)]
      (collection/adicionar-pokemon! s "chat" "ash" strong 200 nil)
      (collection/adicionar-pokemon! s "chat" "misty" (assoc strong :hp 1 :veloc 1) 1 nil)
      (pvp/open-challenge! s "chat" "ash" {:operation-id "open" :collection-index 0})
      (let [bid (:battle-id (ports/active-pvp-battle s "chat"))]
        (pvp/accept-challenge! s "chat" "misty" {:operation-id "accept" :battle-id bid :collection-index 0})
        (let [r (pvp/confirm-turn! s "chat" "ash" {:operation-id "pvp-win" :battle-id bid
                                                   :expected-turn-index 0 :actor-side :x :action :attack :move-index 0 :rng rng})]
          (is (= :x (get-in r [:battle :outcome]))))
        (is (= 1 (daily s "ash" 2)))
        (is (= 1 (weekly s "ash" 2)))
        (is (= 0 (daily s "misty" 2)))
        (is (= 1 (event-count conn)))))
    (let [{s :store :keys [conn]} (fresh)]
      (doseq [pid ["ash" "misty"]] (collection/adicionar-pokemon! s "chat" pid strong 200 nil))
      (pvp/open-challenge! s "chat" "ash" {:operation-id "open" :collection-index 0})
      (let [bid (:battle-id (ports/active-pvp-battle s "chat"))]
        (pvp/accept-challenge! s "chat" "misty" {:operation-id "accept" :battle-id bid :collection-index 0})
        (pvp/cancel! s "chat" "ash" {:operation-id "forfeit" :battle-id bid})
        (is (= 0 (event-count conn)))
        (is (= 0 (weekly s "misty" 2)))))))

(deftest pvp-winner-other-save-concurrent-with-another-action
  (binding [events/*clock* (constantly instant)]
    (let [{s :store :keys [conn]} (fresh)]
      (collection/adicionar-pokemon! s "chat" "ash" strong 1 :envenenado)
      (collection/adicionar-pokemon! s "chat" "misty" strong 200 nil)
      (pvp/open-challenge! s "chat" "ash" {:operation-id "open" :collection-index 0})
      (let [bid (:battle-id (ports/active-pvp-battle s "chat"))]
        (pvp/accept-challenge! s "chat" "misty" {:operation-id "accept" :battle-id bid
                                                :collection-index 0 :first-turn-tie :x})
        (let [gate (promise)
              turn (future @gate
                           (pvp/confirm-turn! s "chat" "ash" {:operation-id "poison-loss" :battle-id bid
                                                             :actor-side :x :expected-turn-index 0
                                                             :action :defend :rng rng}))
              cap (future @gate (caught! s "winner-capture" "misty"))]
          (deliver gate true)
          (is (= :o (get-in @turn [:battle :outcome])))
          (is (true? (:capturou? @cap))))
        (is (= 0 (daily s "ash" 2)))
        (is (= 1 (daily s "misty" 2)))
        (is (= 1 (daily s "misty" 1)))
        (is (= 1 (weekly s "misty" 2)))
        (is (= 2 (event-count conn)))))))

(deftest gym-battle-and-distinct-identifiers
  (binding [events/*clock* (constantly instant)]
    (let [{s :store :keys [conn]} (fresh)]
      (dotimes [_ 3] (collection/adicionar-pokemon! s "chat" "ash" strong 200 nil))
      (gym/set-gym-team! s "chat" "ash" [0 1 2])
      (gym/start-challenge! s "chat" "ash"
                            {:operation-id "gym-start" :gym-id "pedra" :dia "2026-10-20" :agora 1000
                             :candidates (mapv #(assoc wild :species % :nome % :hp-atual 1)
                                               ["geodude" "sandshrew" "onix"])})
      (loop [n 0]
        (let [b (ports/active-battle s "chat" "ash")]
          (when (and b (:active? b) (< n 12))
            (battle/confirm-turn! s "chat" "ash"
                                  {:operation-id (str "gym-turn-" n) :battle-id (:battle-id b)
                                   :expected-turn-index (:turn-index b) :action :attack :move-index 0
                                   :rng rng :dia "2026-10-20" :agora (+ 2000 n) :pedra-roll 99})
            (recur (inc n)))))
      (is (= 1 (weekly s "ash" 0)))
      (is (= 0 (daily s "ash" 0)))
      (is (= 1 (event-count conn))))
    (let [r (reduce (fn [state v]
                      (:weekly (progress/apply-event {} state 1 {:type :gym-won :values [v]
                                                                 :day "2026-10-20" :week "2026-10-19"})))
                    {} ["pedra" "pedra" "agua" "fogo"])]
      (is (= ["pedra" "agua" "fogo"] (get r "ginasios"))))))

(deftest rejected-operation-and-historical-event-untouched
  (binding [events/*clock* (constantly instant)]
    (let [{s :store :keys [conn]} (fresh)
          history {"novo-recomeco-2026-09" {"progresso" {"capturas" 1} "premiados" []}}]
      (foundation/seed! conn :save/missoes-eventos-edn history)
      (is (some? (code #(capture/attempt-capture! s "chat" "ash"
                                                 {:operation-id "no-ball" :pokemon wild :bola "ultra-bola" :roll 0}))))
      (is (= 0 (event-count conn)))
      (caught! s "valid")
      (is (= "ended" (get-in (snapshot s "ash" "a") [:events 0 :status])))
      (is (= 1 (get-in (snapshot s "ash" "a") [:events 0 :missions 1 :progresso])))
      (is (= history (edn/read-string (:save/missoes-eventos-edn
                                      (d/pull (d/db conn) '[*] [:save/key "chat|ash|a"])))))
      (is (true? (:claims-enabled (snapshot s "ash" "a")))))))

(deftest battle-source-cannot-be-attributed-to-another-trainer
  (binding [events/*clock* (constantly instant)]
    (let [{s :store :keys [conn]} (fresh)]
      (start! s "owner-battle")
      (collection/adicionar-pokemon! s "chat" "misty" strong 200 nil)
      (is (= :mission_event_identity_conflict
             (code #(battle/confirm-turn! s "chat" "misty"
                                          {:operation-id "foreign-win" :battle-id "owner-battle"
                                           :expected-turn-index 0 :action :attack :move-index 0 :rng rng}))))
      (is (= 0 (event-count conn)))
      (is (= 0 (daily s "ash" 0)))
      (is (= 0 (daily s "misty" 0)))
      (is (true? (:active? (ports/get-battle s "owner-battle")))))))

(deftest http-progress-and-no-arbitrary-event-endpoint
  (let [{s :store} (fresh)
        server (bridge/start! {:store s :port 0 :token "test"})
        base (:base-url server)]
    (try
      ;; HTTP lê o período real, então a ação usa o mesmo relógio padrão.
      (start! s "http-battle")
      (win! s "http-battle" "http-win")
      (let [body {:chatId "chat" :playerId "ash" :saveId "a"
                  :fenceGeneration (:fence-generation (writer/freeze-writer s "chat" "ash"))
                  :operationId "http-capture" :battleId "http-battle" :bola "pokebola" :roll 0}]
        (is (= 200 (first (foundation/http base "/v1/encounter/capture" "POST" body "test"))))
        (is (= 200 (first (foundation/http base "/v1/encounter/capture" "POST" body "test")))))
      (let [[status body] (foundation/http base "/v1/missions?chatId=chat&playerId=ash" "GET" nil "test")]
        (is (= 200 status))
        (is (= 1 (get-in body ["data" "daily" "missions" 1 "progresso"])))
        (is (true? (get-in body ["data" "progressIntegrationEnabled"])))
        (is (true? (get-in body ["data" "claimsEnabled"]))))
      (is (= 404 (first (foundation/http base "/v1/missions/events" "POST" {:type "gift-sent"} "test"))))
      (finally (bridge/stop! server)))))

(defn restart-phase! [phase dir]
  (binding [events/*clock* (constantly instant)]
    (let [client (persistence/client {:system "a432-restart" :storage-dir dir})
          conn (persistence/ensure-db! client "events")
          s (trainer/bootstrap! (store/make-store conn))]
      (when (= phase "write")
        (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
        (reward/admin-seed-reward! s {:operation-id "seed" :chat-id "chat" :player-id "ash"
                                     :save-id "a" :item {:item-id "pokebola" :qty 3}})
        ;; Encerra a JVM depois do commit e antes da resposta da ação.
        (let [transact d/transact]
          (with-redefs [d/transact (fn [& args] (apply transact args) (System/exit 0))]
            (caught! s "restart-capture"))))
      (when-not (and (true? (:duplicate (caught! s "restart-capture")))
                     (= 1 (event-count conn)) (= 1 (daily s "ash" 1))
                     (= 1 (count (ports/list-collection s "chat" "ash" "a"))))
        (throw (ex-info "restart-event-mismatch" {}))))))

(deftest action-and-progress-survive-independent-jvms
  (let [dir (str (Files/createTempDirectory "odisseu-a432-restart-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (doseq [phase ["write" "read"]]
      (let [command [(str (System/getProperty "java.home") "/bin/java") "-Xmx256m"
                     "-cp" (System/getProperty "java.class.path") "clojure.main"
                     "-m" "pokemon.tests.missions-a432-test" phase dir]
            process (.start (.redirectErrorStream (ProcessBuilder. ^java.util.List command) true))
            output (slurp (.getInputStream process))]
        (is (= 0 (.waitFor process)) output)))))

(defn -main [& [phase dir]]
  (if phase
    (do (restart-phase! phase dir) (shutdown-agents) (System/exit 0))
    (let [result (clojure.test/run-tests 'pokemon.tests.missions-a432-test)]
      (shutdown-agents)
      (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))))
