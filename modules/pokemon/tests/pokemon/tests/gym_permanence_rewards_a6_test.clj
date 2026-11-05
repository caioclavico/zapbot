(ns pokemon.tests.gym-permanence-rewards-a6-test
  "Permanência A6: 1 moeda/10 min, mín. 30 min, teto 100/dia, flag off por padrão."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.gym :as gym]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-gym-permanence-a6")
(def ^:private storage-rel ".datomic-local/gym-permanence-a6")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- mon
  [nome]
  {:nome nome :hp 80 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 50
   :tipos ["normal"] :nivel 10
   :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- boot-team!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (doseq [n ["A" "B" "C"]]
    (collection/adicionar-pokemon! s chat player (mon n) 80 nil))
  (gym-app/set-gym-team! s chat player [0 1 2]))

(defn- occupy!
  [s chat player gym-id op agora]
  (gym-app/resolve-challenge!
   s chat player
   {:operation-id op :gym-id gym-id :venceu? true
    :dia "2026-11-05" :indices [0 1 2] :pedra-roll 0 :nome player
    :participacao {0 0} :agora agora}))

(defn- dethrone!
  [s chat defender challenger gym-id op t0 duration-ms & {:keys [expected-revision]}]
  (boot-team! s chat challenger)
  (gym-app/resolve-challenge!
   s chat challenger
   (cond-> {:operation-id op :gym-id gym-id :venceu? true
            :dia "2026-11-05" :indices [0 1 2] :pedra-roll 0 :nome challenger
            :participacao {0 0}
            :agora (+ t0 duration-ms)}
     expected-revision (assoc :expected-revision expected-revision))))

(deftest dominio-bruta-e-teto
  (is (= 0 (gym/permanencia-bruta-moedas (* 9 60 1000))))
  (is (= 0 (gym/permanencia-bruta-moedas (* 20 60 1000))))
  (is (= 3 (gym/permanencia-bruta-moedas (* 30 60 1000))))
  (is (= 6 (gym/permanencia-bruta-moedas (* 60 60 1000))))
  (is (= 18 (gym/permanencia-bruta-moedas (* 3 60 60 1000))))
  (is (= 50 (gym/permanencia-bruta-moedas (* 500 60 1000))))
  (is (= 72 (gym/permanencia-bruta-moedas (* 12 60 60 1000))))
  (is (= 1 (gym/aplicar-teto-diario 10 99)))
  (is (= 0 (gym/aplicar-teto-diario 10 100)))
  (is (= 100 (gym/aplicar-teto-diario 200 0)))
  (let [off (gym/plano-recompensa-permanencia
             {:duration-ms (* 60 60 1000) :already-today 0 :economy-enabled? false})
        on (gym/plano-recompensa-permanencia
            {:duration-ms (* 60 60 1000) :already-today 99 :economy-enabled? true})]
    (is (= 0 (:payable off)))
    (is (= 6 (:gross off)))
    (is (= 1 (:payable on)))
    (is (= 1 (:capped on))))
  (is (false? (gym/permanence-rewards-enabled?)))
  (is (= "2026-11-05"
         (gym/day-key 1793917800000 "America/Campo_Grande"))))

(deftest flag-off-nao-credita-permanencia
  (let [s (fresh-store! "perm-flag-off")
        chat "c-off"
        t0 1793856000000]
    (boot-team! s chat "misty")
    (occupy! s chat "misty" "pedra" "m-occ" t0)
    (dethrone! s chat "misty" "ash" "pedra" "a-occ" t0 (* 7 60 60 1000)
               :expected-revision 1)
    (is (= 100 (:coins (ports/get-save s chat "misty" "a")))
        "sem flag: só vitória, sem permanência")))

(deftest pagamento-com-flag-e-minimo
  (with-redefs [gym/permanence-rewards-enabled? (constantly true)]
    (testing "abaixo de 30 min → 0"
      (let [s (fresh-store! "perm-short")
            chat "c-short"
            t0 1793856000000]
        (boot-team! s chat "misty")
        (occupy! s chat "misty" "pedra" "m1" t0)
        (dethrone! s chat "misty" "ash" "pedra" "a1" t0 (* 20 60 1000)
                   :expected-revision 1)
        (is (= 100 (:coins (ports/get-save s chat "misty" "a"))))))
    (testing "30 min → +3"
      (let [s (fresh-store! "perm-30")
            chat "c-30"
            t0 1793856000000]
        (boot-team! s chat "misty")
        (occupy! s chat "misty" "pedra" "m2" t0)
        (dethrone! s chat "misty" "ash" "pedra" "a2" t0 (* 30 60 1000)
                   :expected-revision 1)
        (is (= 103 (:coins (ports/get-save s chat "misty" "a"))))))))

(deftest teto-diario-99-e-concorrencia-dois-ginasios
  ;; desbloqueado? liberado: focamos teto diário entre ginásios, não a trilha de insígnias.
  (with-redefs [gym/permanence-rewards-enabled? (constantly true)
                gym/desbloqueado? (fn [_insignias gym-id] (boolean (gym/obter-ginasio gym-id)))]
    (let [s (fresh-store! "perm-cap")
          chat "c-cap"
          t0 1793856000000]
      (boot-team! s chat "brock")
      (occupy! s chat "brock" "pedra" "b-pedra" t0)
      (doseq [n ["D" "E" "F"]]
        (collection/adicionar-pokemon! s chat "brock" (mon n) 80 nil))
      (gym-app/set-gym-team! s chat "brock" [0 1 2])
      (occupy! s chat "brock" "agua" "b-agua" t0)
      ;; Vitórias NPC: pedra + água = 200 antes da permanência
      (is (= 200 (:coins (ports/get-save s chat "brock" "a"))))
      (boot-team! s chat "ash")
      (dethrone! s chat "brock" "ash" "pedra" "ash-p" t0 (* 990 60 1000)
                 :expected-revision 1)
      (is (= (+ 200 99) (:coins (ports/get-save s chat "brock" "a")))
          "16h30 → +99 permanência (saldo diário 99)")
      (boot-team! s chat "gary")
      (dethrone! s chat "brock" "gary" "agua" "gary-a" t0 (* 60 60 1000)
                 :expected-revision 1)
      (is (= (+ 200 100) (:coins (ports/get-save s chat "brock" "a")))
          "segundo ginásio só completa +1 até o teto diário 100")
      (is (= 0 (:payable (gym/plano-recompensa-permanencia
                          {:duration-ms (* 60 60 1000)
                           :already-today 100
                           :economy-enabled? true})))))))

(deftest idempotencia-reward-id
  (is (= "gym-reward:chat|pedra:1000"
         (gym/occupation-reward-id "chat|pedra" 1000)))
  (is (not= (gym/occupation-reward-id "chat|pedra" 1000)
            (gym/occupation-reward-id "chat|pedra" 2000))
      "reentrada do mesmo ginásio gera identidade distinta"))
