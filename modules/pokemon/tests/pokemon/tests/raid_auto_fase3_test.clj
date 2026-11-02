(ns pokemon.tests.raid-auto-fase3-test
  "Fase 3 — aparições automáticas: agenda, tick, CAS, outbox, multi-grupo."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.raid-auto :as domain]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-auto-f3")
(def ^:private storage-rel ".datomic-local/raid-auto-f3")

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
    s))

(def ^:private mon
  {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 40
   :tipos ["normal"] :nivel 8 :raridade "raro"
   :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 80 :classe :fisico}]})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon! s chat player mon 200 nil))

(defn- enable! [s chat agora]
  (raid-auto/upsert-schedule!
   s {:chat-id chat :enabled true :interval-ms domain/min-interval-ms
      :next-at agora :ginasio-index 0 :agora-ms agora}))

;;;; Domínio

(deftest default-desligado
  (let [s (domain/default-schedule "c" 1000)]
    (is (false? (:enabled s)))
    (is (= raid/intervalo-proxima (:interval-ms s)))
    (is (false? (domain/due? s 999999)))))

(deftest intervalo-minimo-e-next-at-deterministico
  (is (= domain/min-interval-ms raid/duracao-inscricoes))
  (is (= :raid_auto_interval_invalid
         (try (domain/normalize-interval (dec domain/min-interval-ms))
              (catch Exception ex (:pokemon/error (ex-data ex))))))
  (let [sch (domain/normalize-schedule
             {:enabled true :interval-ms domain/min-interval-ms :next-at 1000}
             "c" 1000)
        nxt (domain/advance-next-at sch 1000)]
    ;; next-at > due + lobby mesmo com interval == lobby
    (is (= (inc (+ 1000 raid/duracao-inscricoes)) nxt))
    (is (> nxt (+ 1000 raid/duracao-inscricoes)))))

(deftest pick-ginasio-round-robin
  (is (= "pedra" (:ginasio (domain/pick-ginasio 0))))
  (is (= "agua" (:ginasio (domain/pick-ginasio 1))))
  (is (= "pedra" (:ginasio (domain/pick-ginasio 5)))))

(deftest plan-skip-busy-e-spawn
  (let [sch (domain/normalize-schedule
             {:enabled true :interval-ms domain/min-interval-ms :next-at 1000 :ginasio-index 0}
             "c" 1000)
        active {:raid-id "r1" :fase "inscricoes" :expira 99999 :origem "manual"}
        busy (domain/plan-spawn sch active 1000)
        free (domain/plan-spawn sch nil 1000)]
    (is (= :skip-busy (:acao busy)))
    (is (= :spawn (:acao free)))
    (is (= "pedra" (:ginasio free)))
    (is (= (domain/spawn-id "c" 1000) (:spawn-id free)))
    (is (= (domain/advance-next-at sch 1000 (:duracao-ms free)) (:next-at free)))
    (is (pos? (:duracao-ms free)))
    (is (pos? (:min-jogadores free)))))

;;;; Integração

(deftest kill-switch-global-nao-processa
  (let [[s _] (fresh! "f3-kill")
        chat "chat-k"]
    (enable! s chat 1000)
    (let [r (raid-auto/tick! s {:agora-ms 1000 :global-enabled? false})]
      (is (false? (:enabled r)))
      (is (nil? (raid-app/active-raid s chat))))))

(deftest spawn-abre-lobby-e-outbox
  (let [[s _] (fresh! "f3-spawn")
        chat "chat-s"]
    (enable! s chat 5000)
    (let [r (raid-auto/tick! s {:agora-ms 5000 :global-enabled? true :chat-id chat})
          raid (raid-app/active-raid s chat)
          events (raid-auto/pending-events s {:limit 10})
          lobby (raid/lobby-params-for-categoria (get-in raid [:chefe :raridade]))]
      (is (= 1 (count (:spawned r))))
      (is (= :spawn (:acao (first (:spawned r)))))
      (is (= "inscricoes" (:fase raid)))
      (is (= "auto" (:origem raid)))
      (is (nil? (:criador raid)))
      (is (= 1 (count events)))
      (is (re-find #"RAID" (:texto (first events))))
      (is (re-find #"entrar auto" (:texto (first events))))
      (is (= (+ 5000 (:duracao-ms lobby)) (:expira raid)))
      (is (= (:min-jogadores lobby) (:min-jogadores raid)))
      (is (= (:max-jogadores lobby) (:max-jogadores raid))))))

(deftest spawn-idempotente-por-spawn-id
  (let [[s _] (fresh! "f3-idemp")
        chat "chat-i"]
    (enable! s chat 1000)
    (let [a (raid-auto/tick! s {:agora-ms 1000 :global-enabled? true :chat-id chat})
          ;; next-at avançou; forçar mesmo due via upsert artificial não recria
          ;; o mesmo spawn-id. Replay do mesmo tick com mesmo next-at:
          _ (raid-auto/upsert-schedule!
             s {:chat-id chat :enabled true :interval-ms domain/min-interval-ms
                :next-at 1000 :agora-ms 1000})
          ;; revision mudou; due again with same due-at → same spawn-id → duplicate
          b (raid-auto/tick! s {:agora-ms 1000 :global-enabled? true :chat-id chat})]
      (is (= :spawn (:acao (first (:spawned a)))))
      (is (true? (:duplicate (first (:spawned b)))))
      (is (= 1 (count (raid-auto/pending-events s {:limit 10})))))))

(deftest skip-se-raide-manual-ativa
  (let [[s _] (fresh! "f3-busy")
        chat "chat-b"]
    (boot! s chat "ash")
    (enable! s chat 2000)
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "manual-1" :ginasio "pedra" :agora-ms 1500})
    (let [r (raid-auto/tick! s {:agora-ms 2000 :global-enabled? true :chat-id chat})
          active (raid-app/active-raid s chat)]
      (is (= :skip-busy (:acao (first (:spawned r)))))
      (is (= "manual-1" (:raid-id active)))
      (is (= "manual" (:origem active))))))

(deftest expire-sem-quorum-cancela-e-anuncia
  (let [[s _] (fresh! "f3-exp")
        chat "chat-e"]
    (boot! s chat "ash")
    ;; Intervalo > lobby para o expire não disparar novo spawn no mesmo tick.
    (raid-auto/upsert-schedule!
     s {:chat-id chat :enabled true :interval-ms 3600000
        :next-at 1000 :ginasio-index 0 :agora-ms 1000})
    (raid-auto/tick! s {:agora-ms 1000 :global-enabled? true :chat-id chat})
    (let [raid0 (raid-app/active-raid s chat)
          expira (:expira raid0)]
      ;; Raro exige só 1; forçar cancelamento só quando min > inscritos.
      ;; Com 1 inscrito e min=1 (raro), expire inicia combate — usar 0 inscritos.
      (let [r (raid-auto/tick! s {:agora-ms expira :global-enabled? true :chat-id chat})
            events (raid-auto/pending-events s {:limit 20})]
        (is (some #(= :cancelada (:acao %)) (:expired r)))
        (is (nil? (raid-app/active-raid s chat)))
        (is (some #(= "raid-cancelled" (:kind %)) events))))))

(deftest expire-com-quorum-inicia-combate
  (let [[s _] (fresh! "f3-exp2")
        chat "chat-e2"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (boot! s chat "brock")
    (raid-auto/upsert-schedule!
     s {:chat-id chat :enabled true :interval-ms 3600000
        :next-at 1000 :ginasio-index 0 :agora-ms 1000})
    (raid-auto/tick! s {:agora-ms 1000 :global-enabled? true :chat-id chat})
    (let [raid0 (raid-app/active-raid s chat)
          rid (:raid-id raid0)
          expira (:expira raid0)
          min-n (long (:min-jogadores raid0 2))
          players (cond-> ["ash" "misty"] (>= min-n 3) (conj "brock"))]
      (doseq [[i pid] (map-indexed vector players)]
        (tx/join-raid!
         s {:chat-id chat :player-id pid :save-id "a"
            :fence-generation (fence s chat pid)
            :operation-id (str "j" i) :collection-index 0 :nome pid
            :agora-ms (+ 1001 i)}))
      (let [r (raid-auto/tick! s {:agora-ms expira :global-enabled? true :chat-id chat})
            active (raid-app/active-raid s chat)
            events (raid-auto/pending-events s {:limit 20})]
        (is (some #(= :combate (:acao %)) (:expired r)))
        (is (= "combate" (:fase active)))
        (is (= rid (:raid-id active)))
        (is (false? (:rewards-paid? active)))
        (is (some #(= "raid-started" (:kind %)) events))))))

(deftest reinicio-recupera-agenda-e-outbox
  (let [db "f3-restart"
        [s _] (fresh! db)
        chat "chat-r"]
    (enable! s chat 3000)
    (raid-auto/tick! s {:agora-ms 3000 :global-enabled? true :chat-id chat})
    (let [s2 (reopen! db)
          sch (raid-auto/get-schedule s2 chat)
          events (raid-auto/pending-events s2 {:limit 5})
          raid (raid-app/active-raid s2 chat)]
      (is (true? (:enabled sch)))
      (is (> (:next-at sch) 3000))
      (is (= "inscricoes" (:fase raid)))
      (is (= 1 (count events))))))

(deftest ack-outbox-idempotente-e-falha-mantem-pendente
  (let [[s _] (fresh! "f3-ack")
        chat "chat-a"]
    (enable! s chat 1000)
    (raid-auto/tick! s {:agora-ms 1000 :global-enabled? true :chat-id chat})
    (let [ev (first (raid-auto/pending-events s {:limit 5}))
          a1 (raid-auto/ack-event! s (:id ev) 2000)
          a2 (raid-auto/ack-event! s (:id ev) 2001)]
      (is (true? (:acked a1)))
      (is (false? (:duplicate a1)))
      (is (true? (:duplicate a2)))
      (is (empty? (raid-auto/pending-events s {:limit 5}))))))

(deftest grupos-independentes
  (let [[s _] (fresh! "f3-multi")
        c1 "g1" c2 "g2"]
    (enable! s c1 1000)
    (enable! s c2 1000)
    (raid-auto/tick! s {:agora-ms 1000 :global-enabled? true})
    (let [r1 (raid-app/active-raid s c1)
          r2 (raid-app/active-raid s c2)]
      (is (some? r1))
      (is (some? r2))
      (is (not= (:raid-id r1) (:raid-id r2)))
      (is (= 2 (count (raid-auto/pending-events s {:limit 10})))))))

(deftest manual-fase2-preservado
  (let [[s _] (fresh! "f3-manual")
        chat "chat-m"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    ;; agenda existe mas desligada
    (raid-auto/upsert-schedule!
     s {:chat-id chat :enabled false :next-at 1000 :agora-ms 1000})
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "man" :ginasio "pedra" :agora-ms 1000})
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "ja" :collection-index 0 :nome "Ash" :agora-ms 1001})
    (tx/join-raid!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "jm" :collection-index 0 :nome "Misty" :agora-ms 1002})
    (let [st (tx/start-raid!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "st" :agora-ms 1003})]
      (is (= "combate" (get-in st [:raid :fase])))
      (is (false? (get-in st [:raid :rewards-paid]))))))

;;;; Fase 3.1 — hardening

(deftest intervalo-curto-nao-reaparece-no-mesmo-tick
  (let [[s _] (fresh! "f31-short")
        chat "chat-short"]
    (boot! s chat "ash")
    (enable! s chat 1000)
    (raid-auto/tick! s {:agora-ms 1000 :global-enabled? true :chat-id chat})
    (let [raid0 (raid-app/active-raid s chat)
          lobby-ms (- (long (:expira raid0)) 1000)
          sch1 (raid-auto/get-schedule s chat)
          rid1 (:raid-id raid0)
          ;; Expire no instante do lobby; next-at já passou do fim do lobby.
          r (raid-auto/tick! s {:agora-ms (:expira raid0) :global-enabled? true :chat-id chat})
          active (raid-app/active-raid s chat)
          sch2 (raid-auto/get-schedule s chat)]
      (is (= (inc (+ 1000 lobby-ms)) (:next-at sch1)))
      (is (some #(= :cancelada (:acao %)) (:expired r)))
      ;; Sem novo spawn no mesmo tick
      (is (empty? (filter #(= :spawn (:acao %)) (:spawned r))))
      (is (nil? active))
      (is (= rid1 (:last-raid-id sch2)))
      (is (> (:next-at sch2) (:expira raid0))))))

(deftest ticks-concorrentes-um-vencedor
  (let [[s _] (fresh! "f31-race")
        chat "chat-race"]
    (enable! s chat 5000)
    (let [f1 (future (raid-auto/tick! s {:agora-ms 5000 :global-enabled? true :chat-id chat}))
          f2 (future (raid-auto/tick! s {:agora-ms 5000 :global-enabled? true :chat-id chat}))
          _a @f1
          _b @f2
          events (raid-auto/pending-events s {:limit 10})
          active (raid-app/active-raid s chat)
          spawn-id (domain/spawn-id chat 5000)
          receipt (ports/get-receipt s spawn-id)]
      ;; Invariantes sob corrida: 1 raide, 1 outbox, 1 receipt (histórico preservado).
      (is (= 1 (count events)))
      (is (= "inscricoes" (:fase active)))
      (is (= spawn-id (:raid-id active)))
      (is (some? receipt))
      (is (= "raid-auto-spawn" (:kind receipt))))))

(deftest retry-apos-recibo-nao-avanca-de-novo
  (let [[s _] (fresh! "f31-retry")
        chat "chat-retry"]
    (enable! s chat 1000)
    (let [a (raid-auto/tick! s {:agora-ms 1000 :global-enabled? true :chat-id chat})
          next1 (:next-at (raid-auto/get-schedule s chat))
          ;; Simula timeout: cliente reenvia mesmo due (agenda já avançou; forçamos due)
          _ (raid-auto/upsert-schedule!
             s {:chat-id chat :enabled true :interval-ms domain/min-interval-ms
                :next-at 1000 :agora-ms 1000})
          b (raid-auto/tick! s {:agora-ms 1000 :global-enabled? true :chat-id chat})
          next2 (:next-at (raid-auto/get-schedule s chat))]
      (is (false? (:duplicate (first (:spawned a)))))
      (is (true? (:duplicate (first (:spawned b)))))
      ;; Upsert resetou next-at para 1000; duplicate NÃO deve ter avançado de novo
      ;; (receipt hit antes do TX). next2 permanece 1000 após upsert+duplicate.
      (is (= 1000 next2))
      (is (= 1 (count (raid-auto/pending-events s {:limit 5}))))
      (is (pos? next1)))))

(deftest manual-nunca-substituida-por-auto
  (let [[s _] (fresh! "f31-manual-guard")
        chat "chat-mg"]
    (boot! s chat "ash")
    (enable! s chat 1000)
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "keep-manual" :ginasio "pedra" :agora-ms 900})
    (let [r (raid-auto/tick! s {:agora-ms 1000 :global-enabled? true :chat-id chat})
          active (raid-app/active-raid s chat)]
      (is (= :skip-busy (:acao (first (:spawned r)))))
      (is (= "keep-manual" (:raid-id active)))
      (is (= "manual" (:origem active))))))

(deftest outbox-ids-estaveis-e-ack-duplicado
  (let [[s _] (fresh! "f31-outbox")
        chat "chat-ob"]
    (enable! s chat 2000)
    (raid-auto/tick! s {:agora-ms 2000 :global-enabled? true :chat-id chat})
    (let [rid (:raid-id (raid-app/active-raid s chat))
          ev (first (raid-auto/pending-events s {:limit 5}))]
      (is (= (domain/outbox-opened-id rid) (:id ev)))
      (is (true? (:acked (raid-auto/ack-event! s (:id ev) 3000))))
      (is (true? (:duplicate (raid-auto/ack-event! s (:id ev) 3001))))
      (is (empty? (raid-auto/pending-events s {:limit 5}))))))
