(ns pokemon.tests.explore-a41-test
  "A4.1 — paridade áreas/clima/biomas/encontros com a master."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.hunt :as hunt-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.mundo :as mundo]
            [pokemon.infrastructure.datomic.store :as store])
  (:import [java.time Instant]))

(def ^:private system "odisseu-a41-explore-tests")
(def ^:private storage-rel ".datomic-local/a41-explore-tests")

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
   :tipos ["water"] :nivel 10
   :golpes [{:nome-exibicao "Jato" :tipo "water" :poder 80 :classe :especial}]})

(def ^:private magikarp
  {:nome "Magikarp" :species "magikarp" :slug-especie "magikarp"
   :hp 20 :ataque 10 :defesa 20 :atq-esp 10 :def-esp 20 :veloc 80
   :tipos ["water"] :nivel 1 :taxa-captura 255
   :golpes [{:nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]})

(def ^:private geodude
  {:nome "Geodude" :species "geodude"
   :hp 40 :ataque 40 :defesa 50 :atq-esp 20 :def-esp 30 :veloc 20
   :tipos ["rock" "ground"] :nivel 1 :taxa-captura 255
   :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pokebola" :qty 5}})
  (collection/adicionar-pokemon! s chat player squirtle 100 nil))

(deftest catalogo-areas-e-climas-igual-master
  (is (= 6 (count mundo/areas)))
  (is (= 6 (count mundo/climas)))
  (is (= #{"grass" "bug" "flying" "normal"}
         (:tipos (mundo/obter-area "floresta"))))
  (is (= #{"fire" "grass" "ground"}
         (:tipos (mundo/obter-clima "ensolarado"))))
  (is (= "praia" (:id (mundo/obter-area "Praia Azul"))))
  (is (= "vulcao" (:id (mundo/obter-area "vulcão")))))

(deftest rotacao-diaria-e-area-bloqueada
  (let [dia "2026-10-17"
        disponiveis (mundo/areas-do-dia dia)
        ids (set (map :id disponiveis))
        indisponiveis (mundo/areas-indisponiveis dia)]
    (is (= 3 (count disponiveis)))
    (is (= 3 (count indisponiveis)))
    (is (every? #(contains? ids (:id %)) disponiveis))
    (is (every? #(not (contains? ids (:id %))) indisponiveis))
    (doseq [a disponiveis]
      (is (some? (mundo/area-disponivel (:id a) dia))))
    (when-let [bloqueada (first indisponiveis)]
      (is (nil? (mundo/area-disponivel (:id bloqueada) dia)))))
  (let [texto (mundo/texto-resumo (Instant/parse "2026-10-17T15:00:00Z"))]
    (is (re-find #"Clima de hoje" texto))
    (is (re-find #"Bioma deste horário" texto))
    (is (re-find #"Rotação de hoje" texto))
    (is (re-find #"Sem acesso por horário hoje" texto))))

(deftest clima-vs-bioma-35-porcento
  "Limiar master: roll < 35 → clima. Em 0..99 isso são exatamente 35 hits."
  (let [bioma (mundo/obter-area "praia")
        clima (mundo/obter-clima "ensolarado")
        climate-hits
        (count (filter #(= (hunt/resolver-tipos-encontro bioma clima %)
                           (:tipos clima))
                       (range 100)))
        ;; Amostra Monte Carlo com RNG local (tolerância ±4pp em 5k).
        mc 5000
        mc-hits (count (filter (fn [_]
                                 (= (hunt/resolver-tipos-encontro
                                     bioma clima (rand-int 100))
                                    (:tipos clima)))
                               (range mc)))
        rate (/ (double mc-hits) mc)]
    (is (= 35 climate-hits))
    (is (< 0.31 rate 0.39) (str "rate clima=" rate))))

(deftest surto-50-porcento
  (is (= 50 (count (filter hunt/surto? (range 100)))))
  (let [mc 5000
        hits (count (filter (fn [_] (hunt/surto? (rand-int 100))) (range mc)))
        rate (/ (double hits) mc)]
    (is (< 0.46 rate 0.54) (str "rate surto=" rate))))

(deftest shiny-1-em-512
  (is (= 512 hunt/shiny-chance))
  (is (true? (:shiny? (hunt/sortear-shiny magikarp 0))))
  (is (false? (:shiny? (hunt/sortear-shiny magikarp 1))))
  (is (false? (:shiny? (hunt/sortear-shiny magikarp 511))))
  (is (= 1 (count (filter #(:shiny? (hunt/sortear-shiny magikarp %))
                          (range 512))))))

(deftest poder-alvo-e-teto-master
  (is (= 320 (hunt/poder-alvo-caca 1)))
  (is (= (+ 320 (* 25 10)) (hunt/poder-alvo-caca 11)))
  (is (= (hunt/poder-alvo-caca 11) (hunt/poder-alvo-caca 99)))
  (is (= 60 hunt/tolerancia-poder-caca))
  (is (= 25 hunt/tentativas-caca))
  (is (= 35 hunt/chance-tipos-clima))
  (is (= 50 hunt/chance-surto)))

(deftest selecao-bioma-teto-e-fallback-mais-fraco
  (let [tipos #{"water"}
        fraco magikarp
        forte (assoc geodude :tipos ["water"] :ataque 200 :defesa 200
                     :atq-esp 200 :def-esp 200 :hp 200)]
    (is (= "Magikarp"
           (:nome (hunt/selecionar-selvagem [forte fraco] 320 tipos))))
    ;; Sem ninguém no teto: cai no mais fraco do bioma.
    (is (= "Magikarp"
           (:nome (hunt/selecionar-selvagem [forte fraco] 50 tipos))))))

(deftest plano-evento-atual-e-surto
  (let [agora 1700000000000
        com-surto (hunt/montar-plano
                   {:agora-ms agora :trainer-level 1
                    :roll-tipos 50 :roll-surto 10})
        sem-surto (hunt/montar-plano
                   {:agora-ms agora :trainer-level 1
                    :roll-tipos 50 :roll-surto 90})]
    (is (some? (:evento-atual com-surto)))
    (is (some? (:evento-atual sem-surto)))
    (is (true? (:surto? com-surto)))
    (is (some? (:evento com-surto)))
    (is (false? (:surto? sem-surto)))
    (is (nil? (:evento sem-surto)))
    (is (re-find #"Festival|Faíscas|Trilha|Floresta"
                 (:nome (:evento-atual com-surto))))
    (is (re-find #"Clima de hoje" (:resumo-texto com-surto)))
    (is (re-find #"destaque" (hunt/texto-evento agora :prefix "!pk")))))

(deftest area-indisponivel-erro-com-resumo
  (let [dia "2026-10-17"
        bloq (first (mundo/areas-indisponiveis dia))
        instant (Instant/parse "2026-10-17T12:00:00-03:00")
        ex (try
             (hunt/montar-plano
              {:area-pedida (:id bloq)
               :instant instant
               :trainer-level 1
               :roll-tipos 0 :roll-surto 99})
             nil
             (catch Exception e e))]
    (is (= :area_unavailable (err/error-code ex)))
    (is (string? (:resumo-texto (ex-data ex))))
    (is (re-find #"Clima" (:resumo-texto (ex-data ex))))))

(deftest start-surto-usa-primeiro-candidato
  (let [s (fresh-store! "a41-surto")
        chat "chat-surto"
        player "ash"]
    (boot! s chat player)
    (let [r (hunt-app/start-hunt!
             s chat player
             {:operation-id "hunt-surto-1"
              :area-id "praia"
              :tipos-encontro ["water"]
              :surto? true
              :poder-alvo 320
              :candidates [geodude magikarp]
              :agora-ms 1700001000000
              :shiny-roll 1})]
      (is (= "Geodude" (get-in r [:selvagem :nome])))
      (is (true? (get-in r [:battle :surto?]))))))

(deftest idempotencia-e-retomada-plano
  (let [db "a41-idemp"
        s (fresh-store! db)
        chat "chat-id"
        player "misty"
        agora 1700002000000]
    (boot! s chat player)
    (let [req {:operation-id "hunt-a41"
               :area-id "praia"
               :tipos-encontro ["water" "ice" "flying" "electric"]
               :surto? false
               :poder-alvo 320
               :candidates [magikarp]
               :agora-ms agora
               :shiny-roll 1}
          r1 (hunt-app/start-hunt! s chat player req)
          r2 (hunt-app/start-hunt! s chat player req)
          s2 (reopen-store db)
          b (ports/active-battle s2 chat player)]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (some? b))
      (is (= :wild (:kind b)))
      (is (= "praia" (:bioma b))))))

(deftest cooldown-persiste
  (let [s (fresh-store! "a41-cd")
        chat "chat-cd"
        player "brock"
        agora 1700003000000]
    (boot! s chat player)
    (hunt-app/start-hunt!
     s chat player
     {:operation-id "hunt-cd-1"
      :area-id "praia"
      :tipos-encontro ["water"]
      :surto? false
      :poder-alvo 320
      :candidates [magikarp]
      :agora-ms agora
      :shiny-roll 1})
    (is (= :hunt_cooldown
           (try
             (hunt-app/start-hunt!
              s chat player
              {:operation-id "hunt-cd-2"
               :area-id "praia"
               :tipos-encontro ["water"]
               :surto? false
               :poder-alvo 320
               :candidates [magikarp]
               :agora-ms (+ agora 1000)
               :shiny-roll 1})
             :ok
             (catch Exception ex (err/error-code ex)))))
    (let [plano (hunt-app/plan s chat player {:agora-ms (+ agora 1000)})]
      (is (false? (get-in plano [:eligibilidade :cooldown-ok])))
      (is (pos? (get-in plano [:eligibilidade :cooldown-restante-s]))))))
