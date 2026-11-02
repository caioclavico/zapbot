(ns pokemon.tests.raid-auto-readiness-fase127-test
  "Readiness 12.7 — ciclo scheduler, dificuldade, anti-repeat, WA copy.
  Sem ativar produção."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.raid-auto :as domain]
            [pokemon.domain.raid-boss :as rb]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-auto-r127")
(def ^:private storage-rel ".datomic-local/raid-auto-r127")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      [s conn])))

(defn- reopen!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(def ^:private mon
  {:nome "Snorlax" :hp 200 :ataque 90 :defesa 55 :atq-esp 55 :def-esp 55 :veloc 40
   :tipos ["normal"] :nivel 8 :raridade "raro"
   :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 80 :classe :fisico}]})

(def ^:private mon-forte
  {:nome "Dragonite" :hp 220 :ataque 120 :defesa 70 :atq-esp 90 :def-esp 70 :veloc 80
   :tipos ["dragon" "flying"] :nivel 22 :raridade "raro"
   :golpes [{:nome-exibicao "Outrage" :tipo "dragon" :poder 90 :classe :fisico}]})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot!
  ([s chat player] (boot! s chat player mon))
  ([s chat player poke]
   (trainer/create-trainer! s {:chat-id chat :player-id player})
   (collection/adicionar-pokemon! s chat player poke 200 nil)
   (reward-app/admin-seed-reward!
    s {:operation-id (str "seed-poke-" chat "-" player)
       :chat-id chat :player-id player :save-id "a"
       :item {:item-id "pokebola" :qty 5}})))

(defn- enable!
  [s chat agora]
  (raid-auto/upsert-schedule!
   s {:chat-id chat :enabled true :interval-ms 3600000
      :next-at agora :ginasio-index 0 :agora-ms agora}))

(defn- fight-to-end!
  [s chat players agora]
  (let [hp0 (long (:hp-chefe (raid-app/active-raid s chat) 0))]
    (loop [t 0 agora agora]
      (let [r (raid-app/active-raid s chat)]
        (cond
          (>= t 400)
          {:turns t :fase (:fase r) :hp-inicial hp0 :timeout? true}

          (contains? #{"vitoria" "derrota" "abandonada"} (str (:fase r)))
          {:turns t :fase (:fase r) :hp-inicial hp0 :timeout? false}

          :else
          (let [pid (:vez r)]
            (tx/raid-attack!
             s {:chat-id chat :player-id pid :save-id "a"
                :fence-generation (fence s chat pid)
                :operation-id (str "atk-" t "-" pid)
                :expected-turn-index (:turn-index r)
                :move-index 0 :agora-ms agora})
            (recur (inc t) (inc agora))))))))

(defn- sample-from-category
  [categoria n seed nivel]
  (let [pool (vec (filter #(= categoria (:raridade %)) (rb/eligible-species)))
        r (java.util.Random. (long seed))]
    (is (seq pool) (str "pool vazio: " categoria))
    (mapv (fn [_]
            (rb/materialize-boss (nth pool (.nextInt r (count pool))) nivel))
          (range n))))

;;;; 1. Ciclo completo

(deftest ciclo-completo-spawn-lobby-combate-vitoria-captura
  ;; Força categoria raro para o ciclo ser vencível por 2 mons fortes.
  (let [[s _] (fresh! "r127-cycle")
        chat "c-cycle"
        agora 10000
        select-orig rb/select-boss-for-ginasio]
    (boot! s chat "ash" mon-forte)
    (boot! s chat "misty" mon-forte)
    ;; ginasio-index 1 = água (nv.20 / bronze) — compatível com mon-forte nv.22
    (raid-auto/upsert-schedule!
     s {:chat-id chat :enabled true :interval-ms 3600000
        :next-at agora :ginasio-index 1 :agora-ms agora})
    (with-redefs [rb/select-boss-for-ginasio
                  (fn [ginasio-id opts]
                    (select-orig ginasio-id
                                 (assoc opts
                                        :rng (constantly 0.05)
                                        :category-weights {"raro" 1.0})))]
      (let [tick1 (raid-auto/tick! s {:agora-ms agora :global-enabled? true :chat-id chat})
            raid (raid-app/active-raid s chat)
            opened (first (raid-auto/pending-events s {:limit 5}))]
        (is (= :spawn (:acao (first (:spawned tick1)))))
        (is (= "inscricoes" (:fase raid)))
        (is (= "auto" (:origem raid)))
        (is (string? (get-in raid [:chefe :species])))
        (is (= "raro" (get-in raid [:chefe :raridade])))
        (is (number? (get-in raid [:chefe :fator-dificuldade])))
        (is (re-find #"RAID" (:texto opened)))
        (is (re-find #"raide entrar auto" (:texto opened)))
        (is (re-find #"5 minutos" (:texto opened)))
        (is (re-find #"iniciar" (:texto opened)))
        (is (= 1 (:min-jogadores raid)))
        (is (= 5 (:max-jogadores raid)))
        (is (= (+ agora raid/duracao-inscricoes) (:expira raid)))
        (is (not (re-find #"@[0-9]+@(lid|c\.us)" (:texto opened))))
        (raid-auto/ack-event! s (:id opened) (inc agora))

        (tx/join-raid!
         s {:chat-id chat :player-id "ash" :save-id "a"
            :fence-generation (fence s chat "ash")
            :operation-id "j-ash" :auto? true :nome "Ash" :agora-ms (+ agora 1)})
        (tx/join-raid!
         s {:chat-id chat :player-id "misty" :save-id "a"
            :fence-generation (fence s chat "misty")
            :operation-id "j-misty" :auto? true :nome "Misty" :agora-ms (+ agora 2)})

        (let [exp (+ agora raid/duracao-inscricoes)
              tick2 (raid-auto/tick! s {:agora-ms exp :global-enabled? true :chat-id chat})
              active (raid-app/active-raid s chat)
              started (some #(when (= "raid-started" (:kind %)) %)
                            (raid-auto/pending-events s {:limit 10}))]
          (is (some #(= :combate (:acao %)) (:expired tick2)))
          (is (= "combate" (:fase active)))
          (is (some? started))
          (is (re-find #"Raide iniciada automaticamente" (:texto started)))
          (is (re-find #"Chefe HP:" (:texto started)))
          (raid-auto/ack-event! s (:id started) (inc exp))

          (let [fight (fight-to-end! s chat ["ash" "misty"] (+ exp 10))]
            (is (= "vitoria" (:fase fight)) (str "fight=" fight " boss=" (:chefe raid)))
            (is (false? (:timeout? fight)))
            (let [won (raid-app/active-raid s chat)
                  cap (tx/raid-capture!
                       s {:chat-id chat :player-id "ash" :save-id "a"
                          :fence-generation (fence s chat "ash")
                          :operation-id "cap-ash" :bola "pokebola"
                          :roll 0 :agora-ms (+ exp 20)})]
              (is (= "vitoria" (:fase won)))
              (is (true? (:rewards-paid? won)))
              (is (true? (:capturou cap))))))))))

;;;; 2–4. Dificuldade / solo vs coop

(deftest dificuldade-escala-por-categoria
  (let [raros (sample-from-category "raro" 16 1 20)
        lends (sample-from-category "lendario" 16 2 20)
        mitos (sample-from-category "mitico" 12 3 20)
        avg (fn [xs] (/ (double (reduce + xs)) (count xs)))
        f-raro (avg (map :fator-dificuldade raros))
        f-lend (avg (map :fator-dificuldade lends))
        f-mito (avg (map :fator-dificuldade mitos))]
    (is (every? #(= "raro" (:raridade %)) raros))
    (is (every? #(= "lendario" (:raridade %)) lends))
    (is (every? #(= "mitico" (:raridade %)) mitos))
    (is (< f-raro f-lend))
    (is (< f-raro f-mito))
    (is (<= rb/fator-hp-min f-raro (+ rb/fator-hp-max 1e-6)))
    (is (<= rb/fator-hp-min f-mito (+ rb/fator-hp-max 1e-6)))
    (is (every? #(<= rb/fator-hp-min (+ % 0.0) (+ rb/fator-hp-max 1e-6))
                (map :fator-dificuldade (concat raros lends mitos))))
    (is (> (count (set (map :fator-dificuldade raros))) 1))
    (is (> (count (set (map :fator-dificuldade lends))) 1))))

(defn- turns-to-win-solo
  [chefe player-mon]
  (let [agora 1000
        raid0 (raid/nova-raid
               {:raid-id "solo" :chat-id "c" :ginasio "agua"
                :liga "bronze" :chefe chefe :agora agora :criador "p"
                :min-jogadores 1})
        joined (raid/entrar raid0 {:player-id "p" :nome "P" :save-id "a"
                                   :fence-generation 0 :pokemon player-mon
                                   :collection-index 0} agora)
        raid (raid/iniciar joined "p" agora)]
    (loop [r raid t 0]
      (cond
        (>= t 250) {:turns t :fase (:fase r) :hp-max (:hp-max r) :ok? false}
        (= "vitoria" (str (:fase r))) {:turns t :fase "vitoria" :hp-max (:hp-max r) :ok? true}
        (= "derrota" (str (:fase r))) {:turns t :fase "derrota" :hp-max (:hp-max r) :ok? false}
        :else
        (let [res (raid/atacar r "p" 0 (+ agora t))]
          (recur (:raid res) (inc t)))))))

(defn- turns-to-win-coop
  [chefe n-players]
  (let [agora 1000
        players (mapv #(str "p" %) (range n-players))
        raid0 (raid/nova-raid
               {:raid-id "coop" :chat-id "c" :ginasio "fogo"
                :liga "bronze" :chefe chefe :agora agora
                :criador (first players)
                :min-jogadores n-players})
        joined (reduce
                (fn [r pid]
                  (raid/entrar r {:player-id pid :nome pid :save-id "a"
                                  :fence-generation 0 :pokemon mon-forte
                                  :collection-index 0} agora))
                raid0
                players)
        raid (raid/iniciar joined (first players) agora)
        order (cycle players)]
    (loop [r raid t 0 players order]
      (cond
        (>= t 500) {:turns t :ok? false :hp-max (:hp-max r) :fase (:fase r)}
        (= "vitoria" (str (:fase r))) {:turns t :ok? true :hp-max (:hp-max r) :fase "vitoria"}
        (= "derrota" (str (:fase r))) {:turns t :ok? false :hp-max (:hp-max r) :fase "derrota"}
        :else
        (let [pid (:vez r)
              res (raid/atacar r pid 0 (+ agora t))]
          (recur (:raid res) (inc t) (rest players)))))))

(defn- pick-extreme
  "Menor/maior BST da categoria (atributos reais → dificuldade).
  Ignora Shedinja (HP patológico) na ponta fraca."
  [categoria ascending? nivel]
  (let [pool (->> (rb/eligible-species)
                  (filter #(= categoria (:raridade %)))
                  (remove #(= "shedinja" (:species %))))
        ranked (sort-by (fn [row]
                          (+ (:hp row) (:ataque row) (:defesa row)
                             (:atq-esp row) (:def-esp row) (:veloc row)))
                        pool)
        row (if ascending? (first ranked) (last ranked))]
    (rb/materialize-boss row nivel)))

(def ^:private mon-medio
  "Combatente intermediário — sozinho sofre em míticos extremos."
  {:nome "Gabite" :hp 180 :ataque 70 :defesa 55 :atq-esp 50 :def-esp 50 :veloc 70
   :tipos ["dragon" "ground"] :nivel 22 :raridade "raro"
   :golpes [{:nome-exibicao "Dragon Claw" :tipo "dragon" :poder 65 :classe :fisico}]})

(deftest solo-vence-raro-mitico-incentiva-coop
  (let [raro (pick-extreme "raro" true 20)
        mito (pick-extreme "mitico" false 20)
        lend (pick-extreme "lendario" false 20)
        solo-r-forte (turns-to-win-solo raro mon-forte)
        solo-r-medio (turns-to-win-solo raro mon-medio)
        solo-m-medio (turns-to-win-solo mito mon-medio)
        solo-l-medio (turns-to-win-solo lend mon-medio)
        solo-m-forte (turns-to-win-solo mito mon-forte)
        coop3-l (turns-to-win-coop lend 3)
        coop5-m (turns-to-win-coop mito 5)]
    (is (< (:fator-dificuldade raro) (:fator-dificuldade mito)))
    (is (< (:fator-dificuldade raro) (:fator-dificuldade lend)))
    (is (true? (:ok? solo-r-forte)) "solo forte vence raro fraco")
    (is (< (:turns solo-r-forte) 40))
    (is (> (:hp-max solo-m-forte) (:hp-max solo-r-forte)))
    ;; Mon médio: raro pode ou não cair (revide); mítico/lendário extremos derrubam.
    (is (contains? #{"vitoria" "derrota"} (:fase solo-r-medio)))
    (is (= "derrota" (:fase solo-m-medio))
        (str "solo-m-medio=" solo-m-medio))
    (is (= "derrota" (:fase solo-l-medio))
        (str "solo-l-medio=" solo-l-medio))
    ;; Mon elite ainda pode soloar o mítico no limite — coop reduz risco (HP×n).
    (is (true? (:ok? solo-m-forte)))
    (is (true? (:ok? coop3-l)) (str "coop3-l=" coop3-l))
    (is (true? (:ok? coop5-m)) (str "coop5-m=" coop5-m))
    (is (> (:hp-max coop5-m) (:hp-max solo-m-forte)))))

;;;; 5. Histórico de 8

(deftest historico-oito-evita-repeticao-por-grupo
  (let [[s _] (fresh! "r127-hist")
        chat "c-hist"
        species (atom [])]
    (dotimes [i 12]
      (let [due (+ 1000 (* i 10000000))]
        (when-let [r (raid-app/active-raid s chat)]
          (when (= "inscricoes" (:fase r))
            (raid-auto/tick!
             s {:agora-ms (long (:expira r))
                :global-enabled? true :chat-id chat})))
        (raid-auto/upsert-schedule!
         s {:chat-id chat :enabled true :interval-ms 3600000
            :next-at due :ginasio-index (mod i 5) :agora-ms due})
        (let [tick (raid-auto/tick! s {:agora-ms due :global-enabled? true :chat-id chat})
              raid (raid-app/active-raid s chat)]
          (is (= :spawn (:acao (first (:spawned tick)))) (str "spawn i=" i))
          (swap! species conj (get-in raid [:chefe :species])))))
    (let [xs @species
          windows (map (fn [i] (subvec (vec xs) i (+ i 8)))
                       (range 0 (- (count xs) 7)))]
      (is (= 12 (count xs)))
      ;; Após a janela de 8, espécies antigas podem voltar — não é ban permanente.
      (is (>= (count (set xs)) 8))
      (doseq [w windows]
        (is (= 8 (count (set w))) (str "janela: " w))))))

;;;; 6. Reinício + idempotência

(deftest reinicio-e-duplicate-preservam-uma-raid
  (let [db "r127-restart"
        [s _] (fresh! db)
        chat "c-rst"]
    (enable! s chat 7000)
    (raid-auto/tick! s {:agora-ms 7000 :global-enabled? true :chat-id chat})
    (let [rid1 (:raid-id (raid-app/active-raid s chat))
          sp (get-in (raid-app/active-raid s chat) [:chefe :species])
          s2 (reopen! db)
          raid (raid-app/active-raid s2 chat)
          _ (raid-auto/upsert-schedule!
             s2 {:chat-id chat :enabled true
                 :interval-ms domain/min-interval-ms
                 :next-at 7000 :agora-ms 7000})
          dup (raid-auto/tick! s2 {:agora-ms 7000 :global-enabled? true :chat-id chat})]
      (is (= rid1 (:raid-id raid)))
      (is (= sp (get-in raid [:chefe :species])))
      (is (= "inscricoes" (:fase raid)))
      (is (true? (:duplicate (first (:spawned dup)))))
      (is (= 1 (count (raid-auto/pending-events s2 {:limit 10})))))))

;;;; 7. Mensagens WA

(deftest mensagens-auto-sem-ids-tecnicos-e-comando-entrar
  (doseq [ginasio ["pedra" "agua" "eletrico" "planta" "fogo"]]
    (let [boss (rb/select-boss-for-ginasio ginasio {:rng (constantly 0.2)})
          nome (get-in boss [:chefe :nome])
          opened (domain/format-opened
                  (assoc boss
                         :boss-categoria (:categoria boss)
                         :lobby (raid/lobby-params-for-categoria (:categoria boss))
                         :min-jogadores 2 :max-jogadores 5 :lobby-minutos 10))
          started (domain/format-started (assoc boss :hp-chefe 400 :hp-max 400))
          cancelled (domain/format-cancelled boss)]
      (is (re-find #"!pk raide entrar auto" opened))
      (is (re-find #"RAID" opened))
      (is (re-find #"iniciar" opened))
      (is (.contains ^String opened (str "*" nome "*")))
      (is (not (re-find #"@[0-9]+@(lid|c\.us)" opened)))
      (is (not (re-find #"player-id" opened)))
      (is (re-find #"Chefe HP: 400/400" started))
      (is (re-find #"poucos inscritos" cancelled)))))

;;;; 9. Seleção ampla

(deftest selecao-cobre-centenas-de-especies
  (let [sim (rb/simulate-distribution 2000 {:seed 99})]
    (is (> (:especies-distintas sim) 200))
    (is (false? (:especie-unica-dominante? sim)))
    (is (>= (count (:por-geracao sim)) 8))))
