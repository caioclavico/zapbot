(ns pokemon.domain.status
  "Status de combate — impedimentos, DoT e contágio (legado core.cljs)."
  (:require [pokemon.domain.rng :as rng]))

(def chance-paralisia-trava 25)
(def chance-contagio 35)

(def tipo->status
  {"fire" :queimado "electric" :paralisado "poison" :envenenado})

(defn ->kw
  [status]
  (cond
    (nil? status) nil
    (keyword? status) status
    :else (keyword status)))

(defn impedimento-status
  "Espelha core/impedimento-status. RNG keys: :impede-para :cura-sono :cura-gelo
  :cura-confusao :auto-confusao (0–99)."
  [status rng]
  (let [status (->kw status)]
    (case status
      :paralisado
      (when (< (rng/roll rng :impede-para 100) chance-paralisia-trava)
        {:impedido? true :texto "está paralisado"})

      :adormecido
      (if (< (rng/roll rng :cura-sono 100) 35)
        {:curou? true :texto "acordou"}
        {:impedido? true :texto "está dormindo"})

      :congelado
      (if (< (rng/roll rng :cura-gelo 100) 25)
        {:curou? true :texto "descongelou"}
        {:impedido? true :texto "está congelado"})

      :confuso
      (cond
        (< (rng/roll rng :cura-confusao 100) 25) {:curou? true :texto "saiu da confusão"}
        (< (rng/roll rng :auto-confusao 100) 33) {:impedido? true :auto-dano? true :texto "se feriu na confusão"}
        :else nil)

      nil)))

(defn dano-por-status
  [status hp-maximo]
  (case (->kw status)
    :queimado (max 1 (quot (long hp-maximo) 16))
    :envenenado (max 1 (quot (long hp-maximo) 8))
    0))

(defn auto-dano-confusao
  [hp-maximo]
  (max 1 (quot (long hp-maximo) 8)))

(defn tentar-contagiar
  [tipo-golpe status-atual-defensor rng]
  (when (and (nil? (->kw status-atual-defensor))
             (< (rng/roll rng :contagio 100) chance-contagio))
    (get tipo->status tipo-golpe)))

(defn tentar-status-do-golpe
  [golpe status-atual rng]
  (let [status (->kw (:status-causado golpe))
        chance (or (:chance-status golpe) (when status 100) 0)]
    (when (and status (nil? (->kw status-atual))
               (< (rng/roll rng :status-golpe 100) (long chance)))
      status)))
