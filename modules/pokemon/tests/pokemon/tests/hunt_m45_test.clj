(ns pokemon.tests.hunt-m45-test
  "M4.5 — caça selvagem: plano, start, batalha, captura."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.hunt :as hunt-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.battle :as battle-domain]
            [pokemon.domain.errors :as err]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.mundo :as mundo]
            [pokemon.infrastructure.datomic.store :as store])
  (:import [java.time Instant]))

(def ^:private system "odisseu-m45-tests")
(def ^:private storage-rel ".datomic-local/m45-tests")

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

(def ^:private squirtle
  {:nome "Squirtle" :hp 100 :ataque 200 :defesa 70 :atq-esp 200 :def-esp 65 :veloc 50
   :tipos ["water"] :nivel 10 :habilidade "torrent"
   :golpes [{:nome-exibicao "Jato" :tipo "water" :poder 80 :classe :especial}]})

(def ^:private magikarp-cand
  {:nome "Magikarp" :species "magikarp" :slug-especie "magikarp"
   :raridade "comum" :hp 20 :ataque 10 :defesa 20 :atq-esp 10 :def-esp 20 :veloc 80
   :tipos ["water"] :nivel 1 :taxa-captura 255
   :golpes [{:slug "splash" :nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]})

(def ^:private geodude-cand
  {:nome "Geodude" :species "geodude" :slug-especie "geodude"
   :hp 40 :ataque 40 :defesa 50 :atq-esp 20 :def-esp 30 :veloc 20
   :tipos ["rock" "ground"] :nivel 1 :taxa-captura 255
   :golpes [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-balls-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pokebola" :qty 10}})
  (collection/adicionar-pokemon! s chat player squirtle 100 nil))

(deftest mundo-rotacao-deterministica
  (let [dia "2026-10-17"
        c1 (mundo/clima-do-dia dia)
        c2 (mundo/clima-do-dia dia)
        areas (mundo/areas-do-dia dia)
        praia (mundo/obter-area "praia")]
    (is (= (:id c1) (:id c2)))
    (is (= 3 (count areas)))
    (is (nil? (mundo/area-disponivel "area-inexistente" dia)))
    (is (= "#7dd3fc" (:ceu praia)))
    (is (= "#eab308" (:chao praia)))))

(deftest formulas-poder-e-raridade
  (is (= 320 (hunt/poder-alvo-caca 1)))
  (is (= 570 (hunt/poder-alvo-caca 11)))
  (is (= 570 (hunt/poder-alvo-caca 99)))
  (is (= "comum" (:raridade (hunt/com-raridade magikarp-cand))))
  (is (= "comum" (:raridade (hunt/com-raridade (dissoc magikarp-cand :taxa-captura))))
      "taxa-captura ausente (species falhou) não quebra raridade")
  (is (true? (:shiny? (hunt/sortear-shiny magikarp-cand 0))))
  (is (false? (:shiny? (hunt/sortear-shiny magikarp-cand 1))))
  (is (false? (:shiny? (hunt/sortear-shiny magikarp-cand nil)))
      "shiny-roll nil não lança NPE (produção)"))

(deftest cooldown-de-caca-e-configuravel-com-protecao-de-producao
  (let [at 1700000000000]
    (with-redefs [hunt/environment (fn [k]
                                     ({"APP_ENV" "production"
                                       "POKEMON_HUNT_COOLDOWN_SECONDS" "0"} k))]
      (is (= 1800 (hunt/cooldown-cacada-segundos)))
      (is (= (* 30 60 1000) (hunt/cooldown-restante-ms at at))))
    (with-redefs [hunt/environment (fn [k]
                                     ({"APP_ENV" "staging"
                                       "POKEMON_HUNT_COOLDOWN_SECONDS" "0"} k))]
      (is (zero? (hunt/cooldown-cacada-segundos)))
      (is (zero? (hunt/cooldown-restante-ms at at)))
      (is (hunt/pode-cacar? at at)))
    (with-redefs [hunt/environment (fn [k]
                                     ({"APP_ENV" "production"
                                       "POKEMON_HUNT_COOLDOWN_SECONDS" "45"} k))]
      (is (= 45 (hunt/cooldown-cacada-segundos)))
      (is (= 44000 (hunt/cooldown-restante-ms at (+ at 1000)))))
    (with-redefs [hunt/environment (fn [k]
                                     ({"APP_ENV" "staging"
                                       "POKEMON_HUNT_COOLDOWN_SECONDS" "1800"} k))]
      (is (= 1800 (hunt/cooldown-cacada-segundos)))
      (is (false? (hunt/pode-cacar? at (+ at 1000))))
      (is (hunt/pode-cacar? at (+ at (* 1800 1000)))))
    (with-redefs [hunt/environment (constantly nil)]
      (is (= 1800 (hunt/cooldown-cacada-segundos)))
      (is (= (* 30 60 1000) (hunt/cooldown-restante-ms at at))))))

(deftest selecao-respeita-bioma-e-teto
  (let [alvo 320
        tipos #{"water" "ice"}
        escolhido (hunt/selecionar-selvagem
                   [geodude-cand magikarp-cand] alvo tipos)]
    (is (= "Magikarp" (:nome escolhido)))))

(deftest start-sem-shiny-roll-nem-taxa-captura
  "Regressão INTERNAL_ERROR !pk caçar: Node não envia shinyRoll;
   species pode omitir taxa-captura."
  (let [s (fresh-store! "hunt-nil-shiny")
        chat "chat-nil"
        player "ash"
        agora 1700000300000
        cand (dissoc magikarp-cand :taxa-captura)]
    (boot! s chat player)
    (let [start (hunt-app/start-hunt!
                 s chat player
                 {:operation-id "hunt-no-shiny"
                  :area-id "praia"
                  :tipos-encontro ["water" "ice" "flying" "electric"]
                  :surto? false
                  :poder-alvo 320
                  :candidates [cand]
                  :agora-ms agora})
          b (:battle start)
          selv (:selvagem start)]
      (is (false? (:duplicate start)))
      (is (some? b))
      (is (true? (:active? b)))
      (is (= "Magikarp" (:nome selv)))
      (is (contains? #{true false} (boolean (:shiny? selv))))
      (is (= "comum" (:raridade selv))))))

(deftest candidato-incompleto-rejeitado
  (let [s (fresh-store! "hunt-bad-cand")
        chat "chat-bad"
        player "ash"]
    (boot! s chat player)
    (is (= :invalid_wild_pokemon
           (try
             (hunt-app/start-hunt!
              s chat player
              {:operation-id "hunt-bad"
               :area-id "praia"
               :tipos-encontro ["water"]
               :surto? false
               :poder-alvo 320
               :candidates [{:nome "X" :tipos ["water"] :hp 10
                             :golpes [{:nome-exibicao "A" :tipo "normal"
                                       :poder 40 :classe :fisico}]}]
               :agora-ms 1700000400000})
             :ok
             (catch Exception ex (err/error-code ex)))))))

(deftest caca-batalha-captura-e2e
  (let [s (fresh-store! "hunt-e2e")
        chat "chat-e2e"
        player "ash"
        agora 1700000000000]
    (boot! s chat player)
    (let [plano (hunt-app/plan s chat player
                               {:agora-ms agora
                                :roll-tipos 50  ; bioma (não clima)
                                :roll-surto 99}) ; sem surto
          start (hunt-app/start-hunt!
                 s chat player
                 {:operation-id "hunt-1"
                  :area-id (get-in plano [:area :id])
                  :tipos-encontro (:tipos-encontro plano)
                  :surto? false
                  :poder-alvo (:poder-alvo plano)
                  :candidates [magikarp-cand geodude-cand]
                  :agora-ms agora
                  :shiny-roll 1})
          bid (:battle-id start)
          ;; :agora alinhado ao idle do start (agora-ms) — wall-clock quebraria
          ;; battle_idle_expired com timestamps sintéticos.
          win (battle-app/confirm-turn!
               s chat player
               {:operation-id "turn-win"
                :battle-id bid
                :expected-turn-index 0
                :action :attack
                :move-index 0
                :agora agora
                :rng {:damage-var 10 :crit 99 :flee 99 :accuracy 0
                      :contagio 99 :status-golpe 99 :wild-move 0 :impede-para 99}})
          cap (capture-app/attempt-encounter-capture!
               s chat player
               {:operation-id "cap-1"
                :battle-id bid
                :bola "pokebola"
                :roll 0
                :agora agora})]
      (is (false? (:duplicate start)))
      (is (true? (:aguardando-captura? (:battle win))))
      (is (true? (:capturou? cap)))
      (is (= 2 (count (ports/list-collection s chat player "a"))))
      (is (pos? (:ultima-cacada-at (ports/get-save s chat player "a"))))
      (is (nil? (:active-wild-battle-id (ports/get-group s chat)))))))

(deftest idempotencia-e-reinicio
  (let [db "hunt-idem"
        chat "chat-idem"
        player "misty"
        agora 1700000100000
        req {:operation-id "hunt-once"
             :area-id "praia"
             :tipos-encontro ["water" "ice" "flying" "electric"]
             :surto? false
             :poder-alvo 320
             :candidates [magikarp-cand]
             :agora-ms agora
             :shiny-roll 1}]
    (let [s (fresh-store! db)]
      (boot! s chat player)
      (let [r1 (hunt-app/start-hunt! s chat player req)
            r2 (hunt-app/start-hunt! s chat player req)]
        (is (false? (:duplicate r1)))
        (is (true? (:duplicate r2)))
        (is (some? (ports/active-battle s chat player)))))
    (let [s (reopen-store db)
          retry (hunt-app/start-hunt! s chat player req)
          b (ports/active-battle s chat player)]
      (is (true? (:duplicate retry)))
      (is (some? b))
      (is (true? (:active? b))))))

(deftest a-b-bloqueado-e-stale
  (let [s (fresh-store! "hunt-ab")
        chat "chat-ab"
        player "leaf"
        agora 1700000200000]
    (boot! s chat player)
    (hunt-app/start-hunt!
     s chat player
     {:operation-id "hunt-ab"
      :area-id "praia"
      :tipos-encontro ["water"]
      :surto? false
      :poder-alvo 320
      :candidates [magikarp-cand]
      :agora-ms agora
      :shiny-roll 1})
    (is (= :battle_pending
           (try (trainer/switch-save! s chat player "b") :ok
                (catch Exception ex (err/error-code ex)))))
    (let [writer-a (writer/freeze-writer s chat player)]
      ;; defend + flee 0: não KO (Magikarp frágil); selvagem foge
      (battle-app/confirm-turn!
       s chat player
       {:operation-id "flee-ab"
        :battle-id (:battle-id (ports/active-battle s chat player))
        :expected-turn-index 0
        :action :defend
        :agora agora
        :rng {:flee 0 :damage-var 0 :crit 99 :accuracy 0 :impede-para 99
              :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99
              :contagio 99 :status-golpe 99 :wild-move 0}})
      (trainer/switch-save! s chat player "b")
      (is (= :stale_writer_fence
             (try
               (hunt-app/start-hunt!
                s writer-a
                {:operation-id "stale-hunt"
                 :area-id "praia"
                 :tipos-encontro ["water"]
                 :surto? false
                 :poder-alvo 320
                 :candidates [magikarp-cand]
                 :agora-ms (+ agora hunt/cooldown-cacada-ms 1)
                 :shiny-roll 1})
               :ok
               (catch Exception ex (err/error-code ex))))))))

(def ^:private rng-safe
  {:damage-var 0 :crit 99 :flee 99 :accuracy 0
   :contagio 99 :status-golpe 99 :wild-move 0 :impede-para 99
   :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99})

(deftest defender-e-pocao-mid-caca-a1
  "A1: !pk defender e !pk pocao durante caçada (turno + inventário)."
  (let [s (fresh-store! "hunt-a1")
        chat "chat-a1"
        player "ash"
        agora 1700000500000]
    (boot! s chat player)
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-pocao-a1"
        :chat-id chat :player-id player :save-id "a"
        :item {:item-id "pocao" :qty 2}})
    (let [start (hunt-app/start-hunt!
                 s chat player
                 {:operation-id "hunt-a1"
                  :area-id "praia"
                  :tipos-encontro ["water"]
                  :surto? false
                  :poder-alvo 320
                  :candidates [geodude-cand]
                  :agora-ms agora
                  :shiny-roll 1})
          bid (:battle-id start)
          def1 (battle-app/confirm-turn!
                s chat player
                {:operation-id "def-1"
                 :battle-id bid
                 :expected-turn-index 0
                 :action :defend
                 :agora agora
                 :rng rng-safe})
          def2 (battle-app/confirm-turn!
                s chat player
                {:operation-id "def-1"
                 :battle-id bid
                 :expected-turn-index 0
                 :action :defend
                 :agora agora
                 :rng rng-safe})
          pot (battle-app/confirm-turn!
               s chat player
               {:operation-id "pot-1"
                :battle-id bid
                :expected-turn-index 1
                :action :item
                :item-id "pocao"
                :agora agora
                :rng rng-safe})
          pot2 (battle-app/confirm-turn!
                s chat player
                {:operation-id "pot-1"
                 :battle-id bid
                 :expected-turn-index 1
                 :action :item
                 :item-id "pocao"
                 :agora agora
                 :rng rng-safe})
          la (:last-action (:battle pot))
          inv (ports/list-inventory s chat player "a")
          qty-pocao (:qty (first (filter #(= "pocao" (:item-id %)) inv)) 0)]
      (is (= :defend (get-in def1 [:battle :last-action :action])))
      (is (true? (:active? (:battle def1))))
      (is (true? (:duplicate def2)))
      (is (= :item (:action la)))
      (is (= "pocao" (:item-id la)))
      (is (pos? (- (long (:hp-depois la 0)) (long (:hp-antes la 0))))
          "cura da poção registrada no last-action (antes do revide)")
      (is (true? (:duplicate pot2)))
      (is (= 1 qty-pocao) "uma poção consumida; retry idempotente não consome de novo"))))

(deftest isolamento-chat-e-cooldown
  (let [s (fresh-store! "hunt-iso")
        chat "chat-iso"
        agora 1700000300000]
    (boot! s chat "ash")
    (boot! s chat "gary")
    (hunt-app/start-hunt!
     s chat "ash"
     {:operation-id "ash-hunt"
      :area-id "praia"
      :tipos-encontro ["water"]
      :surto? false
      :poder-alvo 320
      :candidates [magikarp-cand]
      :agora-ms agora
      :shiny-roll 1})
    (is (= :chat_hunt_busy
           (try
             (hunt-app/start-hunt!
              s chat "gary"
              {:operation-id "gary-hunt"
               :area-id "praia"
               :tipos-encontro ["water"]
               :surto? false
               :poder-alvo 320
               :candidates [magikarp-cand]
               :agora-ms agora
               :shiny-roll 1})
             :ok
             (catch Exception ex (err/error-code ex)))))
    (is (= :hunt_cooldown
           (try
             (hunt-app/start-hunt!
              s chat "ash"
              {:operation-id "ash-again"
               :area-id "praia"
               :tipos-encontro ["water"]
               :surto? false
               :poder-alvo 320
               :candidates [magikarp-cand]
               :agora-ms (+ agora 1000)
               :shiny-roll 1})
             :ok
             (catch Exception ex (err/error-code ex)))))))

(deftest cooldown-zero-permite-nova-caca-apos-batalha-encerrada
  (with-redefs [hunt/environment (fn [k]
                                   ({"APP_ENV" "staging"
                                     "POKEMON_HUNT_COOLDOWN_SECONDS" "0"} k))]
    (let [s (fresh-store! "hunt-zero-cooldown")
          chat "chat-zero"
          player "ash"
          agora 1700000400000
          request (fn [operation-id]
                    {:operation-id operation-id :area-id "praia"
                     :tipos-encontro ["water"] :surto? false :poder-alvo 320
                     :candidates [magikarp-cand] :agora-ms agora :shiny-roll 1})]
      (boot! s chat player)
      (let [first-hunt (hunt-app/start-hunt! s chat player (request "hunt-zero-1"))]
        ;; Mesmo com cooldown zero, uma batalha ainda ativa bloqueia concorrência.
        (is (= :battle_already_active
               (try (hunt-app/start-hunt! s chat player (request "hunt-zero-active"))
                    :ok (catch Exception ex (err/error-code ex)))))
        ;; A fuga encerra a batalha sem captura e libera a trava canônica do chat.
        (battle-app/confirm-turn!
         s chat player
         {:operation-id "hunt-zero-flee" :battle-id (:battle-id first-hunt)
          :expected-turn-index 0 :action :defend
          :agora agora
          :rng {:flee 0 :damage-var 0 :crit 99 :accuracy 0 :impede-para 99
                :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99
                :contagio 99 :status-golpe 99 :wild-move 0}})
        (is (nil? (:active-wild-battle-id (ports/get-group s chat))))
        (is (= "hunt-zero-2"
               (:operation-id (hunt-app/start-hunt! s chat player (request "hunt-zero-2")))))))))

(deftest cooldown-zero-nao-retorna-apos-derrota
  (with-redefs [hunt/environment (fn [k]
                                   ({"APP_ENV" "staging"
                                     "POKEMON_HUNT_COOLDOWN_SECONDS" "0"} k))]
    (let [s (fresh-store! "hunt-zero-defeat")
          chat "chat-zero-defeat"
          player "ash"
          agora 1700000500000
          ;; HP artificial de cenário — não aplicar species-hp-v1 do catálogo.
          hostile (assoc magikarp-cand :hp 500 :ataque 999 :atq-esp 999
                          :defesa 999 :def-esp 999
                          :species-hp-skip? true
                          :golpes [{:slug "crush" :nome-exibicao "Crush"
                                    :tipo "normal" :poder 500 :classe :fisico}])]
      (boot! s chat player)
      (let [started (hunt-app/start-hunt!
                     s chat player
                     {:operation-id "hunt-zero-defeat-1" :area-id "praia"
                      :tipos-encontro ["water"] :surto? false :poder-alvo 320
                      :candidates [hostile] :agora-ms agora :shiny-roll 1})
            ended (battle-app/confirm-turn!
                   s chat player
                   {:operation-id "hunt-zero-defeat-turn" :battle-id (:battle-id started)
                    :expected-turn-index 0 :action :defend
                    :agora agora
                    :rng {:flee 99 :damage-var 10 :crit 0 :accuracy 0 :impede-para 99
                          :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99
                          :contagio 99 :status-golpe 99 :wild-move 0}})]
        (is (= :lose (get-in ended [:battle :outcome])))
        (is (nil? (:active-wild-battle-id (ports/get-group s chat))))
        (is (true? (get-in (hunt-app/plan s chat player {:agora-ms agora})
                           [:eligibilidade :cooldown-ok])))))))

(deftest vitoria-somente-batalha-encerra-sem-ficar-presa
  "Bolsa cheia: vitória libera o selvagem e limpa active-battle (sem !pk sair)."
  (let [s (fresh-store! "hunt-full-bag-win")
        chat "chat-full-bag"
        player "ash"
        agora 1700000700000
        frail (assoc magikarp-cand :hp 1 :defesa 1 :def-esp 1
                     :species-hp-skip? true)]
    (boot! s chat player)
    (dotimes [i 49]
      (collection/adicionar-pokemon!
       s chat player
       (assoc squirtle :nome (str "Filler" i) :id-pokemon (str "filler-" i))
       100 nil))
    (is (= 50 (count (ports/list-collection s chat player "a"))))
    (let [started (hunt-app/start-hunt!
                   s chat player
                   {:operation-id "hunt-full-1" :area-id "praia"
                    :tipos-encontro ["water"] :surto? false :poder-alvo 320
                    :candidates [frail] :agora-ms agora :shiny-roll 1})
          _ (is (true? (:somente-batalha? (:battle started))))
          ended (battle-app/confirm-turn!
                 s chat player
                 {:operation-id "hunt-full-win" :battle-id (:battle-id started)
                  :expected-turn-index 0 :action :attack :move-index 0
                  :agora agora
                  :rng {:flee 99 :damage-var 10 :crit 0 :accuracy 0 :impede-para 99
                        :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99
                        :contagio 99 :status-golpe 99 :wild-move 0}})
          b (:battle ended)]
      (is (= :win (:outcome b)))
      (is (false? (:aguardando-captura? b)))
      (is (true? (:selvagem-liberado-bolsa-cheia? b)))
      (is (nil? (ports/active-battle s chat player)) "ponteiro limpo sem sair")
      (is (nil? (:active-wild-battle-id (ports/get-group s chat))))
      (is (= 50 (count (ports/list-collection s chat player "a")))
          "sem captura com bolsa cheia")
      ;; Master: comum → 2+1 XP; 0 moedas; sequência intacta.
      (let [ativo (first (ports/list-collection s chat player "a"))
            save (ports/get-save s chat player "a")]
        (is (= 3 (long (:xp-desde-nivel ativo 0))))
        (is (= 0 (long (:coins save 0))))
        (is (= 0 (long (:sequencia-capturas save 0))))))))

(deftest caca-apos-troca-save-e-fence-elevado
  "Simula o fluxo Node: plano congela fence → outra TX eleva fence (ex. !pk ativar)
  durante o gap do pokeapi → start com fence do plano falha; start com fence fresco ok.
  Também cobre recovery-v1 ativo com fence elevado."
  (let [s (fresh-store! "hunt-fence-toc")
        chat "chat-fence-toc"
        player "ash"
        agora 1700000600000
        cand magikarp-cand]
    (boot! s chat player)
    (ports/create-save! s chat player "recovery-v1")
    (trainer/switch-save! s chat player "recovery-v1")
    (collection/adicionar-pokemon! s chat player squirtle 100 nil)
    (let [fence-plano (:fence-generation (ports/get-player s chat player))
          _ (is (pos? fence-plano))
          ;; Gap pokeapi: outra ação eleva o fence (volta pra a e de novo recovery).
          _ (trainer/switch-save! s chat player "a")
          _ (trainer/switch-save! s chat player "recovery-v1")
          fence-fresco (:fence-generation (ports/get-player s chat player))
          stale {:chat-id chat :player-id player :save-id "recovery-v1"
                 :fence-generation fence-plano}
          fresh {:chat-id chat :player-id player :save-id "recovery-v1"
                 :fence-generation fence-fresco}
          req (fn [op]
                {:operation-id op :area-id "praia" :tipos-encontro ["water"]
                 :surto? false :poder-alvo 320 :candidates [cand]
                 :agora-ms agora :shiny-roll 1})]
      (is (> fence-fresco fence-plano))
      (is (= :stale_writer_fence
             (try (hunt-app/start-hunt! s stale (req "hunt-stale-plan"))
                  :ok
                  (catch Exception ex (err/error-code ex)))))
      (let [ok (hunt-app/start-hunt! s fresh (req "hunt-fresh-fence"))]
        (is (false? (:duplicate ok)))
        (is (some? (ports/active-battle s chat player)))
        (is (= 1 (count (ports/list-collection s chat player "recovery-v1"))))
        (is (= fence-fresco
               (:fence-generation (ports/get-player s chat player))))))))

(deftest sweeper-idle-nao-eleva-fence-durante-caca
  "Expire-idle limpa batalha sem bump de fence; caça subsequente com mesmo fence ok."
  (let [s (fresh-store! "hunt-idle-fence")
        chat "chat-idle-fence"
        player "ash"
        t0 1700000700000]
    (boot! s chat player)
    (let [start (hunt-app/start-hunt!
                 s chat player
                 {:operation-id "hunt-idle-1" :area-id "praia"
                  :tipos-encontro ["water"] :surto? false :poder-alvo 320
                  :candidates [magikarp-cand] :agora-ms t0 :shiny-roll 1})
          fence-antes (:fence-generation (ports/get-player s chat player))
          bid (:battle-id start)
          ;; Força expires-at no passado via expire sweeper com agora futuro.
          sweep (ports/expire-idle-battles! s {:agora (+ t0 300000) :limit 8})
          fence-depois (:fence-generation (ports/get-player s chat player))]
      (is (= 1 (:expired-battles sweep)))
      (is (= fence-antes fence-depois))
      (is (nil? (ports/active-battle s chat player)))
      (let [again (hunt-app/start-hunt!
                   s {:chat-id chat :player-id player :save-id "a"
                      :fence-generation fence-depois}
                   {:operation-id "hunt-idle-2" :area-id "praia"
                    :tipos-encontro ["water"] :surto? false :poder-alvo 320
                    :candidates [magikarp-cand]
                    :agora-ms (+ t0 hunt/cooldown-cacada-ms 1)
                    :shiny-roll 1})]
        (is (false? (:duplicate again)))
        (is (some? bid))
        (is (some? (ports/active-battle s chat player)))))))

(deftest classifica-stale-writer-fence-vs-writer-fence-conflict
  "stale = fence/save do writer divergiu; conflict = CAS esgotado com fence ainda válido."
  (testing "fence obsoleto → :stale_writer_fence (pré-check, sem esgotar retries)"
    (let [s (fresh-store! "hunt-fence-class-stale")
          chat "chat-fcs"
          player "ash"
          agora 1700000800000]
      (boot! s chat player)
      ;; boot já cria saves a/b
      (let [w0 (writer/freeze-writer s chat player)
            _ (trainer/switch-save! s chat player "b")
            code (try
                   (hunt-app/start-hunt!
                    s w0
                    {:operation-id "stale-class-1" :area-id "praia"
                     :tipos-encontro ["water"] :surto? false :poder-alvo 320
                     :candidates [magikarp-cand] :agora-ms agora :shiny-roll 1})
                   :ok
                   (catch Exception ex (err/error-code ex)))]
        (is (= :stale_writer_fence code)))))

  (testing "CAS anômalo persistente com fence intacto → :writer_fence_conflict"
    (let [s (fresh-store! "hunt-fence-class-conflict")
          chat "chat-fcc"
          player "ash"
          agora 1700000900000
          conflict (ex-info "cas conflict"
                            {:cognitect.anomalies/category
                             :cognitect.anomalies/conflict})
          attempts (atom 0)]
      (boot! s chat player)
      (let [w (writer/freeze-writer s chat player)
            code (with-redefs [d/transact (fn [& _]
                                           (swap! attempts inc)
                                           (throw conflict))]
                   (try
                     (hunt-app/start-hunt!
                      s w
                      {:operation-id "conflict-class-1" :area-id "praia"
                       :tipos-encontro ["water"] :surto? false :poder-alvo 320
                       :candidates [magikarp-cand] :agora-ms agora :shiny-roll 1})
                     :ok
                     (catch Exception ex (err/error-code ex))))]
        (is (= :writer_fence_conflict code))
        ;; attempt 0..11 retry + 1 final = 12; + pre-check fence intacto.
        (is (>= @attempts 12))
        (is (= (:fence-generation w)
               (:fence-generation (ports/get-player s chat player)))))))

  (testing "Item too large (Datomic Local) NÃO é :writer_fence_conflict"
    (let [s (fresh-store! "hunt-fence-class-too-large")
          chat "chat-ftl"
          player "ash"
          agora 1700000950000
          attempts (atom 0)]
      (boot! s chat player)
      (let [w (writer/freeze-writer s chat player)
            code (with-redefs [d/transact (fn [& _]
                                           (swap! attempts inc)
                                           (throw (ex-info "Item too large" {})))]
                   (try
                     (hunt-app/start-hunt!
                      s w
                      {:operation-id "too-large-class-1" :area-id "praia"
                       :tipos-encontro ["water"] :surto? false :poder-alvo 320
                       :candidates [magikarp-cand] :agora-ms agora :shiny-roll 1})
                     :ok
                     (catch Exception ex (err/error-code ex))))]
        (is (= :datomic_value_too_large code))
        (is (= 1 @attempts) "sem retries de CAS — pré-condição permanente")))))

(defn- fat-historico-golpes
  "Histórico ~1.8 KiB — cabe em :pokemon/historico-golpes-edn, mas estoura
   battle/state-edn quando somado a golpes/imagens/metadata (caso prod ~4351 B)."
  []
  (into {}
        (for [i (range 18)]
          [(keyword (str "g" i))
           {:slug (str "move-hist-" i)
            :nome (str "GolpeHist" i)
            :tipo "normal"
            :poder (+ 10 i)
            :detalhe (apply str (repeat 70 "x"))}])))

(defn- fat-party-pokemon
  []
  (assoc squirtle
         :historico-golpes (fat-historico-golpes)
         :golpes-oferecidos [{:slug "offer" :pad (apply str (repeat 40 "o"))}]
         :golpes-removidos [{:slug "rem" :pad (apply str (repeat 40 "r"))}]
         :imagem (apply str (repeat 104 "I"))
         :imagem-shiny (apply str (repeat 110 "S"))
         :golpes (mapv (fn [i]
                         {:slug (str "atk-" i)
                          :nome-exibicao (str "AtaqueNomeLongoParaBatalha" i)
                          :tipo "water" :poder 80 :classe :especial
                          :descricao (apply str (repeat 120 "d"))
                          :efeitos (mapv (fn [j] {:t j :pad (apply str (repeat 20 "e"))})
                                         (range 3))})
                       (range 4))))

(deftest battle-state-edn-slim-evita-item-too-large
  "Regressão do falso WRITER_FENCE_CONFLICT em !pk caçar com ativo recovery-v1 gordo."
  (testing "pr-str cru do estado com historico excede limite; encode-state-edn cabe"
    (let [fat (fat-party-pokemon)
          ;; create-wild já slimma — re-injeta historico para simular o bug pré-patch
          raw-state {:battle-id "b-slim" :kind :wild :save-id "recovery-v1"
                     :fence-generation 2 :collection-index 0
                     :pokemons {:x fat :o magikarp-cand}
                     :hp {:x 100 :o 20} :status {:x nil :o nil}
                     :bioma "floresta" :clima "neve" :surto? false
                     :somente-batalha? true :turn-index 0 :phase :active :active? true
                     :last-activity-at 1 :idle-expires-at 2}
          raw-bytes (count (pr-str raw-state))
          encoded (battle-domain/encode-state-edn raw-state)
          decoded (battle-domain/decode-compact-edn encoded)
          x (get-in decoded [:pokemons :x])]
      (is (> raw-bytes 4000) (str "fixture deve exceder limite; got " raw-bytes))
      (is (<= (count encoded) 4000) (str "encoded=" (count encoded)))
      (is (nil? (:historico-golpes x)))
      (is (some? (:imagem x)) "imagem permanece para apresentação")
      (is (seq (:golpes x)) "golpes de combate preservados")
      (is (= "torrent" (:habilidade x)))
      (doseq [k [:ataque :defesa :atq-esp :def-esp :veloc :hp :tipos :nivel]]
        (is (some? (get x k)) (str "stat combate " k)))))

  (testing "pior caso: historico ~3.5KiB (cabe na coleção); slim do state ainda cabe"
    (let [huge-hist (loop [n 20]
                      (let [h (into {}
                                    (for [i (range n)]
                                      [(keyword (str "h" i))
                                       {:slug (str "mh-" i) :nome (str "NomeHist" i)
                                        :pad (apply str (repeat 70 "Z"))}]))
                            b (count (pr-str h))]
                        (cond
                          (> b 3900) (recur (dec n))
                          (< b 3000) (recur (inc n))
                          :else h)))
          hist-bytes (count (pr-str huge-hist))
          _ (is (< hist-bytes 4000) (str "historico isolado cabe no attr; got " hist-bytes))
          _ (is (> hist-bytes 2800) (str "fixture deve ser gordo; got " hist-bytes))
          fat (assoc (fat-party-pokemon) :historico-golpes huge-hist
                     :item "restos" :taxa-captura 45 :shiny true)
          legacy-battle {:battle-id "legacy-fat" :kind :wild
                         :pokemons {:x fat :o (assoc magikarp-cand :taxa-captura 255)}
                         :hp {:x 80 :o 20} :status {:x nil :o nil}
                         :turn-index 3 :phase :active :active? true
                         :estagios {:x {:ataque 1} :o {}}
                         :itens-usados {:x {} :o {}}
                         :bioma "vulcao" :clima "neve"}
          raw (count (pr-str legacy-battle))
          encoded (battle-domain/encode-state-edn legacy-battle)
          decoded (battle-domain/decode-compact-edn encoded)
          x (get-in decoded [:pokemons :x])]
      (is (> raw 4000) (str "legado gordo raw=" raw))
      (is (<= (count encoded) 4000) (str "encoded=" (count encoded)))
      (is (nil? (:historico-golpes x)))
      (is (= "restos" (:item x)) "item de combate preservado")
      (is (= 45 (:taxa-captura x)) "taxa-captura preservada")
      (is (true? (:shiny x)))
      (is (= 1 (get-in decoded [:estagios :x :ataque]))
          "estágios de batalha intactos — compat com state legado")))

  (testing "start-hunt! recovery-v1 fence≥2 + ativo com historico gordo persiste"
    (let [s (fresh-store! "hunt-fat-state-edn")
          chat "chat-fat"
          player "ash"
          agora 1700001000000]
      (boot! s chat player)
      (ports/create-save! s chat player "recovery-v1")
      (trainer/switch-save! s chat player "recovery-v1")
      (collection/adicionar-pokemon! s chat player (fat-party-pokemon) 100 nil)
      ;; Eleva fence como no activate de produção (recovery fence=2).
      (trainer/switch-save! s chat player "a")
      (trainer/switch-save! s chat player "recovery-v1")
      (let [col (ports/list-collection s chat player "recovery-v1")
            _ (is (= 1 (count col)))
            _ (is (> (count (pr-str (:historico-golpes (first col)))) 1500))
            w (writer/freeze-writer s chat player)
            _ (is (= "recovery-v1" (:save-id w)))
            _ (is (>= (long (:fence-generation w)) 2))
            save0 (ports/get-save s chat player "recovery-v1")
            rev0 (long (:revision save0 0))
            result (hunt-app/start-hunt!
                    s w
                    {:operation-id "fat-hunt-1" :area-id "praia"
                     :tipos-encontro ["water"] :surto? false :poder-alvo 320
                     :candidates [magikarp-cand] :agora-ms agora :shiny-roll 1})
            battle (ports/active-battle s chat player)
            save1 (ports/get-save s chat player "recovery-v1")
            ;; Coleção intacta (historico permanece no Pokémon, não no state-edn)
            col1 (ports/list-collection s chat player "recovery-v1")]
        (is (some? (:battle-id result)))
        (is (some? battle))
        (is (nil? (get-in battle [:pokemons :x :historico-golpes])))
        (is (seq (:historico-golpes (first col1))) "coleção não foi alterada")
        (is (= (inc rev0) (long (:revision save1 0))) "revision CAS avançou — tx completa")
        (is (= agora (long (:ultima-cacada-at save1 0))) "cooldown só após sucesso")
        (is (<= (count (battle-domain/encode-state-edn battle)) 4000))))))
