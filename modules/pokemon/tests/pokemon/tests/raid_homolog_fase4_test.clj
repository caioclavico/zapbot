(ns pokemon.tests.raid-homolog-fase4-test
  "Fase 4 — homologação A6 com fixtures sintéticas (Datomic local isolado).
  Não usa saves/produção/staging externo."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.raid-auto :as domain]
            [pokemon.infrastructure.datomic.raid-auto-log :as raid-auto-log]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-homolog-f4")
(def ^:private storage-rel ".datomic-local/raid-homolog-f4")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      [s conn])))

(defn- reopen!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    [s conn]))

(def ^:private mon
  "Stats altos p/ fechar combate sob regra 1-mon + HP chefe 320×n×fator.
   Revide ≈ hp/5 ⇒ ~5 hits/treinador; duo precisa de dano alto/turno."
  {:nome "Snorlax" :hp 280 :ataque 200 :defesa 60 :atq-esp 200 :def-esp 60 :veloc 50
   :tipos ["water"] :nivel 30 :raridade "raro"
   :golpes [{:nome-exibicao "Hydro Pump" :tipo "water" :poder 110 :classe :especial}]})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon! s chat player mon 280 nil)
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-ball-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "ultra-bola" :qty 3}}))

(defn- enable!
  [s chat agora & [{:keys [interval-ms]}]]
  (raid-auto/upsert-schedule!
   s {:chat-id chat :enabled true
      :interval-ms (or interval-ms (* 60 60 1000))
      :next-at agora :ginasio-index 0 :agora-ms agora}))

(defn- capture-logs
  [f]
  (let [sw (java.io.StringWriter.)]
    (binding [*err* sw]
      (let [ret (f)]
        {:ret ret :log (str sw)}))))

(defn- fight-to-victory!
  "Empurra o combate até vitória. Usa variação máxima (100%) para
   determinismo sob 1-mon + Proposta A (HP 320×n×fator)."
  [s chat rid]
  (loop [n 0]
    (let [r (raid-app/get-raid s rid)]
      (cond
        (>= n 120) (throw (ex-info "combate não encerrou" {:fase (:fase r)
                                                           :hp-chefe (:hp-chefe r)}))
        (= "vitoria" (:fase r)) r
        (= "combate" (:fase r))
        (let [pid (:vez r)]
          (tx/raid-attack!
           s {:chat-id chat :player-id pid :save-id "a"
              :fence-generation (fence s chat pid)
              :operation-id (str "atk-" rid "-" n)
              :expected-turn-index (:turn-index r)
              :move-index 0 :agora-ms (+ 50000 n)
              :rng {:raid-damage-var 15}})
          (recur (inc n)))
        :else (throw (ex-info "fase inesperada" {:fase (:fase r)
                                                 :hp-chefe (:hp-chefe r)}))))))

;;;; 1. Auditoria de schema / cadeia A6

(deftest schemas-007-029-030-e-cadeia-a6
  (let [[_ conn] (fresh! "f4-schema")
        db (d/db conn)
        attrs [;; 007
               :raid/id :raid/chat-id :raid/fase :raid/expira :raid/revision
               :raid/rewards-paid? :chat-active-raid/chat-id :chat-active-raid/raid-id
               :raid-participant/key :raid-capture/key
               ;; 029
               :raid/criada-em :raid/min-jogadores :raid/max-jogadores
               ;; 030
               :raid-auto-schedule/chat-id :raid-auto-schedule/enabled
               :raid-auto-schedule/interval-ms :raid-auto-schedule/next-at
               :raid-auto-schedule/revision :raid-auto-schedule/last-spawn-id
               :raid/origem :pokemon-outbox/id :pokemon-outbox/chat-id
               :pokemon-outbox/acked-at]
        mids ["pokemon/007-raids" "pokemon/029-raid-lobby" "pokemon/030-raid-auto"]]
    (doseq [a attrs]
      (is (some? (:db/ident (d/pull db '[:db/ident] a))) (str a)))
    (doseq [mid mids]
      (is (some? (d/pull db '[:odisseu.schema/migration-id]
                         [:odisseu.schema/migration-id mid]))
          mid))
    ;; Cadeia listada no instalador (gaps 026–028 intencionais neste branch).
    (is (some #(re-find #"007-raids" %) schema/migration-files))
    (is (some #(re-find #"029-raid-lobby" %) schema/migration-files))
    (is (some #(re-find #"030-raid-auto" %) schema/migration-files))
    (is (= ".datomic-local/raid-homolog-f4" storage-rel)
        "homologação usa storage sintético isolado")))

;;;; 2. Ciclo completo integrado

(deftest ciclo-completo-auto-ate-proxima-aparicao
  (let [db-name "f4-ciclo"
        [s _] (fresh! db-name)
        chat "chat-ciclo-homolog"
        t0 1000000]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (boot! s chat "brock")
    ;; 1. Agenda
    (enable! s chat t0 {:interval-ms (* 60 60 1000)})
    (is (true? (:enabled (raid-auto/get-schedule s chat))))
    ;; 2–3. Spawn + anúncio outbox
    (let [{:keys [ret log]} (capture-logs
                             #(raid-auto/tick!
                               s {:agora-ms t0 :global-enabled? true :chat-id chat}))
          raid (raid-app/active-raid s chat)
          opened (first (raid-auto/pending-events s {:limit 5}))]
      (is (= 1 (count (:spawned ret))))
      (is (= "inscricoes" (:fase raid)))
      (is (= "auto" (:origem raid)))
      (is (= (domain/spawn-id chat t0) (:raid-id raid)))
      (is (= "raid-opened" (:kind opened)))
      (is (= (domain/outbox-opened-id (:raid-id raid)) (:id opened)))
      (is (re-find #"raid_auto_spawn" log))
      ;; Entrega WA simulada (ack)
      (is (true? (:acked (raid-auto/ack-event! s (:id opened) (+ t0 1)))))
      ;; 4. Inscrições
      (doseq [[pid op ms] [["ash" "j-ash" 2] ["misty" "j-misty" 3] ["brock" "j-brock" 4]]]
        (tx/join-raid!
         s {:chat-id chat :player-id pid :save-id "a"
            :fence-generation (fence s chat pid)
            :operation-id op :collection-index 0 :nome pid :agora-ms (+ t0 ms)}))
      (let [rid (:raid-id raid)
            lobby-end (inc (long (:expira (raid-app/active-raid s chat))))
            ;; 5–6. Encerrar lobby → combate (expira real da categoria)
            {:keys [ret log]} (capture-logs
                               #(raid-auto/tick!
                                 s {:agora-ms lobby-end :global-enabled? true :chat-id chat}))
            active (raid-app/active-raid s chat)
            started (some #(when (= "raid-started" (:kind %)) %)
                          (raid-auto/pending-events s {:limit 20}))]
        (is (some #(= :combate (:acao %)) (:expired ret)))
        (is (= "combate" (:fase active)))
        (is (= rid (:raid-id active)))
        (is (some? started))
        (is (re-find #"raid_auto_lobby_expired" log))
        (is (re-find #"raid_auto_started" log))
        (raid-auto/ack-event! s (:id started) (inc lobby-end))
        ;; 7–8. Combate + recompensas idempotentes
        (let [antes-ash (ports/get-save s chat "ash" "a")
              _ (fight-to-victory! s chat rid)
              r (raid-app/get-raid s rid)
              ash (ports/get-save s chat "ash" "a")
              misty (ports/get-save s chat "misty" "a")
              dup (tx/raid-attack!
                   s {:chat-id chat :player-id "ash" :save-id "a"
                      :fence-generation (fence s chat "ash")
                      :operation-id (str "atk-" rid "-0")
                      :expected-turn-index 0 :move-index 0 :agora-ms 60000})]
          (is (= "vitoria" (:fase r)))
          (is (true? (:rewards-paid? r)))
          (is (= (+ 50 (:coins antes-ash 0)) (:coins ash)))
          (is (= 6 (:pe-raids ash)))
          (is (= 6 (:pe-raids misty)))
          (is (true? (:duplicate dup)))
          ;; 9. Captura
          (let [n0 (count (ports/list-collection s chat "ash" "a"))
                cap (tx/raid-capture!
                     s {:chat-id chat :player-id "ash" :save-id "a"
                        :fence-generation (fence s chat "ash")
                        :operation-id "cap-1" :bola "ultra-bola" :roll 0
                        :agora-ms 70000})
                cap2 (tx/raid-capture!
                      s {:chat-id chat :player-id "ash" :save-id "a"
                         :fence-generation (fence s chat "ash")
                         :operation-id "cap-1" :bola "ultra-bola" :roll 0
                         :agora-ms 70000})]
            (is (true? (:capturou cap)))
            (is (true? (:duplicate cap2)))
            (is (= (inc n0) (count (ports/list-collection s chat "ash" "a")))))
          ;; 10. Próxima aparição (next-at da agenda)
          (let [sch (raid-auto/get-schedule s chat)
                next-at (:next-at sch)
                _ (is (> next-at lobby-end))
                r2 (raid-auto/tick!
                    s {:agora-ms next-at :global-enabled? true :chat-id chat})
                raid2 (raid-app/active-raid s chat)]
            (is (some #(= :spawn (:acao %)) (:spawned r2)))
            (is (= "inscricoes" (:fase raid2)))
            (is (= "auto" (:origem raid2)))
            (is (not= rid (:raid-id raid2)))
            (is (= (domain/spawn-id chat next-at) (:raid-id raid2)))))))))

;;;; 3. Cenários de falha

(deftest reinicio-durante-lobby-preserva-estado
  (let [db "f4-restart-lobby"
        [s _] (fresh! db)
        chat "chat-rl"
        t0 2000]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (enable! s chat t0)
    (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id chat})
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "ja" :collection-index 0 :nome "Ash" :agora-ms (inc t0)})
    (let [rid (:raid-id (raid-app/active-raid s chat))
          [s2 _] (reopen! db)
          active (raid-app/active-raid s2 chat)
          sch (raid-auto/get-schedule s2 chat)
          events (raid-auto/pending-events s2 {:limit 10})]
      (is (= rid (:raid-id active)))
      (is (= "inscricoes" (:fase active)))
      (is (true? (:enabled sch)))
      (is (= 1 (count (filter #(= "raid-opened" (:kind %)) events))))
      (tx/join-raid!
       s2 {:chat-id chat :player-id "misty" :save-id "a"
           :fence-generation (fence s2 chat "misty")
           :operation-id "jm" :collection-index 0 :nome "Misty" :agora-ms (+ t0 2)})
      (let [lobby-end (inc (long (:expira (raid-app/active-raid s2 chat))))
            r (raid-auto/tick!
               s2 {:agora-ms lobby-end
                   :global-enabled? true :chat-id chat})]
        (is (some #(= :combate (:acao %)) (:expired r)))
        (is (= "combate" (:fase (raid-app/active-raid s2 chat))))))))

(deftest reinicio-durante-combate-preserva-turno
  (let [db "f4-restart-combate"
        [s _] (fresh! db)
        chat "chat-rc"
        t0 3000]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (boot! s chat "brock")
    (enable! s chat t0)
    (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id chat})
    (let [rid (:raid-id (raid-app/active-raid s chat))]
      (doseq [[pid op ms] [["ash" "ja" 1] ["misty" "jm" 2] ["brock" "jb" 3]]]
        (tx/join-raid!
         s {:chat-id chat :player-id pid :save-id "a"
            :fence-generation (fence s chat pid)
            :operation-id op :collection-index 0 :nome pid :agora-ms (+ t0 ms)}))
      (let [lobby-end (inc (long (:expira (raid-app/active-raid s chat))))]
        (raid-auto/tick!
         s {:agora-ms lobby-end :global-enabled? true :chat-id chat})
        (let [r0 (raid-app/get-raid s rid)
              pid (:vez r0)]
          (tx/raid-attack!
           s {:chat-id chat :player-id pid :save-id "a"
              :fence-generation (fence s chat pid)
              :operation-id "atk-pre-restart"
              :expected-turn-index (:turn-index r0)
              :move-index 0 :agora-ms (+ lobby-end 10)
              :rng {:raid-damage-var 15}})
          (let [mid (raid-app/get-raid s rid)
                [s2 _] (reopen! db)
                after (raid-app/get-raid s2 rid)]
            (is (= "combate" (:fase after)))
            (is (= (:turn-index mid) (:turn-index after)))
            (is (= (:hp-chefe mid) (:hp-chefe after)))
            (is (false? (:rewards-paid? after)))
            (fight-to-victory! s2 chat rid)
            (is (= "vitoria" (:fase (raid-app/get-raid s2 rid))))))))))

(deftest datomic-indisponivel-nao-avanca-agenda
  (let [[s _] (fresh! "f4-datomic-down")
        chat "chat-dd"
        t0 4000]
    (enable! s chat t0)
    (let [before (raid-auto/get-schedule s chat)]
      (is (thrown? Exception
                   (with-redefs [d/transact (fn [& _]
                                              (throw (ex-info "datomic unavailable"
                                                              {:pokemon/error :unavailable})))]
                     (raid-auto/tick!
                      s {:agora-ms t0 :global-enabled? true :chat-id chat}))))
      (let [after (raid-auto/get-schedule s chat)]
        (is (= (:next-at before) (:next-at after)))
        (is (nil? (raid-app/active-raid s chat)))
        (is (nil? (ports/get-receipt s (domain/spawn-id chat t0))))))))

(deftest falha-envio-whatsapp-mantem-outbox-pendente
  (let [[s _] (fresh! "f4-wa-fail")
        chat "chat-wa"
        t0 5000]
    (enable! s chat t0)
    (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id chat})
    (let [ev (first (raid-auto/pending-events s {:limit 5}))
          rid (:raid-id (raid-app/active-raid s chat))]
      ;; Simula falha de envio: sem ack. Retry de entrega usa o mesmo id.
      (raid-auto-log/emit!
       :raid_auto_delivery_failed
       {:chat-id chat :raid-id rid :event-id (:id ev) :result "send_failed"})
      (is (= 1 (count (raid-auto/pending-events s {:limit 5}))))
      (is (= (:id ev) (:id (first (raid-auto/pending-events s {:limit 5})))))
      ;; Reenvio + ack (at-least-once)
      (is (true? (:acked (raid-auto/ack-event! s (:id ev) (+ t0 10)))))
      (is (empty? (raid-auto/pending-events s {:limit 5})))
      ;; Replay do mesmo due → duplicate; não cria segunda raide/outbox opened
      (raid-auto/upsert-schedule!
       s {:chat-id chat :enabled true :interval-ms (* 60 60 1000)
          :next-at t0 :agora-ms t0})
      (let [r (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id chat})]
        (is (true? (:duplicate (first (:spawned r)))))
        (is (= rid (:raid-id (raid-app/active-raid s chat))))
        (is (empty? (raid-auto/pending-events s {:limit 5})))))))

(deftest ack-duplicado-nao-reabre-evento
  (let [[s _] (fresh! "f4-ack-dup")
        chat "chat-ack"
        t0 6000]
    (enable! s chat t0)
    (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id chat})
    (let [id (:id (first (raid-auto/pending-events s {:limit 3})))]
      (is (true? (:acked (raid-auto/ack-event! s id (+ t0 1)))))
      (is (true? (:duplicate (raid-auto/ack-event! s id (+ t0 2)))))
      (is (empty? (raid-auto/pending-events s {:limit 3}))))))

(deftest dois-ticks-simultaneos-um-spawn
  (let [[s _] (fresh! "f4-race")
        chat "chat-race"
        t0 7000]
    (enable! s chat t0)
    (let [f1 (future (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id chat}))
          f2 (future (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id chat}))
          _ @f1 _ @f2
          events (raid-auto/pending-events s {:limit 10})
          active (raid-app/active-raid s chat)
          receipt (ports/get-receipt s (domain/spawn-id chat t0))]
      (is (= 1 (count events)))
      (is (= "inscricoes" (:fase active)))
      (is (some? receipt)))))

(deftest raide-manual-ativa-nunca-substituida
  (let [[s _] (fresh! "f4-manual")
        chat "chat-man"
        t0 8000]
    (boot! s chat "ash")
    (enable! s chat t0)
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "manual-keep" :ginasio "pedra" :agora-ms (dec t0)})
    (let [{:keys [ret log]} (capture-logs
                             #(raid-auto/tick!
                               s {:agora-ms t0 :global-enabled? true :chat-id chat}))
          active (raid-app/active-raid s chat)]
      (is (= :skip-busy (:acao (first (:spawned ret)))))
      (is (= "manual-keep" (:raid-id active)))
      (is (= "manual" (:origem active)))
      (is (re-find #"raid_auto_skip" log)))))

(deftest agenda-desligada-durante-inscricoes-preserva-lobby
  (let [[s _] (fresh! "f4-disable-mid")
        chat "chat-dis"
        t0 9000]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (enable! s chat t0)
    (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id chat})
    (let [rid (:raid-id (raid-app/active-raid s chat))]
      (tx/join-raid!
       s {:chat-id chat :player-id "ash" :save-id "a"
          :fence-generation (fence s chat "ash")
          :operation-id "ja" :collection-index 0 :nome "Ash" :agora-ms (inc t0)})
      (tx/join-raid!
       s {:chat-id chat :player-id "misty" :save-id "a"
          :fence-generation (fence s chat "misty")
          :operation-id "jm" :collection-index 0 :nome "Misty" :agora-ms (+ t0 2)})
      ;; Pausa novas aparições sem matar lobby
      (raid-auto/upsert-schedule!
       s {:chat-id chat :enabled false :agora-ms (+ t0 3)})
      (let [lobby-end (inc (long (:expira (raid-app/active-raid s chat))))
            r (raid-auto/tick!
               s {:agora-ms lobby-end
                  :global-enabled? true :chat-id chat})
            active (raid-app/active-raid s chat)
            sch (raid-auto/get-schedule s chat)]
        (is (false? (:enabled sch)))
        (is (some #(= :combate (:acao %)) (:expired r)))
        (is (= rid (:raid-id active)))
        (is (= "combate" (:fase active)))
        (is (empty? (filter #(= :spawn (:acao %)) (:spawned r))))))))

;;;; 4. Operacional — kill switches, logs, rollback

(deftest kill-switches-global-e-por-agenda
  (let [[s _] (fresh! "f4-kill")
        chat "chat-k"
        t0 10000]
    (enable! s chat t0)
    (testing "kill switch global"
      (let [r (raid-auto/tick! s {:agora-ms t0 :global-enabled? false})]
        (is (false? (:enabled r)))
        (is (nil? (raid-app/active-raid s chat)))))
    (testing "agenda disabled (rollback de config)"
      (raid-auto/upsert-schedule!
       s {:chat-id chat :enabled false :next-at t0 :agora-ms t0})
      (let [r (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id chat})]
        (is (empty? (:spawned r)))
        (is (nil? (raid-app/active-raid s chat)))))
    (testing "reabilitar agenda"
      (enable! s chat t0)
      (let [r (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id chat})]
        (is (= :spawn (:acao (first (:spawned r)))))))))

(deftest logs-estruturados-emit-sem-pii-extra
  (let [row (raid-auto-log/emit!
             :raid_auto_spawn
             {:chat-id "c" :raid-id "r" :spawn-id "s" :due-at 1 :result "spawn"
              :player-secret "NÃO_DEVE_APARECER" ;; não escalar → filtrado
              :nested {:x 1}})]
    (is (= "raid_auto_spawn" (:event row)))
    (is (= "c" (:chat-id row)))
    (is (nil? (:player-secret row)))
    (is (nil? (:nested row)))
    (doseq [ev [:raid_auto_skip :raid_auto_lobby_expired :raid_auto_started
                :raid_auto_cancelled :raid_auto_delivery_failed]]
      (is (= (name ev) (:event (raid-auto-log/emit! ev {:chat-id "c" :result "ok"})))))))

(deftest pausar-novas-aparicoes-sem-interromper-combate
  ;; Procedimento: enabled=false (ou kill switch global) pausa spawns;
  ;; combate/lobby em andamento continua até o fim natural.
  (let [[s _] (fresh! "f4-pause")
        chat "chat-pause"
        t0 11000]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (boot! s chat "brock")
    (enable! s chat t0)
    (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id chat})
    (doseq [[pid op ms] [["ash" "ja" 1] ["misty" "jm" 2] ["brock" "jb" 3]]]
      (tx/join-raid!
       s {:chat-id chat :player-id pid :save-id "a"
          :fence-generation (fence s chat pid)
          :operation-id op :collection-index 0 :nome pid :agora-ms (+ t0 ms)}))
    ;; Lobby auto varia por categoria (5–30 min) — usar :expira da raid.
    (let [lobby (raid-app/active-raid s chat)
          lobby-end (inc (long (:expira lobby)))]
      (raid-auto/tick!
       s {:agora-ms lobby-end :global-enabled? true :chat-id chat})
      (is (= "combate" (:fase (raid-app/active-raid s chat))))
      ;; Procedimento: enabled=false — combate segue.
      (raid-auto/upsert-schedule!
       s {:chat-id chat :enabled false :agora-ms (inc lobby-end)})
      (let [rid (:raid-id (raid-app/active-raid s chat))]
        (fight-to-victory! s chat rid)
        (is (= "vitoria" (:fase (raid-app/get-raid s rid))))
        (is (nil?
             (->> (raid-auto/tick!
                   s {:agora-ms (+ t0 (* 2 60 60 1000))
                      :global-enabled? true :chat-id chat})
                  :spawned
                  (filter #(= :spawn (:acao %)))
                  seq)))))))
