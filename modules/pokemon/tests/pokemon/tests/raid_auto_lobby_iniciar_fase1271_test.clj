(ns pokemon.tests.raid-auto-lobby-iniciar-fase1271-test
  "Fase 12.7.1 — lobby dinâmico por categoria + !pk raide iniciar em auto."
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

(def ^:private system "odisseu-raid-lobby-r1271")
(def ^:private storage-rel ".datomic-local/raid-lobby-r1271")

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

;;;; Domínio puro — lobby params

(deftest lobby-params-por-categoria
  (is (= 5 (:minutos (raid/lobby-params-for-categoria "raro"))))
  (is (= 1 (:min-jogadores (raid/lobby-params-for-categoria "raro"))))
  (is (= 1 (:recomendados (raid/lobby-params-for-categoria "raro"))))
  (is (= 10 (:minutos (raid/lobby-params-for-categoria "epico"))))
  (is (= 1 (:min-jogadores (raid/lobby-params-for-categoria "epico"))))
  (is (= 2 (:recomendados (raid/lobby-params-for-categoria "epico"))))
  (is (= 20 (:minutos (raid/lobby-params-for-categoria "lendario"))))
  (is (= 1 (:min-jogadores (raid/lobby-params-for-categoria "lendario"))))
  (is (= 2 (:recomendados (raid/lobby-params-for-categoria "lendario"))))
  (is (= 30 (:minutos (raid/lobby-params-for-categoria "mitico"))))
  (is (= 1 (:min-jogadores (raid/lobby-params-for-categoria "mitico"))))
  (is (= 3 (:recomendados (raid/lobby-params-for-categoria "mitico"))))
  (is (= 5 (:max-jogadores (raid/lobby-params-for-categoria "mitico"))))
  (is (<= (:duracao-ms (raid/lobby-params-for-categoria "mitico"))
          raid/max-duracao-inscricoes)))

(deftest plan-spawn-aplica-lobby-dinamico
  (doseq [[cat minutos rec] [["raro" 5 1] ["epico" 10 2]
                             ["lendario" 20 2] ["mitico" 30 3]]]
    (let [sch (domain/normalize-schedule
               {:enabled true :interval-ms (* 6 60 60 1000) :next-at 1000}
               "c" 1000)
          weights (assoc {"raro" 0.0 "epico" 0.0 "lendario" 0.0 "mitico" 0.0} cat 1.0)
          plan (domain/plan-spawn sch nil 1000
                                  {:rng (constantly 0.0)
                                   :category-weights weights})]
      (is (= :spawn (:acao plan)))
      (is (= cat (:boss-categoria plan)))
      (is (= (* minutos 60 1000) (:duracao-ms plan)))
      (is (= 1 (:min-jogadores plan)))
      (is (= rec (:recomendados plan)))
      (is (= 5 (:max-jogadores plan)))
      (is (= (domain/advance-next-at sch 1000 (:duracao-ms plan))
             (:next-at plan))))))

(deftest format-opened-revela-chefe-e-lobby
  (let [lobby (raid/lobby-params-for-categoria "lendario")
        t (domain/format-opened
           {:nome-ginasio "Pedra"
            :liga "ouro"
            :chefe {:nome "Rayquaza" :hp 3500 :raridade "lendario"}
            :boss-categoria "lendario"
            :lobby lobby
            :min-jogadores 1 :recomendados 2 :max-jogadores 5
            :lobby-minutos 20})]
    (is (re-find #"RAYQUAZA|Rayquaza" t))
    (is (re-find #"Lendária|LENDÁRIA" t))
    (is (re-find #"20 minutos" t))
    (is (re-find #"Jogadores recomendados: 2" t))
    (is (re-find #"iniciar" t))
    (is (re-find #"entrar auto" t))))
(deftest format-reminder-ainda-sem-spoiler
  (doseq [m [30 15 5]]
    (let [t (domain/format-reminder m)]
      (is (not (re-find #"(?i)chefe|rayquaza|lendár|épica|mítica" t))))))

;;;; Integração — iniciar antecipado auto

(deftest iniciar-auto-qualquer-inscrito-sem-criador
  (let [[s _] (fresh! "lobby-start-auto")
        chat "pilot-start@g.us"
        agora 100000]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (boot! s chat "brock")
    (raid-auto/upsert-schedule!
     s {:chat-id chat :enabled true :interval-ms (* 6 60 60 1000)
        :next-at agora :ginasio-index 0 :agora-ms agora})
    ;; Spawn real (categoria aleatória do catálogo)
    (raid-auto/tick! s {:agora-ms agora :global-enabled? true :chat-id chat})
    (let [raid (raid-app/active-raid s chat)
          min-n (long (:min-jogadores raid 2))
          players (vec (take (max min-n 2) ["ash" "misty" "brock"]))]
      (is (nil? (:criador raid)))
      (is (= "auto" (:origem raid)))
      (doseq [[i pid] (map-indexed vector players)]
        (tx/join-raid!
         s {:chat-id chat :player-id pid :save-id "a"
            :fence-generation (fence s chat pid)
            :operation-id (str "join-" i) :collection-index 0 :nome pid
            :agora-ms (+ agora 1 i)}))
      ;; Início antecipado por participante (não criador)
      (let [starter (last players)
            st (tx/start-raid!
                s {:chat-id chat :player-id starter :save-id "a"
                   :fence-generation (fence s chat starter)
                   :operation-id "start-early" :agora-ms (+ agora 50)})
            active (raid-app/active-raid s chat)]
        (is (= "combate" (get-in st [:raid :fase])))
        (is (= "combate" (:fase active)))
        ;; Duplicado falha / não reabre lobby
        (is (thrown? Exception
              (tx/start-raid!
               s {:chat-id chat :player-id starter :save-id "a"
                  :fence-generation (fence s chat starter)
                  :operation-id "start-dup" :agora-ms (+ agora 60)})))))))

(deftest iniciar-auto-aceita-um-inscrito
  "Após hotfix: mínimo real=1 — solo inicia em qualquer categoria."
  (let [[s _] (fresh! "lobby-start-min")
        chat "pilot-min@g.us"
        agora 200000]
    (boot! s chat "ash")
    (raid-auto/upsert-schedule!
     s {:chat-id chat :enabled true :interval-ms (* 6 60 60 1000)
        :next-at agora :ginasio-index 0 :agora-ms agora})
    (raid-auto/tick! s {:agora-ms agora :global-enabled? true :chat-id chat})
    (let [raid (raid-app/active-raid s chat)]
      (is (= 1 (long (:min-jogadores raid 1))))
      (tx/join-raid!
       s {:chat-id chat :player-id "ash" :save-id "a"
          :fence-generation (fence s chat "ash")
          :operation-id "only-one" :collection-index 0 :nome "Ash"
          :agora-ms (inc agora)})
      (let [st (tx/start-raid!
                s {:chat-id chat :player-id "ash" :save-id "a"
                   :fence-generation (fence s chat "ash")
                   :operation-id "solo-ok" :agora-ms (+ agora 2)})]
        (is (= "combate" (get-in st [:raid :fase])))))))
(deftest iniciar-manual-ainda-exige-criador
  (let [[s _] (fresh! "lobby-start-manual")
        chat "manual@g.us"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "man-open" :ginasio "pedra" :agora-ms 1000})
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "mj1" :collection-index 0 :nome "Ash" :agora-ms 1001})
    (tx/join-raid!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "mj2" :collection-index 0 :nome "Misty" :agora-ms 1002})
    (is (thrown? Exception
          (tx/start-raid!
           s {:chat-id chat :player-id "misty" :save-id "a"
              :fence-generation (fence s chat "misty")
              :operation-id "not-creator" :agora-ms 1003})))
    (let [st (tx/start-raid!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "creator-ok" :agora-ms 1004})]
      (is (= "combate" (get-in st [:raid :fase]))))))

(deftest lobby-cheio-nao-inicia-sozinho
  (let [lobby (raid/lobby-params-for-categoria "raro")
        r (raid/nova-raid
           {:raid-id "r-full" :chat-id "c" :ginasio "pedra" :nome-ginasio "Pedra"
            :nivel-ginasio 10 :liga "iniciante"
            :chefe {:nome "Onix" :hp 70 :ataque 60 :defesa 100 :atq-esp 40 :def-esp 55
                    :veloc 50 :tipos ["rock"] :raridade "raro"
                    :golpes [{:nome-exibicao "Rock Throw" :tipo "rock" :poder 50 :classe :fisico}]}
            :agora 1000 :criador nil
            :min-jogadores (:min-jogadores lobby)
            :max-jogadores (:max-jogadores lobby)
            :duracao-ms (:duracao-ms lobby)})
        r5 (reduce
            (fn [acc i]
              (raid/entrar acc
                           {:player-id (str "p" i) :nome (str "P" i) :save-id "a"
                            :fence-generation 1
                            :pokemon mon :collection-index 0}
                           (+ 1000 i)))
            r (range 5))]
    (is (= 5 (count (:ordem r5))))
    (is (= "inscricoes" (:fase r5)))
    ;; Ainda aberto até expira / iniciar
    (is (raid/inscricoes-abertas? r5 2000))
    (is (thrown? Exception
          (raid/entrar r5 {:player-id "p6" :nome "P6" :save-id "a"
                           :fence-generation 1 :pokemon mon :collection-index 0}
                      2001)))))
