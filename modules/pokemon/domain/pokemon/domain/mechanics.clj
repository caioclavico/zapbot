(ns pokemon.domain.mechanics
  "Famílias de efeito compartilhadas por caça, ginásio, PvP e NPC.
  O estado volátil fica na batalha em memória. Não grava save nem schema."
  (:require [pokemon.domain.rng :as rng]))

(defn tipos-de
  [pokemon]
  (set (map str (or (:tipos pokemon) (get pokemon "tipos") []))))

(defn imune-a-status?
  [status tipos]
  (let [st (keyword (if (keyword? status) (name status) (str status)))
        ts (set (map str tipos))]
    (case st
      :queimado (contains? ts "fire")
      :paralisado (contains? ts "electric")
      (:envenenado :envenenado-grave) (or (contains? ts "poison") (contains? ts "steel"))
      :congelado (contains? ts "ice")
      false)))

(defn chance-protecao
  "Primeiro uso acerta. Cada sucesso seguido divide a chance por 3."
  [seguidas]
  (if (zero? (long seguidas))
    100
    (max 1 (long (Math/round (/ 100.0 (Math/pow 3 (long seguidas))))))))

(defn rolar-acertos
  "2–5 usa a distribuição clássica (35/35/15/15). Outras faixas são uniformes."
  [min-acertos max-acertos rng]
  (let [lo (long min-acertos)
        hi (long max-acertos)]
    (if (and (= lo 2) (= hi 5))
      (let [r (rng/roll rng :multi-hit 100)]
        (cond
          (< r 35) 2
          (< r 70) 3
          (< r 85) 4
          :else 5))
      (+ lo (rng/roll rng :multi-hit (inc (- hi lo)))))))

(defn dano-veneno-grave
  [hp-max contador]
  (max 1 (long (quot (* (long hp-max) (max 1 (long contador))) 16))))

(defn dano-residual
  [hp-max]
  (max 1 (long (quot (long hp-max) 8))))

(defn- volatil [battle marca]
  (get-in battle [:volatil marca] {}))

(defn limpar-volatil
  [battle marca]
  (assoc-in battle [:volatil marca] {}))

(defn preparar-acao
  "Recarga, flinch, carga e golpe de primeiro turno.
  Devolve {:battle :golpe :pular? :carregar? :mensagem :alteracoes-carga}."
  [battle marca golpe rng]
  (let [v (volatil battle marca)
        turnos (long (:turnos-no-campo v 0))
        battle (assoc-in battle [:volatil marca :turnos-no-campo] (inc turnos))
        recarga? (:recarga? v)
        recuo? (:recuo? v)
        carga (:carga v)
        golpe (or golpe {})]
    (cond
      recarga?
      {:battle (update-in battle [:volatil marca] dissoc :recarga?)
       :golpe golpe
       :pular? true
       :mensagem "⏳ O Pokémon precisa recarregar e perdeu a ação!"}

      recuo?
      {:battle (update-in battle [:volatil marca] dissoc :recuo?)
       :golpe golpe
       :pular? true
       :mensagem "💥 O adversário recuou e perdeu a ação!"}

      (and (:so-primeiro-turno golpe) (pos? turnos))
      {:battle battle
       :golpe golpe
       :pular? true
       :mensagem "⚠️ Esse golpe só funciona no primeiro turno em campo!"}

      (and carga (= (:slug carga) (:slug golpe)))
      {:battle (update-in battle [:volatil marca] dissoc :carga :invulneravel?)
       :golpe (dissoc (:golpe carga) :alteracoes)
       :pular? false
       :mensagem nil}

      (= :preparacao (:familia golpe))
      {:battle (cond-> (assoc-in battle [:volatil marca :carga]
                                 {:slug (:slug golpe) :golpe golpe})
                 (:invulneravel golpe)
                 (assoc-in [:volatil marca :invulneravel?] true))
       :golpe golpe
       :pular? true
       :carregar? true
       :alteracoes-carga (when (= :proprio (:alvo golpe)) (:alteracoes golpe))
       :mensagem "✨ O Pokémon está se preparando!"}

      :else
      {:battle (if (= :protecao (:familia golpe))
                 battle
                 (assoc-in battle [:volatil marca :protecao-seguidas] 0))
       :golpe golpe
       :pular? false})))

(defn marcar-invulneravel
  [battle marca]
  (assoc-in battle [:volatil marca :invulneravel?] true))

(defn tentar-protecao
  [battle marca rng]
  (let [seguidas (long (get-in battle [:volatil marca :protecao-seguidas] 0))
        chance (chance-protecao seguidas)
        ok? (< (rng/roll rng :protecao 100) chance)]
    (if ok?
      {:battle (-> battle
                   (assoc-in [:volatil marca :protecao?] true)
                   (assoc-in [:volatil marca :protecao-seguidas] (inc seguidas)))
       :sucesso? true
       :mensagem "🛡️ Proteção ativada!"}
      {:battle (assoc-in battle [:volatil marca :protecao-seguidas] 0)
       :sucesso? false
       :mensagem "⚠️ A proteção falhou!"})))

(defn protecao-bloqueia?
  [battle def-marca golpe]
  (and (get-in battle [:volatil def-marca :protecao?])
       (not (:atravessa-protecao golpe))
       (not= :proprio (:alvo golpe))))

(defn consumir-protecao
  [battle marca]
  (if (get-in battle [:volatil marca :protecao?])
    (update-in battle [:volatil marca] dissoc :protecao?)
    battle))

(defn marcar-flinch
  [battle alvo golpe acertou? alvo-ja-agiu? rng]
  (if (and acertou?
           (not alvo-ja-agiu?)
           (:flinch golpe)
           (pos? (long (:flinch golpe)))
           (< (rng/roll rng :flinch 100) (long (:flinch golpe))))
    (assoc-in battle [:volatil alvo :recuo?] true)
    battle))

(defn agendar-recarga
  [battle marca golpe]
  (if (= :recarga (:familia golpe))
    (assoc-in battle [:volatil marca :recarga?] true)
    battle))

(defn aplicar-semente
  [battle alvo fonte tipos-alvo]
  (cond
    (contains? (set (map str tipos-alvo)) "grass")
    {:battle battle :aplicou? false :imune? true}

    (get-in battle [:volatil alvo :semente])
    {:battle battle :aplicou? false}

    :else
    {:battle (assoc-in battle [:volatil alvo :semente] fonte)
     :aplicou? true
     :mensagem "🌱 Semente Sanguessuga foi plantada!"}))

(defn aplicar-residual
  [battle alvo golpe rng]
  (let [lo (long (:turnos-min golpe 4))
        hi (long (:turnos-max golpe lo))
        n (if (>= hi lo)
            (+ lo (rng/roll rng :residual-turns (inc (- hi lo))))
            lo)]
    (assoc-in battle [:volatil alvo :residual] {:restante n})))

(defn tick-semente
  [battle marca]
  (let [fonte (get-in battle [:volatil marca :semente])
        hp (long (get-in battle [:hp marca] 0))
        max-hp (long (get-in battle [:pokemons marca :hp] 0))]
    (if (or (nil? fonte) (zero? hp) (zero? max-hp))
      {:battle battle :mensagens []}
      (let [dano (dano-residual max-hp)
            battle (update-in battle [:hp marca] #(max 0 (- % dano)))
            fonte-hp (long (get-in battle [:hp fonte] 0))
            fonte-max (long (get-in battle [:pokemons fonte :hp] 0))
            cura (if (pos? fonte-hp) (min (- fonte-max fonte-hp) dano) 0)
            battle (if (pos? cura)
                     (update-in battle [:hp fonte] #(min fonte-max (+ % cura)))
                     battle)]
        {:battle battle
         :mensagens [(str "🌱 Semente Sanguessuga drenou " dano " HP!")]}))))

(defn tick-residual
  [battle marca]
  (let [res (get-in battle [:volatil marca :residual])
        hp (long (get-in battle [:hp marca] 0))
        max-hp (long (get-in battle [:pokemons marca :hp] 0))]
    (if (or (nil? res) (zero? hp))
      {:battle battle :mensagens []}
      (let [dano (dano-residual max-hp)
            restante (dec (long (:restante res 1)))
            battle (-> battle
                       (update-in [:hp marca] #(max 0 (- % dano)))
                       (assoc-in [:volatil marca :residual]
                                 (when (pos? restante) {:restante restante})))]
        {:battle battle
         :mensagens [(str "🌀 A armadilha causou " dano " de dano!")]}))))

(defn tick-veneno-grave
  [battle marca]
  (let [hp (long (get-in battle [:hp marca] 0))
        max-hp (long (get-in battle [:pokemons marca :hp] 0))
        n (max 1 (long (get-in battle [:volatil marca :veneno-contador] 1)))]
    (if (or (zero? hp) (zero? max-hp))
      {:battle battle :dot 0 :mensagem nil}
      (let [dot (dano-veneno-grave max-hp n)]
        {:battle (-> battle
                     (update-in [:hp marca] #(max 0 (- % dot)))
                     (assoc-in [:volatil marca :veneno-contador] (inc n)))
         :dot dot
         :mensagem (str "☠️ O veneno grave causou " dot " de dano!")}))))

(defn iniciar-veneno-grave
  [battle marca]
  (assoc-in battle [:volatil marca :veneno-contador] 1))

(defn limpar-contador-veneno
  [battle marca]
  (update-in battle [:volatil marca] dissoc :veneno-contador))

(defn limpar-status-lado
  [battle marca alcance]
  (let [battle (-> battle
                   (assoc-in [:status marca] nil)
                   (limpar-contador-veneno marca))]
    (if (= alcance :equipe)
      (update-in battle [:reservas marca]
                 (fn [rs]
                   (when rs
                     (mapv (fn [e]
                             (if (and (map? e) (:pokemon e))
                               (update e :pokemon dissoc :status)
                               (dissoc e :status)))
                           rs))))
      battle)))

(defn limpar-estagios
  [battle]
  (-> battle
      (assoc-in [:estagios :x] {})
      (assoc-in [:estagios :o] {})))

(defn limpar-semente-e-armadilha
  [battle marca]
  (update-in battle [:volatil marca] dissoc :semente :residual))

(defn chance-de-alteracao?
  [golpe rng]
  (let [chance (:chance-alteracao golpe)]
    (or (nil? chance)
        (< (rng/roll rng :chance-alt 100) (long chance)))))
