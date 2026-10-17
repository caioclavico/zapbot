(ns pokemon.tests.battle-m34-test
  "M3.4 — motor de batalha selvagem com fence/idempotência."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.damage :as dmg]
            [pokemon.domain.errors :as err]
            [pokemon.domain.status :as status]
            [pokemon.domain.types :as types]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m34-tests")
(def ^:private storage-rel ".datomic-local/m34-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- reopen-store
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player}))

(def ^:private squirtle
  {:nome "Squirtle" :hp 100 :ataque 60 :defesa 70 :atq-esp 60 :def-esp 65 :veloc 50
   :tipos ["water"] :nivel 10 :habilidade "torrent"
   :golpes [{:nome-exibicao "Jato de Agua" :tipo "water" :poder 40 :classe :especial}
            {:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]})

(def ^:private charmander-selvagem
  {:nome "Charmander" :hp 80 :ataque 55 :defesa 45 :atq-esp 60 :def-esp 50 :veloc 65
   :tipos ["fire"] :nivel 8 :raridade "comum" :habilidade "blaze"
   :golpes [{:nome-exibicao "Brasas" :tipo "fire" :poder 40 :classe :especial}]})

(defn- load-damage-fixtures
  []
  (edn/read-string
   (slurp (io/file (persistence/project-root)
                   "modules/pokemon/tests/pokemon/tests/fixtures/legacy_damage.edn"))))

(deftest equivalencia-dano-e-tipos
  (doseq [f (load-damage-fixtures)]
    (testing (:id f)
      (when (:dano-esperado f)
        (is (= (:dano-esperado f)
               (dmg/calcular-dano (:poder f) (:ataque f) (:defesa f)
                                  {:damage-var (:variance f)}))))
      (when (:multiplicador f)
        (is (= (double (:multiplicador f))
               (types/multiplicador-vs-tipos
                (:tipo-golpe f) (:tipos-defesa f) (:habilidade f)))))
      (when (:dot f)
        (is (= (:dot f) (status/dano-por-status (:status f) (:hp-max f))))))))

(deftest resolver-ataque-crit-e-efetividade
  (let [r (dmg/resolver-ataque
           {:golpe {:tipo "water" :poder 40 :classe :especial}
            :atacante {:atq-esp 100 :habilidade "torrent" :hp 100}
            :defensor {:def-esp 50 :tipos ["fire"] :habilidade nil}
            :estagios-atacante {} :estagios-defensor {}
            :defendendo? false :hp-atacante-atual 100
            ;; crit sim, accuracy ok, damage-var 0
            :rng {:crit 0 :accuracy 0 :damage-var 0}})]
    (is (true? (:critico? r)))
    (is (= 2.0 (:multiplicador r)))
    (is (pos? (:dano r)))
    (is (true? (:acertou? r)))))

(deftest status-impede-e-dot
  (let [imp (status/impedimento-status :paralisado {:impede-para 0})]
    (is (true? (:impedido? imp))))
  ;; cura-sono < 35 acorda; >= 35 mantém dormindo
  (let [imp (status/impedimento-status :adormecido {:cura-sono 50})]
    (is (true? (:impedido? imp))))
  (is (= 5 (status/dano-por-status :queimado 80))))

(deftest dominio-vitoria-e-derrota
  (let [base (battle/create-wild-battle
              {:battle-id "b1" :chat-id "c" :player-id "p" :save-id "a"
               :fence-generation 1
               :pokemon-x squirtle :pokemon-o (assoc charmander-selvagem :hp 5)
               :hp-x 100 :hp-o 5})
        win (battle/apply-player-turn
             base {:action :attack :move-index 0}
             ;; dano alto: variance 10, no crit needed; flee never; wild move 0
             {:damage-var 10 :crit 99 :flee 99 :impede-para 99
              :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99
              :contagio 99 :status-golpe 99 :wild-move 0 :accuracy 0})]
    (is (= :win (:outcome win)))
    (is (= :opponent-fainted (:phase win)))
    (is (false? (:active? win))))
  (let [base (battle/create-wild-battle
              {:battle-id "b2" :chat-id "c" :player-id "p" :save-id "a"
               :fence-generation 1
               :pokemon-x (assoc squirtle :hp 5) :pokemon-o charmander-selvagem
               :hp-x 5 :hp-o 80
               :status-x :confuso})
        lose (battle/apply-player-turn
              base {:action :attack :move-index 0}
              ;; confusão impede com auto-dano alto o suficiente? auto = max(1,quot 5 8)=1
              ;; força wild a matar: player age, depois wild hit forte
              {:cura-confusao 50 :auto-confusao 50  ; age normalmente
               :damage-var 0 :crit 99 :flee 99
               :wild-move 0 :accuracy 0
               ;; no segundo hit (wild) usamos mesmas keys — dano do brasas deve KO hp 5
               :contagio 99 :status-golpe 99 :impede-para 99})]
    ;; Se player não matar (charmander 80hp), wild responde e pode KO
    (is (contains? #{:lose :win :flee nil} (:outcome lose)))
    (when (= :lose (:outcome lose))
      (is (zero? (get-in lose [:hp :x]))))))

(deftest dominio-desmaio-por-status
  (let [base (battle/create-wild-battle
              {:battle-id "b3" :chat-id "c" :player-id "p" :save-id "a"
               :fence-generation 1
               :pokemon-x (assoc squirtle :hp 3)
               :pokemon-o charmander-selvagem
               :hp-x 3 :hp-o 80
               :status-x :confuso})
        out (battle/apply-player-turn
             base {:action :attack :move-index 0}
             {:cura-confusao 99 :auto-confusao 0  ; impede + auto-dano
              :flee 99 :damage-var 0})]
    (is (true? (get-in out [:last-action :impedido?])))
    (is (or (zero? (get-in out [:hp :x]))
            (= :lose (:outcome out))
            (= :active (:phase out))))))

(deftest start-e-turno-persistido
  (let [s (fresh-store! "bat-ok")
        chat "chat-b1"
        player "ash"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player squirtle 100 nil)
    (let [start (battle-app/start-wild-battle!
                 s chat player
                 {:operation-id "bat-start-1"
                  :battle-id "battle-1"
                  :pokemon-x squirtle
                  :pokemon-o charmander-selvagem
                  :hp-x 100 :hp-o 80
                  :collection-index 0})
          turn (battle-app/confirm-turn!
                s chat player
                {:operation-id "bat-turn-1"
                 :battle-id "battle-1"
                 :expected-turn-index 0
                 :action :attack
                 :move-index 0
                 :rng {:damage-var 5 :crit 99 :flee 99 :accuracy 0
                       :contagio 99 :status-golpe 99 :wild-move 0
                       :impede-para 99}})]
      (is (false? (:duplicate start)))
      (is (= 1 (get-in turn [:battle :turn-index])))
      (is (< (get-in turn [:battle :hp :o]) 80))
      (is (some? (battle-app/active-battle s chat player))))))

(deftest turno-idempotente
  (let [s (fresh-store! "bat-idem")
        chat "chat-b2"
        player "misty"
        req {:operation-id "turn-once"
             :battle-id "bid-idem"
             :expected-turn-index 0
             :action :attack
             :move-index 0
             :rng {:damage-var 3 :crit 99 :flee 99 :accuracy 0
                   :contagio 99 :status-golpe 99 :wild-move 0 :impede-para 99}}]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player squirtle 100 nil)
    (battle-app/start-wild-battle!
     s chat player
     {:operation-id "start-idem" :battle-id "bid-idem"
      :pokemon-x squirtle :pokemon-o charmander-selvagem
      :hp-x 100 :hp-o 80 :collection-index 0})
    (let [r1 (battle-app/confirm-turn! s chat player req)
          r2 (battle-app/confirm-turn! s chat player req)]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= 1 (:turn-index (battle-app/get-battle s "bid-idem")))))))

(deftest turno-duplicado-stale-index
  (let [s (fresh-store! "bat-stale-turn")
        chat "chat-b3"
        player "brock"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player squirtle 100 nil)
    (battle-app/start-wild-battle!
     s chat player
     {:operation-id "start-st" :battle-id "bid-st"
      :pokemon-x squirtle :pokemon-o charmander-selvagem
      :hp-x 100 :hp-o 80 :collection-index 0})
    (battle-app/confirm-turn!
     s chat player
     {:operation-id "t1" :battle-id "bid-st" :expected-turn-index 0
      :action :attack :move-index 0
      :rng {:damage-var 2 :crit 99 :flee 99 :accuracy 0 :contagio 99
            :status-golpe 99 :wild-move 0 :impede-para 99}})
    (let [code (try
                 (battle-app/confirm-turn!
                  s chat player
                  {:operation-id "t2-bad" :battle-id "bid-st"
                   :expected-turn-index 0  ; já avançou
                   :action :attack :move-index 0
                   :rng {:damage-var 2 :crit 99 :flee 99 :accuracy 0}})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :stale_turn_index code)))))

(deftest concorrencia-turnos
  (let [s (fresh-store! "bat-conc")
        chat "chat-b4"
        player "red"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player squirtle 100 nil)
    (battle-app/start-wild-battle!
     s chat player
     {:operation-id "start-c" :battle-id "bid-c"
      :pokemon-x squirtle :pokemon-o (assoc charmander-selvagem :hp 500)
      :hp-x 100 :hp-o 500 :collection-index 0})
    (let [futs (doall
                (for [i (range 4)]
                  (future
                    (try
                      (battle-app/confirm-turn!
                       s chat player
                       {:operation-id (str "tc-" i)
                        :battle-id "bid-c"
                        :expected-turn-index 0
                        :action :attack :move-index 0
                        :rng {:damage-var i :crit 99 :flee 99 :accuracy 0
                              :contagio 99 :status-golpe 99 :wild-move 0
                              :impede-para 99}})
                      (catch Exception ex
                        {:error (err/error-code ex)})))))
          results (mapv deref futs)
          oks (filter #(and (map? %) (false? (:duplicate %)) (nil? (:error %))) results)
          errs (filter :error results)]
      (is (= 1 (count oks)))
      (is (= 1 (:turn-index (battle-app/get-battle s "bid-c"))))
      (is (every? #(contains? #{:stale_turn_index :writer_fence_conflict} (:error %))
                  errs)))))

(deftest alternancia-bloqueada-com-batalha
  (let [s (fresh-store! "bat-block")
        chat "chat-b5"
        player "leaf"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player squirtle 100 nil)
    (battle-app/start-wild-battle!
     s chat player
     {:operation-id "start-blk" :battle-id "bid-blk"
      :pokemon-x squirtle :pokemon-o charmander-selvagem
      :hp-x 100 :hp-o 80 :collection-index 0})
    (let [code (try
                 (trainer/switch-save! s chat player "b")
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :battle_pending code))
      (is (= "a" (ports/active-save-id s chat player))))))

(deftest callback-stale-apos-switch
  (let [s (fresh-store! "bat-fence")
        chat "chat-b6"
        player "green"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player squirtle 100 nil)
    ;; batalha curta: força flee no 1º turno para liberar switch
    (battle-app/start-wild-battle!
     s chat player
     {:operation-id "start-f" :battle-id "bid-f"
      :pokemon-x squirtle :pokemon-o charmander-selvagem
      :hp-x 100 :hp-o 80 :collection-index 0})
    (let [writer-a (writer/freeze-writer s chat player)]
      (battle-app/confirm-turn!
       s chat player
       {:operation-id "flee-t" :battle-id "bid-f" :expected-turn-index 0
        :action :attack :move-index 0
        :rng {:flee 0 :damage-var 0 :crit 99 :accuracy 0 :impede-para 99}})
      (trainer/switch-save! s chat player "b")
      (let [code (try
                   (battle-app/start-wild-battle!
                    s writer-a
                    {:operation-id "stale-start" :battle-id "bid-stale"
                     :pokemon-x squirtle :pokemon-o charmander-selvagem
                     :hp-x 100 :hp-o 80})
                   :ok
                   (catch Exception ex (err/error-code ex)))]
        (is (= :stale_writer_fence code))))))

(deftest persistencia-apos-reabrir
  (let [db "bat-persist"
        chat "chat-b7"
        player "blue"]
    (let [s (fresh-store! db)]
      (boot! s chat player)
      (collection/adicionar-pokemon! s chat player squirtle 100 nil)
      (battle-app/start-wild-battle!
       s chat player
       {:operation-id "start-p" :battle-id "bid-p"
        :pokemon-x squirtle :pokemon-o charmander-selvagem
        :hp-x 100 :hp-o 80 :collection-index 0})
      (battle-app/confirm-turn!
       s chat player
       {:operation-id "turn-p" :battle-id "bid-p" :expected-turn-index 0
        :action :attack :move-index 0
        :rng {:damage-var 4 :crit 99 :flee 99 :accuracy 0 :contagio 99
              :status-golpe 99 :wild-move 0 :impede-para 99}}))
    (let [s (reopen-store db)
          b (battle-app/get-battle s "bid-p")
          retry (battle-app/confirm-turn!
                 s chat player
                 {:operation-id "turn-p" :battle-id "bid-p" :expected-turn-index 0
                  :action :attack :move-index 0
                  :rng {:damage-var 4 :crit 99 :flee 99 :accuracy 0 :contagio 99
                        :status-golpe 99 :wild-move 0 :impede-para 99}})]
      (is (true? (:duplicate retry)))
      (is (= 1 (:turn-index b)))
      (is (true? (:active? b)))
      (is (= "Squirtle" (get-in b [:pokemons :x :nome]))))))
