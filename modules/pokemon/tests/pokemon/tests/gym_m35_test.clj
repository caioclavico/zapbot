(ns pokemon.tests.gym-m35-test
  "M3.5 — ginásios: desafio, ocupação, fence e idempotência."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m35-tests")
(def ^:private storage-rel ".datomic-local/m35-tests")

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

(defn- load-fixtures
  []
  (edn/read-string
   (slurp (io/file (persistence/project-root)
                   "modules/pokemon/tests/pokemon/tests/fixtures/legacy_gym.edn"))))

(def ^:private mon
  (fn [nome]
    {:nome nome :hp 80 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 50
     :tipos ["normal"] :nivel 10
     :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]}))

(defn- seed-team3!
  [s chat player]
  (doseq [n ["A" "B" "C"]]
    (collection/adicionar-pokemon! s chat player (mon n) 80 nil))
  (gym-app/set-gym-team! s chat player [0 1 2]))

(deftest equivalencia-regras-ginasio
  (let [fx (load-fixtures)]
    (doseq [c (:catalog fx)]
      (is (= (:nivel c) (:nivel (gym/obter-ginasio (:id c)))))
      (is (= (:item c) (:item (gym/obter-ginasio (:id c))))))
    (doseq [u (:unlock fx)]
      (is (= (:ok u) (gym/desbloqueado? (:insignias u) (:gym u)))))
    (doseq [x (:xp-participante fx)]
      (is (= (:xp x) (gym/xp-ginasio-participante (:venceu? x) (:premio x) (:nocautes x)))))
    (doseq [r (:recompensa-vitoria fx)]
      (let [out (gym/recompensa-vitoria (:premio r) (:pedra-roll r))]
        (is (= (:moedas r) (:moedas out)))
        (is (= (:pe r) (:pe out)))
        (is (= (:pedra? r) (:pedra? out)))))
    (doseq [m (:motivacao fx)]
      (let [agora 1000000000000
            desde (if (:horas m)
                    (- agora (* (:horas m) 60 60 1000))
                    agora)
            ocup {:motivacao (:base m) :motivacao-em desde :desde desde :time [1 2 3]}
            mots (if (:defesa m)
                   (:motivacao (gym/desgastar-motivacao ocup agora))
                   (gym/motivacoes ocup agora))]
        (is (= (:expect m) mots))))
    (doseq [f (:fator-stats fx)]
      (is (= (:fator f) (gym/fator-motivacao (:motivacao f)))))
    (doseq [p (:permanencia fx)]
      (let [agora 2000000000000
            ocup {:desde (- agora (:ms p)) :time [1 2 3]}]
        (is (= (:moedas p) (gym/recompensa-permanencia ocup agora)))
        (is (= (:xp p) (gym/xp-permanencia ocup agora)))))))

(deftest vitoria-primeira-e-insignia
  (let [s (fresh-store! "g-win")
        chat "chat-g1"
        player "ash"]
    (boot! s chat player)
    (seed-team3! s chat player)
    (let [r (gym-app/resolve-challenge!
             s chat player
             {:operation-id "gym-win-1"
              :gym-id "pedra"
              :venceu? true
              :dia "2026-10-17"
              :indices [0 1 2]
              :participacao {0 1 1 0 2 1}
              :pedra-roll 0
              :nome "Ash"})
          save (ports/get-save s chat player "a")
          occ (gym-app/get-occupation s chat "pedra")
          inv (ports/list-inventory s chat player "a")]
      (is (false? (:duplicate r)))
      (is (= :primeira (:premio r)))
      (is (= 100 (get-in r [:reward :moedas])))
      (is (= 5 (:pe-delta r)))
      (is (true? (:occupied? r)))
      (is (= "pedra-lua" (:item-id (first (filter #(= "pedra-lua" (:item-id %)) inv)))))
      (is (= {"pedra" "2026-10-17"} (:ginasios save)))
      (is (= 5 (:pe-ginasios save)))
      (is (= 100 (:coins save)))
      (is (= player (:pid occ)))
      (is (= "a" (:save-id occ)))
      (is (= 0 (count (ports/list-collection s chat player "a"))))
      (is (= 1 (:gym-defense-count (ports/get-player s chat player)))))))

(deftest derrota-concede-pe-e-desgaste
  (let [s (fresh-store! "g-lose")
        chat "chat-g2"]
    (boot! s chat "ash")
    (boot! s chat "brock")
    (seed-team3! s chat "brock")
    (gym-app/resolve-challenge!
     s chat "brock"
     {:operation-id "occ-brock" :gym-id "pedra" :venceu? true
      :dia "2026-10-16" :indices [0 1 2] :pedra-roll 0 :nome "Brock"
      :participacao {0 0}})
    (seed-team3! s chat "ash")
    (let [antes (gym-app/get-occupation s chat "pedra")
          r (gym-app/resolve-challenge!
             s chat "ash"
             {:operation-id "gym-lose-1" :gym-id "pedra" :venceu? false
              :dia "2026-10-17" :participacao {0 2}
              :agora (+ (:desde antes) 1000)})
          depois (gym-app/get-occupation s chat "pedra")
          save (ports/get-save s chat "ash" "a")]
      (is (= 1 (:pe-delta r)))
      (is (nil? (:premio r)))
      (is (= 1 (:pe-ginasios save)))
      (is (= "brock" (:pid depois)))
      (is (= [88 88 88] (mapv long (gym/motivacoes depois (+ (:desde antes) 1000))))))))

(deftest ocupacao-devolve-ao-save-origem
  (let [s (fresh-store! "g-dethrone")
        chat "chat-g3"]
    (boot! s chat "misty")
    (boot! s chat "ash")
    (seed-team3! s chat "misty")
    (gym-app/resolve-challenge!
     s chat "misty"
     {:operation-id "m-occ" :gym-id "pedra" :venceu? true
      :dia "2026-10-10" :indices [0 1 2] :pedra-roll 0 :nome "Misty"
      :participacao {0 0}
      :agora 1000000000000})
    (seed-team3! s chat "ash")
    (gym-app/resolve-challenge!
     s chat "ash"
     {:operation-id "a-occ" :gym-id "pedra" :venceu? true
      :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "Ash"
      :participacao {0 0}
      :agora (+ 1000000000000 (* 7 60 60 1000))  ;; >6h
      :expected-revision 1})
    (let [occ (gym-app/get-occupation s chat "pedra")
          misty-col (ports/list-collection s chat "misty" "a")
          misty-save (ports/get-save s chat "misty" "a")]
      (is (= "ash" (:pid occ)))
      (is (= "a" (:save-id occ)))
      (is (= 3 (count misty-col)))
      (is (every? zero? (map :hp-atual misty-col)))
      ;; 100 da 1ª vitória + 50 de permanência (>6h)
      (is (= 150 (:coins misty-save)))
      (is (= 0 (:gym-defense-count (ports/get-player s chat "misty"))))
      (is (= 1 (:gym-defense-count (ports/get-player s chat "ash")))))))

(deftest switch-bloqueado-com-defensor
  (let [s (fresh-store! "g-block")
        chat "chat-g4"
        player "red"]
    (boot! s chat player)
    (seed-team3! s chat player)
    (gym-app/resolve-challenge!
     s chat player
     {:operation-id "occ-red" :gym-id "pedra" :venceu? true
      :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "Red"
      :participacao {0 0}})
    (let [code (try
                 (trainer/switch-save! s chat player "b")
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :gym_defense_pending code)))))

(deftest a-b-a-isolamento-save
  (let [s (fresh-store! "g-aba")
        chat "chat-g5"
        player "leaf"]
    (boot! s chat player)
    ;; ocupa com A, depois “libera” trocando ocupação via outro jogador
    (seed-team3! s chat player)
    (gym-app/resolve-challenge!
     s chat player
     {:operation-id "leaf-a" :gym-id "pedra" :venceu? true
      :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "Leaf"
      :participacao {0 0}})
    (boot! s chat "rival")
    (seed-team3! s chat "rival")
    (gym-app/resolve-challenge!
     s chat "rival"
     {:operation-id "rival-occ" :gym-id "pedra" :venceu? true
      :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "Rival"
      :participacao {0 0} :expected-revision 1
      :agora (+ (System/currentTimeMillis) 1000)})
    ;; leaf livre para switch
    (trainer/switch-save! s chat player "b")
    (is (= "b" (ports/active-save-id s chat player)))
    (is (= 3 (count (ports/list-collection s chat player "a"))))
    (trainer/switch-save! s chat player "a")
    (is (= "a" (ports/active-save-id s chat player)))))

(deftest idempotencia-resolve
  (let [s (fresh-store! "g-idem")
        chat "chat-g6"
        player "blue"
        req {:operation-id "once-gym" :gym-id "pedra" :venceu? true
             :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "Blue"
             :participacao {0 0}}]
    (boot! s chat player)
    (seed-team3! s chat player)
    (let [r1 (gym-app/resolve-challenge! s chat player req)
          r2 (gym-app/resolve-challenge! s chat player req)]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= 100 (:coins (ports/get-save s chat player "a")))))))

(deftest concorrencia-ocupacao
  (let [s (fresh-store! "g-conc")
        chat "chat-g7"]
    (boot! s chat "p1")
    (boot! s chat "p2")
    (seed-team3! s chat "p1")
    (seed-team3! s chat "p2")
    (let [futs (doall
                (for [p ["p1" "p2"]]
                  (future
                    (try
                      (gym-app/resolve-challenge!
                       s chat p
                       {:operation-id (str "conc-" p) :gym-id "pedra" :venceu? true
                        :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome p
                        :participacao {0 0}})
                      (catch Exception ex {:error (err/error-code ex)})))))
          results (mapv deref futs)
          oks (filter #(and (map? %) (false? (:duplicate %)) (nil? (:error %)) (:occupied? %)) results)]
      (is (= 1 (count oks)))
      (is (some? (gym-app/get-occupation s chat "pedra"))))))

(deftest callback-stale-apos-switch
  (let [s (fresh-store! "g-stale")
        chat "chat-g8"
        player "green"]
    (boot! s chat player)
    (seed-team3! s chat player)
    (let [w (writer/freeze-writer s chat player)]
      (trainer/switch-save! s chat player "b")
      (let [code (try
                   (gym-app/resolve-challenge!
                    s w
                    {:operation-id "stale-gym" :gym-id "pedra" :venceu? true
                     :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "G"
                     :participacao {0 0}})
                   :ok
                   (catch Exception ex (err/error-code ex)))]
        (is (= :stale_writer_fence code))))))

(deftest persistencia-reabrir
  (let [db "g-persist"
        chat "chat-g9"
        player "yellow"]
    (let [s (fresh-store! db)]
      (boot! s chat player)
      (seed-team3! s chat player)
      (gym-app/resolve-challenge!
       s chat player
       {:operation-id "pers-gym" :gym-id "pedra" :venceu? true
        :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "Y"
        :participacao {0 0}}))
    (let [s (reopen-store db)
          occ (gym-app/get-occupation s chat "pedra")
          retry (gym-app/resolve-challenge!
                 s chat player
                 {:operation-id "pers-gym" :gym-id "pedra" :venceu? true
                  :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "Y"
                  :participacao {0 0}})]
      (is (true? (:duplicate retry)))
      (is (= "yellow" (:pid occ)))
      (is (= "a" (:save-id occ)))
      (is (= 3 (count (:time occ)))))))

(deftest heal-motivacao-lider
  (let [s (fresh-store! "g-heal")
        chat "chat-g10"
        player "heal"]
    (boot! s chat player)
    (seed-team3! s chat player)
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-pocao" :chat-id chat :player-id player :save-id "a"
        :item {:item-id "pocao" :qty 2}})
    (gym-app/resolve-challenge!
     s chat player
     {:operation-id "occ-h" :gym-id "pedra" :venceu? true
      :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "H"
      :participacao {0 0} :agora 1000})
    ;; desgasta via derrota de outro
    (boot! s chat "atk")
    (seed-team3! s chat "atk")
    (gym-app/resolve-challenge!
     s chat "atk"
     {:operation-id "atk-lose" :gym-id "pedra" :venceu? false
      :dia "2026-10-17" :participacao {0 0} :agora 2000})
    (let [r (gym-app/heal-motivation!
             s chat player
             {:operation-id "heal-1" :gym-id "pedra" :indice 0
              :item-id "pocao" :pontos 40 :agora 3000})]
      (is (= 88 (:antes r)))
      (is (= 100 (:depois r))))))
