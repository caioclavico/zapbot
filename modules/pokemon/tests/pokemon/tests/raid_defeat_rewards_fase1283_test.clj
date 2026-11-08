(ns pokemon.tests.raid-defeat-rewards-fase1283-test
  "Fase 12.8.3 — PE/XP de participação em raids derrotadas (sem economia/captura)."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.event-missions :as em]
            [pokemon.domain.identities :as id]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.battle-idle-store :as idle-store]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-defeat-1283")
(def ^:private storage-rel ".datomic-local/raid-defeat-1283")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      {:s s :conn conn})))

;; Mon frágil + ataque baixo: morre pelo revide antes de zerar o chefe
;; (hp-chefe ≈ 320×n; dano ~20/hit; 5 hits até KO do mon).
(def ^:private mon-fraco
  {:nome "Magikarp" :hp 10 :ataque 10 :defesa 20 :atq-esp 10 :def-esp 20 :veloc 80
   :tipos ["water"] :nivel 5 :raridade "comum" :xp-desde-nivel 0
   :golpes [{:nome-exibicao "Tackle" :tipo "normal" :poder 40 :classe :fisico}]})

(def ^:private mon-quase-nivel
  (assoc mon-fraco :nome "Feebas" :xp-desde-nivel 7))

(def ^:private mon-vitoria
  {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 40
   :tipos ["normal"] :nivel 20 :raridade "raro"
   :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 80 :classe :fisico}]})

(def ^:private chefe-tanque
  {:nome "Onix" :hp 5000 :ataque 50 :defesa 120 :atq-esp 40 :def-esp 100 :veloc 20
   :tipos ["rock" "ground"] :nivel 30 :raridade "raro"
   :fator-dificuldade 2.0
   :golpes [{:nome-exibicao "Rock Throw" :tipo "rock" :poder 50 :classe :fisico}]})

(def ^:private chefe-fraco
  {:nome "Onix" :hp 100 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 30
   :tipos ["rock"] :nivel 1 :raridade "raro"
   :golpes [{:nome-exibicao "Tackle" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot!
  ([s chat player] (boot! s chat player mon-fraco))
  ([s chat player mon]
   (trainer/create-trainer! s {:chat-id chat :player-id player})
   ;; hp-atual selado na inscrição da raid — usar o HP do mon (não 200).
   (collection/adicionar-pokemon! s chat player mon (long (:hp mon)) nil)
   (reward-app/admin-seed-reward!
    s {:operation-id (str "ub-" chat "-" player)
       :chat-id chat :player-id player :save-id "a"
       :item {:item-id "ultra-bola" :qty 3}})))

(defn- open!
  [s chat rid chefe]
  (tx/open-raid!
   s {:chat-id chat :player-id "ash" :save-id "a"
      :fence-generation (fence s chat "ash")
      :operation-id (str "open-" rid)
      :raid-id rid :ginasio "pedra" :liga "bronze" :chefe chefe
      :agora-ms 1000}))

(defn- join-start!
  [s chat rid players]
  (doseq [p players]
    (tx/join-raid!
     s {:chat-id chat :player-id p :save-id "a"
        :fence-generation (fence s chat p)
        :operation-id (str "j-" rid "-" p)
        :collection-index 0 :nome p :agora-ms 2}))
  (tx/start-raid!
   s {:chat-id chat :player-id (first players) :save-id "a"
      :fence-generation (fence s chat (first players))
      :operation-id (str "st-" rid) :agora-ms 4}))

(defn- fight-until!
  "Ataca até fase ∈ terminal ou n max. Retorna {:raid :last-atk :kill-req}."
  [s chat rid terminal]
  (let [last (atom nil)
        kill (atom nil)]
    (loop [n 0]
      (let [r (raid-app/get-raid s rid)
            fase (str (:fase r))]
        (cond
          (contains? terminal fase)
          {:raid r :last-atk @last :kill-req @kill}

          (or (>= n 80) (not= "combate" fase))
          {:raid (raid-app/get-raid s rid)
           :last-atk @last
           :kill-req @kill
           :aborted? true
           :fase fase}

          :else
          (let [pid (:vez r)
                req {:chat-id chat :player-id pid :save-id "a"
                     :fence-generation (fence s chat pid)
                     :operation-id (str "atk-" rid "-" n)
                     :expected-turn-index (:turn-index r)
                     :move-index 0
                     :agora-ms (+ 10 n)}
                res (tx/raid-attack! s req)]
            (reset! last res)
            (when (or (:venceu res) (:derrota res))
              (reset! kill req))
            (recur (inc n))))))))

(defn- strip-operational!
  [conn chat players]
  (d/transact
   conn
   {:tx-data
    (mapcat
     (fn [pid]
       (let [sk (id/save-key chat pid "a")
             e (d/pull (d/db conn) '[*] [:save/key sk])
             rev (long (:save/revision e 0))]
         (cond-> [[:db/add [:save/key sk] :save/economy-status "unknown-pending"]
                  [:db/add [:save/key sk] :save/coins-historical-status "unknown"]
                  [:db/add [:save/key sk] :save/coins 999]
                  [:db/cas [:save/key sk] :save/revision rev (inc rev)]]
           (some? (:save/coins-operational e))
           (concat
            [[:db/retract [:save/key sk] :save/coins-operational
              (:save/coins-operational e)]
             [:db/retract [:save/key sk] :save/coins-operational-earned
              (:save/coins-operational-earned e 0)]
             [:db/retract [:save/key sk] :save/coins-operational-spent
              (:save/coins-operational-spent e 0)]]))))
     players)}))

;;;; Domínio — tabela e elegibilidade

(deftest tabela-recompensas-vitória-inalterada-e-derrota-metade
  (is (= {:moedas 50 :pe 6 :xp-pokemon 6} (raid/recompensas :vitoria)))
  (is (= {:moedas 0 :pe 3 :xp-pokemon 3} (raid/recompensas :derrota)))
  (is (= 3 raid/recompensa-pe-derrota))
  (is (= 3 raid/recompensa-xp-pokemon-derrota))
  (is (= 0 raid/recompensa-moedas-derrota))
  (is (= (quot raid/recompensa-pe 2) raid/recompensa-pe-derrota)
      "arredondamento quot/floor documentado")
  (is (false? (raid/elegivel-recompensa? {:dano 0})))
  (is (true? (raid/elegivel-recompensa? {:dano 1}))))

(deftest missoes-participacao-vs-vitoria
  (let [join-m {:acao "raid-participou"}
        win-m {:acao "raid-venceu"}]
    (is (true? (em/action-matches? join-m {:type :raid-joined})))
    (is (true? (em/action-matches? join-m {:type :raid-won :raid-won? true})))
    (is (false? (em/action-matches? win-m {:type :raid-joined})))
    (is (true? (em/action-matches? win-m {:type :raid-won :raid-won? true})))
    ;; Derrota não emite :raid-won — só join conta participação.
    (is (false? (em/action-matches? win-m {:type :wild-defeated})))))

;;;; Derrota solo / multi / KO / vitória regressão

(deftest derrota-solo-pe-xp-sem-moedas
  (let [{:keys [s]} (fresh! "solo-def")
        chat "c-solo" rid "raid-solo"]
    (boot! s chat "ash")
    (open! s chat rid chefe-tanque)
    (join-start! s chat rid ["ash"])
    (let [{:keys [raid last-atk]} (fight-until! s chat rid #{"derrota" "vitoria"})
          ash (ports/get-save s chat "ash" "a")
          poke (first (ports/list-collection s chat "ash" "a"))
          inv (->> (ports/list-inventory s chat "ash" "a")
                   (filter #(= "ultra-bola" (:item-id %)))
                   (map :qty) (reduce + 0))]
      (is (= "derrota" (:fase raid)))
      (is (true? (:derrota last-atk)))
      (is (true? (:rewards-paid? raid)))
      (is (= 1 (count (:rewards last-atk))))
      (is (= 0 (:moedas (first (:rewards last-atk)))))
      (is (= 3 (:pe (first (:rewards last-atk)))))
      (is (= 3 (:xp-pokemon (first (:rewards last-atk)))))
      (is (= "derrota" (:outcome (first (:rewards last-atk)))))
      (is (= 3 (long (:pe-raids ash))))
      (is (= 0 (long (:coins ash 0))))
      (is (= 3 (long (:xp-desde-nivel poke 0))))
      (is (= 3 inv) "ultra-bola intacta — sem captura/consumo")
      (is (zero? (get-in raid [:participantes "ash" :hp]))
          "nocauteado no snapshot ainda elegível"))))

(deftest derrota-multiplayer-recompensas-por-participante
  (let [{:keys [s]} (fresh! "multi-def")
        chat "c-multi" rid "raid-multi"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (open! s chat rid chefe-tanque)
    (join-start! s chat rid ["ash" "misty"])
    (let [{:keys [raid last-atk]} (fight-until! s chat rid #{"derrota" "vitoria"})
          ash (ports/get-save s chat "ash" "a")
          misty (ports/get-save s chat "misty" "a")
          by-pid (into {} (map (juxt :player-id identity) (:rewards last-atk)))]
      (is (= "derrota" (:fase raid)))
      (is (= 2 (count (:rewards last-atk))))
      (is (= 3 (get-in by-pid ["ash" :pe])))
      (is (= 3 (get-in by-pid ["misty" :pe])))
      (is (= 0 (get-in by-pid ["ash" :moedas])))
      (is (= 0 (get-in by-pid ["misty" :moedas])))
      (is (= 3 (long (:pe-raids ash))))
      (is (= 3 (long (:pe-raids misty))))
      (is (= 0 (long (:coins ash 0))))
      (is (= 0 (long (:coins misty 0)))))))

(deftest vitoria-preserva-recompensas-anteriores
  (let [{:keys [s]} (fresh! "win-reg")
        chat "c-win" rid "raid-win"]
    (boot! s chat "ash" mon-vitoria)
    (boot! s chat "misty" mon-vitoria)
    (open! s chat rid chefe-fraco)
    (join-start! s chat rid ["ash" "misty"])
    (let [{:keys [raid last-atk]} (fight-until! s chat rid #{"vitoria" "derrota"})
          ash (ports/get-save s chat "ash" "a")]
      (is (= "vitoria" (:fase raid)))
      (is (true? (:venceu last-atk)))
      (is (every? #(and (= 50 (:moedas %)) (= 6 (:pe %)) (= 6 (:xp-pokemon %)))
                  (:rewards last-atk)))
      (is (= 50 (long (:coins ash))))
      (is (= 6 (long (:pe-raids ash)))))))

;;;; Abandono / lobby / timeout — sem recompensa

(deftest abandono-em-combate-sem-recompensa
  (let [{:keys [s]} (fresh! "abandon")
        chat "c-ab" rid "raid-ab"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (open! s chat rid chefe-tanque)
    (join-start! s chat rid ["ash" "misty"])
    ;; Um golpe para gerar dano, depois !pk sair (leave-battle) → abandonada
    (let [r0 (raid-app/get-raid s rid)
          pid (:vez r0)]
      (tx/raid-attack!
       s {:chat-id chat :player-id pid :save-id "a"
          :fence-generation (fence s chat pid)
          :operation-id "atk-once"
          :expected-turn-index (:turn-index r0)
          :move-index 0 :agora-ms 10}))
    (tx/leave-battle!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "leave-a" :agora-ms 20})
    (tx/leave-battle!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "leave-m" :agora-ms 21})
    (let [r (raid-app/get-raid s rid)
          ash (ports/get-save s chat "ash" "a")]
      (is (= "abandonada" (:fase r)))
      (is (false? (boolean (:rewards-paid? r))))
      (is (= 0 (long (:pe-raids ash 0))))
      (is (= 0 (long (:coins ash 0)))))))

(deftest lobby-sem-combate-sem-recompensa
  (let [{:keys [s]} (fresh! "lobby")
        chat "c-lob" rid "raid-lob"]
    (boot! s chat "ash")
    (open! s chat rid chefe-tanque)
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "j-lob" :collection-index 0 :agora-ms 2})
    (tx/cancel-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "cancel-lob" :agora-ms 3})
    (let [ash (ports/get-save s chat "ash" "a")]
      (is (= 0 (long (:pe-raids ash 0))))
      (is (= 0 (long (:coins ash 0)))))))

(deftest timeout-idle-sem-recompensa
  (let [{:keys [s conn]} (fresh! "idle-to")
        chat "c-idle" rid "raid-idle"]
    (boot! s chat "ash")
    (open! s chat rid chefe-tanque)
    (join-start! s chat rid ["ash"])
    (let [r0 (raid-app/get-raid s rid)
          agora (long (:last-activity-at r0
                                         (or (:idle-expires-at r0) 10000)))
          ;; Um ataque para ter dano (elegível se fosse derrota)
          _ (tx/raid-attack!
             s {:chat-id chat :player-id "ash" :save-id "a"
                :fence-generation (fence s chat "ash")
                :operation-id "atk-idle"
                :expected-turn-index (:turn-index (raid-app/get-raid s rid))
                :move-index 0 :agora-ms (+ agora 1)})
          r1 (raid-app/get-raid s rid)
          expires (long (:idle-expires-at r1))
          built (idle-store/build-expire-raid-tx (d/db conn) rid (+ expires 1))]
      (is (seq (:tx built)))
      (d/transact conn {:tx-data (:tx built)})
      (let [r (raid-app/get-raid s rid)
            ash (ports/get-save s chat "ash" "a")]
        (is (= "idle-timeout" (str (:fase r))))
        (is (false? (boolean (:rewards-paid? r))))
        (is (= 0 (long (:pe-raids ash 0))))))))

;;;; Idempotência / concorrência / restart

(deftest retry-finalizacao-nao-duplica
  (let [{:keys [s]} (fresh! "retry")
        chat "c-retry" rid "raid-retry"]
    (boot! s chat "ash")
    (open! s chat rid chefe-tanque)
    (join-start! s chat rid ["ash"])
    (let [{:keys [raid last-atk kill-req]} (fight-until! s chat rid #{"derrota"})
          pe0 (long (:pe-raids (ports/get-save s chat "ash" "a")))
          dup (tx/raid-attack! s kill-req)
          pe1 (long (:pe-raids (ports/get-save s chat "ash" "a")))]
      (is (= "derrota" (:fase raid)))
      (is (true? (:duplicate dup)))
      (is (= pe0 pe1 3))
      (is (= 3 (:pe (first (:rewards last-atk))))))))

(deftest concorrencia-dois-ataques-finais
  (let [{:keys [s]} (fresh! "race")
        chat "c-race" rid "raid-race"]
    (boot! s chat "ash")
    (open! s chat rid chefe-tanque)
    (join-start! s chat rid ["ash"])
    ;; Deixa o mon com HP exatamente no limiar do próximo KO (revide=2 → hp 2).
    (loop [n 0]
      (let [r (raid-app/get-raid s rid)
            hp (long (get-in r [:participantes "ash" :hp] 0))]
        (when (and (< n 40) (= "combate" (str (:fase r))) (> hp 2))
          (tx/raid-attack!
           s {:chat-id chat :player-id "ash" :save-id "a"
              :fence-generation (fence s chat "ash")
              :operation-id (str "pre-" n)
              :expected-turn-index (:turn-index r)
              :move-index 0 :agora-ms (+ 10 n)})
          (recur (inc n)))))
    (let [r (raid-app/get-raid s rid)
          _ (is (= "combate" (str (:fase r))))
          _ (is (= 2 (long (get-in r [:participantes "ash" :hp]))))
          turn (:turn-index r)
          req-a {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "fin-a"
                 :expected-turn-index turn :move-index 0 :agora-ms 900}
          req-b {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "fin-b"
                 :expected-turn-index turn :move-index 0 :agora-ms 901}
          f1 (future (try (tx/raid-attack! s req-a) (catch Exception e e)))
          f2 (future (try (tx/raid-attack! s req-b) (catch Exception e e)))
          r1 @f1
          r2 @f2
          ok (filter #(and (map? %) (not (instance? Exception %))) [r1 r2])
          errs (filter #(instance? Exception %) [r1 r2])
          ash (ports/get-save s chat "ash" "a")
          final (raid-app/get-raid s rid)]
      (is (= 1 (count ok)) "só um ataque final confirma")
      (is (seq errs))
      (is (= "derrota" (:fase final)))
      (is (true? (:rewards-paid? final)))
      (is (= 3 (long (:pe-raids ash)))))))

(deftest restart-apos-derrota-nao-reaplica
  (let [{:keys [s conn]} (fresh! "restart")
        chat "c-rs" rid "raid-rs"]
    (boot! s chat "ash")
    (open! s chat rid chefe-tanque)
    (join-start! s chat rid ["ash"])
    (fight-until! s chat rid #{"derrota"})
    (let [pe0 (long (:pe-raids (ports/get-save s chat "ash" "a")))
          ;; "Restart": novo store sobre o mesmo conn
          s2 (store/make-store conn)
          r (raid-app/get-raid s2 rid)
          ash (ports/get-save s2 chat "ash" "a")]
      (is (= "derrota" (:fase r)))
      (is (true? (:rewards-paid? r)))
      (is (= pe0 (long (:pe-raids ash)) 3)))))

;;;; Progressão nível

(deftest pokemon-sobe-nivel-na-derrota
  (let [{:keys [s]} (fresh! "poke-lvl")
        chat "c-pl" rid "raid-pl"]
    (boot! s chat "ash" mon-quase-nivel)
    (let [antes (first (ports/list-collection s chat "ash" "a"))]
      (is (= 5 (long (:nivel antes))))
      (is (= 7 (long (:xp-desde-nivel antes)))))
    (open! s chat rid chefe-tanque)
    (join-start! s chat rid ["ash"])
    (fight-until! s chat rid #{"derrota"})
    (let [depois (first (ports/list-collection s chat "ash" "a"))]
      (is (= 6 (long (:nivel depois))) "7+3 XP → sobe (xp-por-nivel=9)")
      (is (= 1 (long (:xp-desde-nivel depois)))))))

(deftest treinador-sobe-nivel-na-derrota
  (let [{:keys [s conn]} (fresh! "tr-lvl")
        chat "c-tl" rid "raid-tl"]
    (boot! s chat "ash")
    ;; PE parcial: 4 → nível 1 (falta 1 para 5). Derrota +3 → total 7 → nível 2.
    (let [sk (id/save-key chat "ash" "a")
          e (d/pull (d/db conn) '[:save/revision] [:save/key sk])
          rev (long (:save/revision e 0))]
      (d/transact conn {:tx-data [[:db/add [:save/key sk] :save/pe-raids 4]
                                  [:db/cas [:save/key sk] :save/revision rev (inc rev)]]}))
    (let [antes (ports/get-save s chat "ash" "a")
          prog0 (xp/progresso-treinador (xp/xp-treinador-total antes))]
      (is (= 1 (:nivel prog0))))
    (open! s chat rid chefe-tanque)
    (join-start! s chat rid ["ash"])
    (fight-until! s chat rid #{"derrota"})
    (let [depois (ports/get-save s chat "ash" "a")
          prog (xp/progresso-treinador (xp/xp-treinador-total depois))]
      (is (= 7 (long (:pe-raids depois))))
      (is (= 2 (:nivel prog))))))

;;;; Economia / saves especiais

(deftest unknown-pending-derrota-sem-moedas
  (let [{:keys [s conn]} (fresh! "up-def")
        chat "c-up" rid "raid-up"]
    (boot! s chat "ash")
    (strip-operational! conn chat ["ash"])
    (open! s chat rid chefe-tanque)
    (join-start! s chat rid ["ash"])
    (let [{:keys [last-atk]} (fight-until! s chat rid #{"derrota"})
          ash (ports/get-save s chat "ash" "a")
          raw (d/pull (d/db conn) '[*] [:save/key (id/save-key chat "ash" "a")])]
      (is (= 0 (:moedas (first (:rewards last-atk)))))
      (is (= 3 (long (:pe-raids ash))))
      (is (= "unknown-pending" (:save/economy-status raw)))
      (is (= 999 (long (:save/coins raw)))
          "histórico unknown-pending intocado")
      (is (nil? (:save/coins-operational raw))))))

(deftest recovery-v1-isolado-na-derrota
  (let [{:keys [s]} (fresh! "rec-def")
        chat "c-rec"]
    (boot! s chat "ash")
    (trainer/create-save! s chat "ash" "recovery-v1")
    (let [col-r0 (count (ports/list-collection s chat "ash" "recovery-v1"))
          pe-r0 (long (:pe-raids (ports/get-save s chat "ash" "recovery-v1") 0))]
      (open! s chat "raid-rec" chefe-tanque)
      (join-start! s chat "raid-rec" ["ash"])
      (fight-until! s chat "raid-rec" #{"derrota"})
      (is (= 3 (long (:pe-raids (ports/get-save s chat "ash" "a")))))
      (is (= col-r0 (count (ports/list-collection s chat "ash" "recovery-v1"))))
      (is (= pe-r0 (long (:pe-raids (ports/get-save s chat "ash" "recovery-v1") 0)))))))
