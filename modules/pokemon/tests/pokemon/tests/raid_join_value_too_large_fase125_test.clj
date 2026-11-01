(ns pokemon.tests.raid-join-value-too-large-fase125-test
  "Fase 12.5 — !pk raid entrar: Item too large em :raid-participant/pokemon-edn."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-join-vtl-125")
(def ^:private storage-rel ".datomic-local/raid-join-vtl-125")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      {:s s :conn conn})))

(defn- reopen!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(defn- fat-historico
  "Mapa V1 ~1.8KiB+ — cabe no attr canônico, estoura snapshot cru do participante."
  []
  (into {}
        (for [i (range 18)]
          [(keyword (str "g" i))
           {:slug (str "move-hist-" i)
            :nome (str "GolpeHist" i)
            :tipo "rock"
            :poder (+ 10 i)
            :detalhe (apply str (repeat 70 "x"))}])))

(defn- fat-mon
  "Pokémon com historico/ofertas/legado — pr-str cru > 4000."
  [nome]
  {:nome nome :species nome :nickname nome
   :hp 200 :hp-atual 200 :ataque 90 :defesa 70 :atq-esp 80 :def-esp 70 :veloc 60
   :tipos ["rock" "ground"] :nivel 8 :level 8 :raridade "raro"
   :habilidade "sturdy" :shiny false :amizade 70 :xp-desde-nivel 5
   :imagem (apply str (repeat 100 "I"))
   :imagem-shiny (apply str (repeat 100 "S"))
   :historico-golpes (fat-historico)
   :golpes-oferecidos [{:slug "offer" :pad (apply str (repeat 80 "o"))}]
   :ofertas-golpes [{:slug "offer" :pad (apply str (repeat 80 "o"))}]
   :golpes-removidos [{:slug "rem" :pad (apply str (repeat 80 "r"))}]
   :ivs {:hp 31 :atk 31 :def 31 :spa 31 :spd 31 :spe 31}
   :legado {:blob (apply str (repeat 400 "L"))}
   :origin-edn (apply str (repeat 200 "O"))
   :golpes (mapv (fn [i]
                   {:slug (str "atk-" i)
                    :nome-exibicao (str "AtaqueRochaLongo" i)
                    :tipo "rock" :poder 70 :classe :fisico
                    :precisao 100 :alvo :adversario
                    :descricao (apply str (repeat 90 "d"))
                    :efeitos [{:k i :pad (apply str (repeat 40 "e"))}]})
                 (range 4))})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot-fat! [s chat player mon*]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon! s chat player mon* (long (:hp mon*)) nil))

(deftest snapshot-cru-excede-limite-local-slim-cabe
  (let [fat (fat-mon "OnixGordo")
        raw (pr-str fat)
        slim (battle/slim-pokemon-for-battle fat)
        enc (battle/encode-value-edn :raid-participant/pokemon-edn slim)
        decoded (battle/decode-compact-edn enc)]
    (is (> (count raw) 4000) (str "fixture deve exceder; got " (count raw)))
    (is (<= (count enc) 4000) (str "encoded=" (count enc)))
    (is (nil? (:historico-golpes decoded)))
    (is (nil? (:legado decoded)))
    (is (nil? (:golpes-oferecidos decoded)))
    (is (= 4 (count (:golpes decoded))))
    (is (seq (:golpes decoded)))))

(deftest join-com-pokemon-gordo-persiste-sem-item-too-large
  "Regressão prod: DATOMIC_VALUE_TOO_LARGE / Item too large no join."
  (let [{:keys [s]} (fresh! "join-fat")
        chat "chat-fat-join"
        ash "207700373848240@lid"
        misty "5511946342101@c.us"
        rid "raid-fat-join"
        mon-a (fat-mon "Darmanitan-standard")
        mon-m (fat-mon "QuaxlyGordo")]
    (boot-fat! s chat ash mon-a)
    (boot-fat! s chat misty mon-m)
    (let [raw-a (count (pr-str (first (ports/list-collection s chat ash "a"))))
          _ (is (> raw-a 4000) (str "coleção canônica gorda; got " raw-a))
          coins0 (long (:coins (ports/get-save s chat ash "a") 0))
          _ (tx/open-raid!
             s {:chat-id chat :player-id ash :save-id "a"
                :fence-generation (fence s chat ash)
                :operation-id "open-fat" :raid-id rid
                :ginasio "pedra" :agora-ms 1000})
          j1 (tx/join-raid!
              s {:chat-id chat :player-id ash :save-id "a"
                 :fence-generation (fence s chat ash)
                 :operation-id "join-fat-ash"
                 :collection-index 0 :nome "CaioSmoke" :agora-ms 2})
          j2 (tx/join-raid!
              s {:chat-id chat :player-id misty :save-id "a"
                 :fence-generation (fence s chat misty)
                 :operation-id "join-fat-misty"
                 :collection-index 0 :nome "PeerSmoke" :agora-ms 3})
          raid (raid-app/get-raid s rid)
          p-ash (get-in raid [:participantes ash])
          p-misty (get-in raid [:participantes misty])
          col-ash (first (ports/list-collection s chat ash "a"))]
      (is (false? (:duplicate j1)))
      (is (false? (:duplicate j2)))
      (is (= "inscricoes" (:fase raid)))
      (is (= 2 (count (:ordem raid))))
      (is (nil? (get-in p-ash [:pokemon :historico-golpes]))
          "snapshot do participante sem histórico")
      (is (nil? (get-in p-misty [:pokemon :historico-golpes])))
      (is (= 4 (count (get-in p-ash [:pokemon :golpes]))))
      (is (seq (:historico-golpes col-ash)) "histórico canônico intacto na coleção")
      (is (= coins0 (long (:coins (ports/get-save s chat ash "a") 0))))
      ;; Idempotência: mesmo operation-id
      (let [dup (tx/join-raid!
                 s {:chat-id chat :player-id ash :save-id "a"
                    :fence-generation (fence s chat ash)
                    :operation-id "join-fat-ash"
                    :collection-index 0 :nome "CaioSmoke" :agora-ms 4})]
        (is (true? (:duplicate dup)))
        (is (= 2 (count (:ordem (raid-app/get-raid s rid)))))))))

(deftest join-duplicado-mesmo-jogador-sem-nova-linha
  (let [{:keys [s]} (fresh! "join-dup")
        chat "chat-dup"
        ash "ash@c.us"
        rid "raid-dup"]
    (boot-fat! s chat ash (fat-mon "Snorlax"))
    (tx/open-raid!
     s {:chat-id chat :player-id ash :save-id "a"
        :fence-generation (fence s chat ash)
        :operation-id "open-dup" :raid-id rid
        :ginasio "pedra" :agora-ms 1000})
    (tx/join-raid!
     s {:chat-id chat :player-id ash :save-id "a"
        :fence-generation (fence s chat ash)
        :operation-id "j1" :collection-index 0 :agora-ms 2})
    (let [again (tx/join-raid!
                 s {:chat-id chat :player-id ash :save-id "a"
                    :fence-generation (fence s chat ash)
                    :operation-id "j2" :collection-index 0 :agora-ms 3})]
      (is (true? (:duplicate again)))
      (is (= 1 (count (:ordem (raid-app/get-raid s rid))))))))

(deftest join-apos-reinicio-preserva-snapshot-slim
  (let [{:keys [s]} (fresh! "join-restart")
        chat "chat-restart"
        ash "ash@lid"
        rid "raid-restart"]
    (boot-fat! s chat ash (fat-mon "Machop"))
    (tx/open-raid!
     s {:chat-id chat :player-id ash :save-id "a"
        :fence-generation (fence s chat ash)
        :operation-id "open-r" :raid-id rid
        :ginasio "pedra" :agora-ms 1000})
    (tx/join-raid!
     s {:chat-id chat :player-id ash :save-id "a"
        :fence-generation (fence s chat ash)
        :operation-id "join-r" :collection-index 0 :agora-ms 2})
    (let [s2 (reopen! "join-restart")
          raid (raid-app/get-raid s2 rid)
          poke (get-in raid [:participantes ash :pokemon])]
      (is (= "inscricoes" (:fase raid)))
      (is (nil? (:historico-golpes poke)))
      (is (= 4 (count (:golpes poke))))
      (is (seq (:historico-golpes (first (ports/list-collection s2 chat ash "a"))))))))

(deftest join-em-raide-expirada-rejeita
  (let [{:keys [s]} (fresh! "join-exp")
        chat "chat-exp"
        ash "ash@c.us"
        rid "raid-exp"]
    (boot-fat! s chat ash (fat-mon "Budew"))
    (tx/open-raid!
     s {:chat-id chat :player-id ash :save-id "a"
        :fence-generation (fence s chat ash)
        :operation-id "open-e" :raid-id rid
        :ginasio "pedra" :agora-ms 1000})
    (is (= :raid_not_open
           (try
             (tx/join-raid!
              s {:chat-id chat :player-id ash :save-id "a"
                 :fence-generation (fence s chat ash)
                 :operation-id "join-late"
                 :collection-index 0
                 :agora-ms (+ 1000 raid/duracao-inscricoes 1)})
             (catch Exception ex (err/error-code ex)))))))

(deftest dois-joins-concorrentes-mesmo-jogador
  (let [{:keys [s]} (fresh! "join-race")
        chat "chat-race"
        ash "ash@c.us"
        rid "raid-race"]
    (boot-fat! s chat ash (fat-mon "Tinkatuff"))
    (tx/open-raid!
     s {:chat-id chat :player-id ash :save-id "a"
        :fence-generation (fence s chat ash)
        :operation-id "open-race" :raid-id rid
        :ginasio "pedra" :agora-ms 1000})
    (let [f (fence s chat ash)
          results
          (mapv
           (fn [i]
             (try
               (tx/join-raid!
                s {:chat-id chat :player-id ash :save-id "a"
                   :fence-generation f
                   :operation-id (str "race-join-" i)
                   :collection-index 0 :agora-ms (+ 10 i)})
               {:ok true}
               (catch Exception ex
                 {:ok false :code (err/error-code ex)})))
           [0 1])
          oks (filter :ok results)]
      ;; Um commit "novo"; o segundo pode ser duplicate lógico (already in raid)
      ;; ou sucesso duplicate=true via already? no mesmo TX path.
      (is (>= (count oks) 1))
      (is (= 1 (count (:ordem (raid-app/get-raid s rid))))))))

(deftest join-timestamps-grandes-ok
  (let [{:keys [s]} (fresh! "join-ts")
        chat "chat-ts"
        ash "ash@c.us"
        rid "raid-ts"
        agora 1730000000000]
    (boot-fat! s chat ash (fat-mon "Voltorb"))
    (tx/open-raid!
     s {:chat-id chat :player-id ash :save-id "a"
        :fence-generation (fence s chat ash)
        :operation-id "open-ts" :raid-id rid
        :ginasio "pedra" :agora-ms agora})
    (let [j (tx/join-raid!
             s {:chat-id chat :player-id ash :save-id "a"
                :fence-generation (fence s chat ash)
                :operation-id "join-ts"
                :collection-index 0 :agora-ms (inc agora)})]
      (is (false? (:duplicate j)))
      (is (= "inscricoes" (:fase (raid-app/get-raid s rid)))))))
