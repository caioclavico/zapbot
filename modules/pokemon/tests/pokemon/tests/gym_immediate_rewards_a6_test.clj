(ns pokemon.tests.gym-immediate-rewards-a6-test
  "Recompensas imediatas de ginásio — PE/XP por batalha, KO, conquista/defesa."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.growth :as growth]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-gym-imm-rew")
(def ^:private storage-rel ".datomic-local/gym-imm-rew")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player}))

(defn- mon
  [nome & {:keys [nivel] :or {nivel 10}}]
  {:nome nome :species nome :hp 80 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50
   :veloc 50 :tipos ["normal"] :nivel nivel
   :xp-total 0 :xp-desde-nivel 0 :xp-progress-version "growth-v1"
   :growth-rate :medium-fast
   :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- seed-team3!
  [s chat player]
  (doseq [n ["A" "B" "C"]]
    (collection/adicionar-pokemon! s chat player (mon n) 80 nil))
  (gym-app/set-gym-team! s chat player [0 1 2]))

(deftest dominio-valores-centralizados
  (is (= 10 gym/pe-vitoria-batalha))
  (is (= 4 gym/pe-derrota-batalha))
  (is (= 2 gym/pe-por-nocaute))
  (is (= 15 gym/pe-conquista-ginasio))
  (is (= 8 gym/pe-defesa-sucesso))
  (is (= 4 (gym/atividade-participante true 0)))
  (is (= 5 (gym/atividade-participante true 1)))
  (is (= 2 (gym/atividade-participante false 0)))
  (is (= 4 (gym/atividade-participante false 2)))
  (is (= 4 (gym/xp-ginasio-participante true :primeira 0)))
  (is (= 4 (gym/xp-ginasio-participante true nil 0)))
  (is (= 0 (:pe (gym/recompensa-vitoria :primeira 0)))))

(deftest plano-vitoria-com-nocautes-e-conquista
  (let [p (gym/plano-recompensa-batalha
           {:venceu? true
            :participacao {0 1 2 1}
            :premio :primeira
            :ocupacao nil
            :desafiante-player-key "c|ash"})]
    (is (= 10 (:pe-batalha p)))
    (is (= 4 (:pe-nocautes p)))
    (is (= 15 (:pe-conquista p)))
    (is (= 29 (:pe-desafiante p)))
    (is (= 0 (:pe-defensor p)))
    (is (true? (:conquistou? p)))
    (is (= 2 (count (:participantes p))))))

(deftest plano-derrota-com-defesa
  (let [p (gym/plano-recompensa-batalha
           {:venceu? false
            :participacao {0 0}
            :premio nil
            :ocupacao {:player-key "c|brock" :save-id "a"}
            :desafiante-player-key "c|ash"})]
    (is (= 4 (:pe-batalha p)))
    (is (= 0 (:pe-nocautes p)))
    (is (= 0 (:pe-conquista p)))
    (is (= 8 (:pe-defesa p)))
    (is (= 4 (:pe-desafiante p)))
    (is (= 8 (:pe-defensor p)))
    (is (true? (:defendeu? p)))))

(deftest vitoria-individual-imediata
  (let [s (fresh-store! "imm-win1")
        chat "c-w1"]
    (boot! s chat "ash")
    (seed-team3! s chat "ash")
    (let [r (gym-app/resolve-challenge!
             s chat "ash"
             {:operation-id "imm-w1" :gym-id "pedra" :venceu? true
              :dia "2026-11-11" :indices [0 1 2] :pedra-roll 0 :nome "Ash"
              :participacao {0 0} :agora 1000})
          save (ports/get-save s chat "ash" "a")]
      (is (= 25 (:pe-delta r))) ;; 10+15
      (is (= 25 (:pe-ginasios save)))
      (is (= 1 (count (:pokemon-rewards r))))
      (is (= 0 (:idx (first (:pokemon-rewards r)))))
      (is (pos? (:xp-oficial (first (:pokemon-rewards r)))))
      (is (true? (:occupied? r))))))

(deftest derrota-individual-imediata
  (let [s (fresh-store! "imm-lose1")
        chat "c-l1"]
    (boot! s chat "ash")
    (seed-team3! s chat "ash")
    (let [r (gym-app/resolve-challenge!
             s chat "ash"
             {:operation-id "imm-l1" :gym-id "pedra" :venceu? false
              :dia "2026-11-11" :participacao {0 0} :agora 1000})
          save (ports/get-save s chat "ash" "a")
          col (ports/list-collection s chat "ash" "a")]
      (is (= 4 (:pe-delta r)))
      (is (= 4 (:pe-ginasios save)))
      (is (= 1 (count (:pokemon-rewards r))))
      (is (pos? (:xp-oficial (first (:pokemon-rewards r)))))
      (is (= 3 (count col))))))

(deftest multiplos-participantes-vitoria
  (let [s (fresh-store! "imm-multi-w")
        chat "c-mw"]
    (boot! s chat "ash")
    (seed-team3! s chat "ash")
    (let [r (gym-app/resolve-challenge!
             s chat "ash"
             {:operation-id "imm-mw" :gym-id "pedra" :venceu? true
              :dia "2026-11-11" :indices [0 1 2] :pedra-roll 0 :nome "Ash"
              :participacao {0 1 1 0 2 2} :agora 1000})]
      (is (= 3 (count (:pokemon-rewards r))))
      (is (= 31 (:pe-delta r))) ;; 10 + 2*(1+0+2)=6 + 15
      (is (= 6 (:pe-nocautes r))))))

(deftest multiplos-participantes-derrota
  (let [s (fresh-store! "imm-multi-l")
        chat "c-ml"]
    (boot! s chat "ash")
    (seed-team3! s chat "ash")
    (let [r (gym-app/resolve-challenge!
             s chat "ash"
             {:operation-id "imm-ml" :gym-id "pedra" :venceu? false
              :dia "2026-11-11" :participacao {0 1 2 0} :agora 1000})]
      (is (= 2 (count (:pokemon-rewards r))))
      (is (= 6 (:pe-delta r))) ;; 4 + 2*1
      (is (nil? (some #(= 1 (:idx %)) (:pokemon-rewards r)))))))

(deftest reserva-sem-participacao-nao-ganha-xp
  (let [s (fresh-store! "imm-bench")
        chat "c-bench"]
    (boot! s chat "ash")
    (seed-team3! s chat "ash")
    (let [antes (ports/list-collection s chat "ash" "a")
          xp1 (long (:xp-total (nth antes 1) 0))
          r (gym-app/resolve-challenge!
             s chat "ash"
             {:operation-id "imm-bench" :gym-id "pedra" :venceu? false
              :dia "2026-11-11" :participacao {0 0} :agora 1000})
          depois (ports/list-collection s chat "ash" "a")]
      (is (= 1 (count (:pokemon-rewards r))))
      (is (= xp1 (long (:xp-total (nth depois 1) 0)))))))

(deftest nocauteado-continua-elegivel
  (let [s (fresh-store! "imm-faint")
        chat "c-f"]
    (boot! s chat "ash")
    (seed-team3! s chat "ash")
    (let [r (gym-app/resolve-challenge!
             s chat "ash"
             {:operation-id "imm-f" :gym-id "pedra" :venceu? false
              :dia "2026-11-11" :participacao {0 1 1 0} :agora 1000})]
      (is (= 2 (count (:pokemon-rewards r))))
      (is (every? #(pos? (:xp-oficial %)) (:pokemon-rewards r))))))

(deftest um-e-multiplos-nocautes
  (is (= 2 (:pe-nocautes (gym/plano-recompensa-batalha
                          {:venceu? true :participacao {0 1} :premio nil
                           :ocupacao nil :desafiante-player-key "x"}))))
  (is (= 6 (:pe-nocautes (gym/plano-recompensa-batalha
                          {:venceu? true :participacao {0 1 1 2} :premio nil
                           :ocupacao nil :desafiante-player-key "x"}))))
  (let [s (fresh-store! "imm-ko")
        chat "c-ko"]
    (boot! s chat "ash")
    (seed-team3! s chat "ash")
    (let [r (gym-app/resolve-challenge!
             s chat "ash"
             {:operation-id "imm-ko" :gym-id "pedra" :venceu? false
              :dia "2026-11-11" :participacao {0 3} :agora 1000})]
      (is (= 3 (:nocautes-total r)))
      (is (= 6 (:pe-nocautes r)))
      (is (= 3 (:nocautes (first (:pokemon-rewards r))))))))

(deftest nocaute-sem-autor-nao-inventa
  (let [p (gym/plano-recompensa-batalha
           {:venceu? true :participacao {} :premio :primeira
            :ocupacao nil :desafiante-player-key "x"})]
    (is (= 0 (:pe-nocautes p)))
    (is (= [] (:participantes p)))
    (is (= 25 (:pe-desafiante p)))))

(deftest conquista-apos-varias-batalhas-sem-duplicar
  (let [s (fresh-store! "imm-seq")
        chat "c-seq"]
    (boot! s chat "ash")
    (seed-team3! s chat "ash")
    (let [l1 (gym-app/resolve-challenge!
              s chat "ash"
              {:operation-id "imm-seq-l1" :gym-id "pedra" :venceu? false
               :dia "2026-11-11" :participacao {0 0} :agora 1000})
          pe1 (:pe-ginasios (ports/get-save s chat "ash" "a"))
          ;; remonta time (ainda na coleção)
          _ (gym-app/set-gym-team! s chat "ash" [0 1 2])
          w (gym-app/resolve-challenge!
             s chat "ash"
             {:operation-id "imm-seq-w" :gym-id "pedra" :venceu? true
              :dia "2026-11-11" :indices [0 1 2] :pedra-roll 0 :nome "Ash"
              :participacao {0 0} :agora 2000})
          pe2 (:pe-ginasios (ports/get-save s chat "ash" "a"))]
      (is (= 4 (:pe-delta l1)))
      (is (= 4 pe1))
      (is (= 25 (:pe-delta w)))
      (is (= 29 pe2)) ;; 4 da derrota + 25 da vitória/conquista
      (is (true? (:conquistou? w))))))

(deftest defesa-bem-sucedida
  (let [s (fresh-store! "imm-def")
        chat "c-def"]
    (boot! s chat "brock")
    (boot! s chat "ash")
    (seed-team3! s chat "brock")
    (gym-app/resolve-challenge!
     s chat "brock"
     {:operation-id "imm-def-occ" :gym-id "pedra" :venceu? true
      :dia "2026-11-10" :indices [0 1 2] :pedra-roll 0 :nome "Brock"
      :participacao {0 0} :agora 1000})
    (seed-team3! s chat "ash")
    (let [pe-antes (:pe-ginasios (ports/get-save s chat "brock" "a"))
          r (gym-app/resolve-challenge!
             s chat "ash"
             {:operation-id "imm-def-atk" :gym-id "pedra" :venceu? false
              :dia "2026-11-11" :participacao {0 0} :agora 2000})
          pe-depois (:pe-ginasios (ports/get-save s chat "brock" "a"))]
      (is (true? (:defendeu? r)))
      (is (= 8 (:pe-defesa r)))
      (is (= (+ pe-antes 8) pe-depois)))))

(deftest recibo-duplicado-nao-paga-duas-vezes
  (let [s (fresh-store! "imm-dup")
        chat "c-dup"
        req {:operation-id "imm-dup-1" :gym-id "pedra" :venceu? true
             :dia "2026-11-11" :indices [0 1 2] :pedra-roll 0 :nome "Ash"
             :participacao {0 1} :agora 1000}]
    (boot! s chat "ash")
    (seed-team3! s chat "ash")
    (let [r1 (gym-app/resolve-challenge! s chat "ash" req)
          pe1 (:pe-ginasios (ports/get-save s chat "ash" "a"))
          r2 (gym-app/resolve-challenge! s chat "ash" req)
          pe2 (:pe-ginasios (ports/get-save s chat "ash" "a"))]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= pe1 pe2)))))

(deftest growth-v1-moderado-vs-batalha-comum
  (let [reg (mon "Pikachu" :nivel 20)
        win-comum (xp/calcular-ganho-pokemon reg xp/xp-por-vitoria {:atividade :batalha})
        win-ginasio (xp/calcular-ganho-pokemon reg gym/atividade-vitoria-batalha
                                               {:atividade :ginasio})
        loss-ginasio (xp/calcular-ganho-pokemon reg gym/atividade-derrota-batalha
                                                {:atividade :ginasio})]
    (is (pos? win-comum))
    (is (pos? win-ginasio))
    (is (>= win-ginasio win-comum)
        "Vitória de ginásio (4 pts × 1.10) ≥ vitória comum (3 pts)")
    (is (< loss-ginasio win-ginasio))
    (is (<= win-ginasio
            (max 1 (long (Math/floor
                          (* (double (growth/level-span :medium-fast 20))
                             xp/pokemon-xp-grant-span-cap))))))))

(deftest pokemon-nivel-100-sem-xp
  (let [reg (assoc (mon "Mew" :nivel 100)
                   :xp-total (growth/total-xp-for-level :medium-fast 100)
                   :xp-desde-nivel 0)
        ganho (xp/calcular-ganho-pokemon reg 4 {:atividade :ginasio})]
    (is (zero? ganho))))

(deftest saves-preservados-em-derrota-antes-conquista
  (let [s (fresh-store! "imm-pres")
        chat "c-pres"]
    (boot! s chat "ash")
    (seed-team3! s chat "ash")
    (let [antes (count (ports/list-collection s chat "ash" "a"))]
      (gym-app/resolve-challenge!
       s chat "ash"
       {:operation-id "imm-pres-l" :gym-id "pedra" :venceu? false
        :dia "2026-11-11" :participacao {0 1 1 0} :agora 1000})
      (is (= antes (count (ports/list-collection s chat "ash" "a"))))
      (is (nil? (ports/get-gym-occupation s chat "pedra"))))))
