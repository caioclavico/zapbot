(ns pokemon.domain.move-effects
  "Efeitos de combate por slug. Enriquecem o golpe em memória, sem gravar o save."
  (:require [clojure.java.io :as io]
            [pokemon.domain.json-min :as json]
            [pokemon.domain.move-names :as nomes]))

(def catalogo
  (json/read-str (slurp (io/resource "move-effects.json"))))

(defn- kw-attr [a]
  (let [s (str (if (keyword? a) (name a) a))]
    (keyword s)))

(defn por-slug [slug]
  (get catalogo (str slug)))

(defn- alteracoes-de [fx]
  (mapv (fn [a]
          {:atributo (kw-attr (get a "atributo"))
           :estagios (long (get a "estagios"))})
        (or (get fx "alteracoes") [])))

(defn enriquecer
  "Completa alvo, estágios, status, cura, dreno e recuo a partir do slug.
  Campos de dano já persistidos (poder, tipo, classe) permanecem."
  [golpe]
  (let [slug (or (when-let [s (:slug golpe)]
                   (when (contains? nomes/nomes (str s)) (str s)))
                 (:slug (nomes/resolver (or (:nome-exibicao golpe)
                                            (get golpe "nome-exibicao")
                                            (:nome golpe)
                                            ""))))
        fx (por-slug slug)]
    (if-not fx
      (cond-> golpe slug (assoc :slug slug))
      (cond-> (assoc golpe :slug slug)
        (get fx "alvo") (assoc :alvo (keyword (get fx "alvo")))
        (seq (get fx "alteracoes")) (assoc :alteracoes (alteracoes-de fx))
        (get fx "statusCausado") (assoc :status-causado (keyword (get fx "statusCausado")))
        (some? (get fx "chanceStatus")) (assoc :chance-status (long (get fx "chanceStatus")))
        (some? (get fx "cura")) (assoc :cura (long (get fx "cura")))
        (some? (get fx "dreno")) (assoc :dreno (long (get fx "dreno")))
        (some? (get fx "recuo")) (assoc :recuo (long (get fx "recuo")))
        (some? (get fx "precisao")) (assoc :precisao (long (get fx "precisao")))
        (true? (get fx "autodesmaio")) (assoc :autodesmaio true)))))

(def atributo-pt
  {:ataque "Ataque"
   :defesa "Defesa"
   :atq-esp "Ataque Especial"
   :def-esp "Defesa Especial"
   :veloc "Velocidade"})

(def status-pt
  {:queimado "queimadura"
   :envenenado "envenenamento"
   :paralisado "paralisia"
   :adormecido "sono"
   :congelado "congelamento"
   :confuso "confusão"})

(defn linhas-estagios
  [aplicadas nome-atk nome-def alvo]
  (for [{:keys [atributo antes depois pedido]} aplicadas
        :let [delta (- (long depois) (long antes))
              nome (get atributo-pt atributo (name atributo))
              proprio? (not= :adversario alvo)
              quem (if proprio? nome-atk nome-def)]]
    (if (zero? delta)
      (str "⚠️ " nome " de *" quem "* não pode "
           (if (pos? (long pedido)) "aumentar" "diminuir") " mais.")
      (str "🛡️ " nome " de *" quem "* "
           (if (pos? delta) "aumentou" "caiu")
           " " (Math/abs (long delta))
           (if (= 1 (Math/abs (long delta))) " estágio." " estágios.")))))

(defn linha-status
  [status-aplicado nome-def]
  (when status-aplicado
    (str "✨ *" nome-def "* sofreu " (get status-pt status-aplicado (name status-aplicado)) ".")))

(defn linha-cura
  [cura nome-atk]
  (when (and cura (pos? (long cura)))
    (str "💚 *" nome-atk "* recuperou " cura " de HP.")))
