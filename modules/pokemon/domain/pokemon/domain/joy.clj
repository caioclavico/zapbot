(ns pokemon.domain.joy
  "Centro Pokémon / Enfermeira Joy — puro. Espelha treinador.cljs."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]))

(def tempo-tratamento-minutos 30)
(def tempo-tratamento-ms (* tempo-tratamento-minutos 60 1000))
(def tempo-tratamento-padrao-segundos 1800)

(defn environment
  "Indireção para a configuração do processo JVM ser testável."
  [name]
  (System/getenv name))

(defn tempo-tratamento-segundos
  "Apenas novos tratamentos usam este valor. Zero é permitido somente em
   staging; produção preserva 30 minutos para entradas inválidas ou inseguras."
  []
  (let [raw (some-> (environment "POKEMON_JOY_HEAL_SECONDS") str)
        seconds (try (Long/parseLong raw) (catch Exception _ tempo-tratamento-padrao-segundos))
        staging? (= "staging" (some-> (environment "APP_ENV") str/lower-case str/trim))]
    (cond
      (neg? seconds) tempo-tratamento-padrao-segundos
      (and (zero? seconds) (not staging?)) tempo-tratamento-padrao-segundos
      :else seconds)))

(defn tempo-tratamento-atual-ms
  []
  (* 1000 (tempo-tratamento-segundos)))

(defn ferido?
  [registro]
  (or (< (long (or (:hp-atual registro) (get registro "hp-atual") 0))
         (long (or (:hp registro) (get registro "hp") 0)))
      (some? (or (:status registro) (get registro "status")))))

(defn enviar
  "Remove o índice ferido da coleção e cria entrada de enfermaria.
  Retorna {:colecao :enfermaria :registro}."
  [colecao enfermaria idx agora]
  (when-not (and (integer? idx) (<= 0 idx (dec (count colecao))))
    (throw (err/error :invalid_joy_index {:idx idx})))
  (let [reg (nth colecao idx)]
    (when-not (ferido? reg)
      (throw (err/error :pokemon_healthy {:idx idx})))
    (let [colecao' (vec (concat (subvec (vec colecao) 0 idx)
                                (subvec (vec colecao) (inc idx))))
          entrada {:bed-id (str "bed-" (or (:id-pokemon reg) idx) "-" agora)
                   :pokemon reg
                   ;; `pronto-em` é canônico e imutável: trocar env depois não
                   ;; altera leitos já persistidos no Datomic.
                   :pronto-em (+ (long agora) (tempo-tratamento-atual-ms))}]
      {:colecao colecao'
       :enfermaria (conj (vec enfermaria) entrada)
       :registro reg
       :entrada entrada})))

(defn curados
  "Particiona enfermaria em prontos vs ainda em tratamento."
  [enfermaria agora]
  (let [agora (long agora)
        prontos (filterv #(<= (long (:pronto-em % 0)) agora) enfermaria)
        restante (filterv #(> (long (:pronto-em % 0)) agora) enfermaria)
        registros (mapv (fn [e]
                          (-> (:pokemon e)
                              (assoc :hp-atual (or (:hp (:pokemon e))
                                                   (get (:pokemon e) "hp")
                                                   0)
                                     :status nil)))
                        prontos)]
    {:prontos prontos
     :restante restante
     :registros registros}))

(defn em-tratamento
  [enfermaria]
  (vec enfermaria))
