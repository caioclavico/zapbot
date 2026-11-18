(ns pokemon.domain.move-effects
  "Efeitos de combate por slug. Enriquecem o golpe em memória, sem gravar o save."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pokemon.domain.json-min :as json]
            [pokemon.domain.move-names :as nomes]))

(def catalogo
  (json/read-str (slurp (io/resource "move-effects.json"))))

(def mecanicas
  "Famílias geradas da PokéAPI. Não substitui estágios já aprovados em `catalogo`."
  (json/read-str (slurp (io/resource "move-mechanics.json"))))

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

(defn- por-mecanica [slug]
  (get mecanicas (str slug)))

(defn- aplicar-mecanica
  "Preenche só o que o catálogo aprovado não definiu."
  [golpe mec]
  (let [extra (alteracoes-de {"alteracoes" (get mec "alteracoesExtra")})]
    (cond-> golpe
      (and (nil? (:alvo golpe)) (get mec "alvo"))
      (assoc :alvo (keyword (get mec "alvo")))

      (and (empty? (or (:alteracoes golpe) [])) (seq (get mec "alteracoes")))
      (assoc :alteracoes (alteracoes-de mec))

      (seq extra)
      (update :alteracoes (fnil into []) extra)

      (and (nil? (:status-causado golpe)) (get mec "statusCausado"))
      (assoc :status-causado (keyword (get mec "statusCausado")))

      (and (nil? (:chance-status golpe)) (some? (get mec "chanceStatus")))
      (assoc :chance-status (long (get mec "chanceStatus")))

      (and (nil? (:cura golpe)) (some? (get mec "cura")))
      (assoc :cura (long (get mec "cura")))

      (and (nil? (:dreno golpe)) (some? (get mec "dreno")))
      (assoc :dreno (long (get mec "dreno")))

      (and (nil? (:recuo golpe)) (some? (get mec "recuo")))
      (assoc :recuo (long (get mec "recuo")))

      (some? (get mec "chanceAlteracao"))
      (assoc :chance-alteracao (long (get mec "chanceAlteracao")))

      (some? (get mec "flinch"))
      (assoc :flinch (long (get mec "flinch")))

      (some? (get mec "minAcertos"))
      (assoc :min-acertos (long (get mec "minAcertos")))

      (some? (get mec "maxAcertos"))
      (assoc :max-acertos (long (get mec "maxAcertos")))

      (get mec "familia")
      (assoc :familia (keyword (get mec "familia")))

      (true? (get mec "soPrimeiroTurno"))
      (assoc :so-primeiro-turno true)

      (true? (get mec "atravessaProtecao"))
      (assoc :atravessa-protecao true)

      (true? (get mec "ignoraPrecisao"))
      (assoc :ignora-precisao true)

      (true? (get mec "invulneravel"))
      (assoc :invulneravel true)

      (some? (get mec "turnosMin"))
      (assoc :turnos-min (long (get mec "turnosMin")))

      (some? (get mec "turnosMax"))
      (assoc :turnos-max (long (get mec "turnosMax")))

      (= "veneno-grave" (get mec "familia"))
      (assoc :status-causado :envenenado-grave
             :chance-status (or (:chance-status golpe)
                                (when (some? (get mec "chanceStatus"))
                                  (long (get mec "chanceStatus")))
                                100)))))

(defn enriquecer
  "Completa alvo, estágios, status, cura, dreno, recuo e família a partir do slug.
  Campos de dano já persistidos (poder, tipo, classe) permanecem.
  Estágios de `move-effects.json` vencem os da PokéAPI."
  [golpe]
  (let [slug (or (when-let [s (:slug golpe)]
                   (when (contains? nomes/nomes (str s)) (str s)))
                 (:slug (nomes/resolver (or (:nome-exibicao golpe)
                                            (get golpe "nome-exibicao")
                                            (:nome golpe)
                                            ""))))
        fx (por-slug slug)
        base (if-not fx
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
                 (true? (get fx "autodesmaio")) (assoc :autodesmaio true)))]
    (if-let [mec (por-mecanica slug)]
      (aplicar-mecanica base mec)
      base)))

(def atributo-pt
  {:ataque "Ataque"
   :defesa "Defesa"
   :atq-esp "Ataque Especial"
   :def-esp "Defesa Especial"
   :veloc "Velocidade"
   :precisao "Precisão"
   :evasao "Evasão"})

(def status-pt
  {:queimado "queimadura"
   :envenenado "envenenamento"
   :envenenado-grave "envenenamento grave"
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
      (if (#{:precisao :evasao} atributo)
        (str "🎯 A " (str/lower-case nome) " "
             (if (pos? delta) "aumentou" "caiu")
             " " (Math/abs (long delta))
             (if (= 1 (Math/abs (long delta))) " estágio!" " estágios!"))
        (str "🛡️ " nome " de *" quem "* "
             (if (pos? delta) "aumentou" "caiu")
             " " (Math/abs (long delta))
             (if (= 1 (Math/abs (long delta))) " estágio." " estágios."))))))

(defn linha-status
  [status-aplicado nome-def]
  (when status-aplicado
    (str "✨ *" nome-def "* sofreu " (get status-pt status-aplicado (name status-aplicado)) ".")))

(defn linha-cura
  [cura nome-atk]
  (when (and cura (pos? (long cura)))
    (str "💚 *" nome-atk "* recuperou " cura " de HP.")))
