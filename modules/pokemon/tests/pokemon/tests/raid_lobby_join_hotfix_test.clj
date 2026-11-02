(ns pokemon.tests.raid-lobby-join-hotfix-test
  "Hotfix: idle sweeper não mata lobby; mínimo real=1; recomendados só UX."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.battle-idle :as idle]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.raid-auto :as domain]
            [pokemon.infrastructure.datomic.battle-idle-store :as idle-store]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-lobby-join-hotfix")
(def ^:private storage-rel ".datomic-local/raid-lobby-join-hotfix")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      [s conn])))

(def ^:private mon
  {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 40
   :tipos ["normal"] :nivel 8 :raridade "raro"
   :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 80 :classe :fisico}]})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon! s chat player mon 200 nil))

(defn- spawn-mitico!
  [s chat agora]
  (raid-auto/upsert-schedule!
   s {:chat-id chat :enabled true :interval-ms (* 6 60 60 1000)
      :next-at agora :ginasio-index 0 :agora-ms agora
      :next-categoria "mitico" :next-reminders? true})
  (raid-auto/tick! s {:agora-ms agora :global-enabled? true :chat-id chat})
  (raid-app/active-raid s chat))

;;;; Domínio — mínimo real / recomendados

(deftest lobby-params-minimo-real-um-recomendados-informativos
  (doseq [[cat rec] [["raro" 1] ["epico" 2] ["lendario" 2] ["mitico" 3]]]
    (let [p (raid/lobby-params-for-categoria cat)]
      (is (= 1 (:min-jogadores p)) cat)
      (is (= rec (:recomendados p)) cat)
      (is (= 5 (:max-jogadores p)))))
  (is (= 1 raid/min-participantes)))

(deftest format-opened-recomendados-sem-bloquear
  (let [lobby (raid/lobby-params-for-categoria "mitico")
        t (domain/format-opened
           {:nome-ginasio "Central" :liga "diamante"
            :chefe {:nome "Mewtwo" :hp 4000 :raridade "mitico"}
            :boss-categoria "mitico" :lobby lobby
            :min-jogadores 1 :recomendados 3 :max-jogadores 5
            :lobby-minutos 30})]
    (is (re-find #"Jogadores recomendados: 3" t))
    (is (re-find #"Participantes: 0/5" t))
    (is (re-find #"pode tentar sozinho" t))
    (is (not (re-find #"mín\. 3" t)))
    (is (re-find #"1\+ inscrito" t))))

(deftest iniciar-e-expirar-com-um-jogador
  (let [lobby (raid/lobby-params-for-categoria "mitico")
        r0 (raid/nova-raid
            {:raid-id "r1" :chat-id "c" :ginasio "pedra" :nome-ginasio "Pedra"
             :nivel-ginasio 10 :liga "iniciante"
             :chefe {:nome "Mewtwo" :hp 100 :ataque 80 :defesa 50 :atq-esp 80
                     :def-esp 50 :veloc 90 :tipos ["psychic"] :raridade "mitico"
                     :golpes [{:nome-exibicao "Psíquic" :tipo "psychic"
                               :poder 90 :classe :especial}]}
             :agora 1000 :criador nil
             :min-jogadores (:min-jogadores lobby)
             :max-jogadores (:max-jogadores lobby)
             :duracao-ms (:duracao-ms lobby)})
        r1 (raid/entrar r0 {:player-id "ash" :nome "Ash" :save-id "a"
                            :fence-generation 1 :pokemon mon :collection-index 0}
                        1001)
        early (raid/iniciar r1 "ash" 1002)
        solo-expire (raid/expirar-inscricoes r1 (+ 1000 (:duracao-ms lobby) 1))
        empty-expire (raid/expirar-inscricoes r0 (+ 1000 (:duracao-ms lobby) 1))]
    (is (= "combate" (:fase early)))
    (is (= :combate (:acao solo-expire)))
    (is (= "combate" (get-in solo-expire [:raid :fase])))
    (is (= :cancelada (:acao empty-expire)))
    (is (= 3 (raid/recomendados-da-raid (assoc r1 :boss-categoria "mitico"))))))

;;;; Reprodução do bug — idle sweeper matava lobby

(deftest idle-sweeper-nao-mata-lobby-auto
  (testing "reproduz bug: backfill+expire em inscricoes limpava ponteiro"
    (let [[s conn] (fresh! "idle-kills-lobby")
          chat "pilot-idle@g.us"
          agora 1000000]
      (boot! s chat "ash")
      (let [raid (spawn-mitico! s chat agora)
            rid (:raid-id raid)]
        (is (some? rid))
        (is (= "inscricoes" (:fase raid)))
        (is (= "auto" (:origem raid)))
        (is (= 1 (:min-jogadores raid)))
        ;; LEGACY buggy path: touch idle on lobby (o que find-open fazia)
        (d/transact conn {:tx-data (idle-store/raid-touch-tx rid agora)})
        (let [after-touch (raid-app/active-raid s chat)
              expires (:idle-expires-at after-touch)]
          (is (some? expires))
          (is (idle/expired? expires (+ agora (idle/idle-timeout-ms))))
          ;; Novo sweeper: NÃO inclui lobby em find-expired / find-missing
          (is (empty? (idle-store/find-combat-raids-missing-idle (d/db conn))))
          (is (empty? (idle-store/find-expired-raid-ids
                       (d/db conn) (+ agora (idle/idle-timeout-ms) 1))))
          ;; expire-due não remove o ponteiro
          (ports/expire-idle-battles!
           s {:agora (+ agora (idle/idle-timeout-ms) 1) :limit 8})
          (let [still (raid-app/active-raid s chat)]
            (is (some? still))
            (is (= rid (:raid-id still)))
            (is (= "inscricoes" (:fase still))))
          ;; join ainda funciona no meio do lobby (após “5 min idle”)
          (let [j (tx/join-raid!
                   s {:chat-id chat :player-id "ash" :save-id "a"
                      :fence-generation (fence s chat "ash")
                      :operation-id "join-after-idle-window"
                      :use-active? true :nome "Ash"
                      :agora-ms (+ agora 10)})]
            (is (= rid (get-in j [:raid :raid-id])))
            (is (or (= 1 (get-in j [:raid :n-participantes]))
                    (= 1 (count (:ordem (raid-app/active-raid s chat))))))))))))

(deftest idle-expire-tx-skips-lobby
  (let [[s conn] (fresh! "idle-skip-lobby")
        chat "pilot-skip@g.us"
        agora 2000000]
    (boot! s chat "ash")
    (let [raid (spawn-mitico! s chat agora)
          rid (:raid-id raid)
          built (idle-store/build-expire-raid-tx (d/db conn) rid (+ agora 999999))]
      (is (= "lobby" (:skipped (:result built))))
      (is (empty? (:tx built)))
      (is (some? (raid-app/active-raid s chat))))))

(deftest entrar-auto-e-manual-mesmo-fluxo
  (let [[s _] (fresh! "join-auto-manual")
        chat "pilot-join@g.us"
        agora 3000000]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (let [auto (spawn-mitico! s chat agora)]
      (is (some? auto))
      (tx/join-raid!
       s {:chat-id chat :player-id "ash" :save-id "a"
          :fence-generation (fence s chat "ash")
          :operation-id "ja" :auto? true :nome "Ash" :agora-ms (inc agora)})
      (is (= 1 (count (:ordem (raid-app/active-raid s chat))))))
    ;; manual no outro chat
    (let [mchat "manual-join@g.us"]
      (boot! s mchat "brock")
      (tx/open-raid!
       s {:chat-id mchat :player-id "brock" :save-id "a"
          :fence-generation (fence s mchat "brock")
          :operation-id "open-m" :ginasio "pedra" :agora-ms agora})
      (tx/join-raid!
       s {:chat-id mchat :player-id "brock" :save-id "a"
          :fence-generation (fence s mchat "brock")
          :operation-id "jm" :use-active? true :nome "Brock"
          :agora-ms (inc agora)})
      (let [st (tx/start-raid!
                s {:chat-id mchat :player-id "brock" :save-id "a"
                   :fence-generation (fence s mchat "brock")
                   :operation-id "start-solo" :agora-ms (+ agora 2)})]
        (is (= "combate" (get-in st [:raid :fase])))))))

(deftest chat-errado-nao-ve-raid
  (let [[s _] (fresh! "join-wrong-chat")
        chat "pilot-a@g.us"
        other "pilot-b@g.us"
        agora 4000000]
    (boot! s chat "ash")
    (boot! s other "ash")
    (spawn-mitico! s chat agora)
    (is (nil? (raid-app/active-raid s other)))
    (is (= :raid_not_found
           (try
             (tx/join-raid!
              s {:chat-id other :player-id "ash" :save-id "a"
                 :fence-generation (fence s other "ash")
                 :operation-id "wrong-chat" :auto? true :nome "Ash"
                 :agora-ms (inc agora)})
             (catch Exception ex (err/error-code ex)))))))
