(ns pokemon.domain.xp
  "Regras de XP/nível portadas de zapbot.pokemon.treinador (legado CLJS).

  Pokémon: xp-por-nivel=9, fator=1.03, nível máximo=100.
  Treinador: progresso com custos 5, 7, 9, 11… (progresso-treinador)."
  (:require [pokemon.domain.errors :as err]))

(def nivel-maximo 100)
(def fator-crescimento-por-nivel 1.03)
(def xp-por-nivel 9)
(def xp-por-vitoria 3)
(def xp-por-derrota 1)

(defn crescer
  "Espelha js/Math.round(* n 1.03)."
  [n]
  (Math/round (* (double n) fator-crescimento-por-nivel)))

(defn- subir-registro
  "Um nível: cresce stats; hp-atual sobe pelo incremento se > 0."
  [registro]
  (let [hp-antigo (long (get registro :hp (get registro "hp" 0)))
        hp-novo (crescer hp-antigo)
        incremento (- hp-novo hp-antigo)
        hp-atual (long (get registro :hp-atual (get registro "hp-atual" 0)))
        grow (fn [k]
               (let [v (long (get registro k (get registro (name k) 0)))]
                 (crescer v)))]
    (-> registro
        (assoc :nivel (inc (long (get registro :nivel (get registro "nivel" 1))))
               :hp hp-novo
               :hp-atual (if (pos? hp-atual) (+ hp-atual incremento) 0)
               :ataque (grow :ataque)
               :defesa (grow :defesa)
               :atq-esp (grow :atq-esp)
               :def-esp (grow :def-esp)
               :veloc (grow :veloc)))))

(defn normalizar-xp-registro
  "Converte excesso de XP em subidas (legado normalizar-xp-registro)."
  [registro]
  (let [nivel (long (get registro :nivel (get registro "nivel" 1)))
        xp (max 0 (long (get registro :xp-desde-nivel
                              (get registro "xp-desde-nivel"
                                   (* xp-por-vitoria
                                      (long (get registro :vitorias-desde-nivel
                                                 (get registro "vitorias-desde-nivel" 0))))))))
        subidas (min (long (Math/floor (/ (double xp) xp-por-nivel)))
                     (- nivel-maximo nivel))
        final (nth (iterate subir-registro
                            (assoc registro
                                   :nivel nivel
                                   :hp (long (get registro :hp (get registro "hp" 0)))
                                   :hp-atual (long (get registro :hp-atual (get registro "hp-atual" 0)))
                                   :ataque (long (get registro :ataque (get registro "ataque" 0)))
                                   :defesa (long (get registro :defesa (get registro "defesa" 0)))
                                   :atq-esp (long (get registro :atq-esp (get registro "atq-esp" 0)))
                                   :def-esp (long (get registro :def-esp (get registro "def-esp" 0)))
                                   :veloc (long (get registro :veloc (get registro "veloc" 0)))))
                   subidas)
        nivel-final (+ nivel subidas)]
    (-> final
        (assoc :xp-desde-nivel (if (= nivel-final nivel-maximo)
                                 0
                                 (- xp (* subidas xp-por-nivel))))
        (dissoc :vitorias-desde-nivel "vitorias-desde-nivel"))))

(defn aplicar-xp-pokemon
  "Concede `quantidade` de XP ao registro; retorna {:registro :subidas :nivel-final}."
  [registro quantidade]
  (let [nivel-atual (long (get registro :nivel (get registro "nivel" 1)))
        xp-anterior (long (get registro :xp-desde-nivel
                               (get registro "xp-desde-nivel"
                                    (* xp-por-vitoria
                                       (long (get registro :vitorias-desde-nivel
                                                  (get registro "vitorias-desde-nivel" 0)))))))
        xp-total (+ xp-anterior (max 0 (long quantidade)))
        subidas (if (< nivel-atual nivel-maximo)
                  (min (long (Math/floor (/ (double xp-total) xp-por-nivel)))
                       (- nivel-maximo nivel-atual))
                  0)
        amizade (min 255 (+ (long (get registro :amizade (get registro "amizade" 70))) 10))
        registro-novo (-> registro
                          (assoc :amizade amizade
                                 :xp-desde-nivel xp-total
                                 :nivel nivel-atual)
                          normalizar-xp-registro)]
    {:registro registro-novo
     :subidas subidas
     :nivel-final (long (:nivel registro-novo))
     :nome (get registro-novo :nome (get registro-novo "nome"))}))

(defn progresso-treinador
  "Distribui XP total entre níveis com custos 5, 7, 9, 11…"
  [xp]
  (loop [nivel 1 restante (max 0 (long xp)) necessario 5]
    (if (< restante necessario)
      {:nivel nivel :xp-atual restante :xp-necessario necessario}
      (recur (inc nivel) (- restante necessario) (+ necessario 2)))))

(defn xp-treinador-total
  "Soma dos componentes (legado xp-treinador), sem I/O."
  [{:keys [vitorias-treinador pe-ginasios pe-raids xp-insignias xp-missoes]
    :or {vitorias-treinador 0 pe-ginasios 0 pe-raids 0 xp-insignias 0 xp-missoes 0}}]
  (+ (long vitorias-treinador)
     (long pe-ginasios)
     (long pe-raids)
     (long xp-insignias)
     (long xp-missoes)))

(defn perfil-treinador
  [componentes]
  (let [xp (xp-treinador-total componentes)
        prog (progresso-treinador xp)]
    (merge prog {:xp xp
                 :vitorias-treinador (long (:vitorias-treinador componentes 0))
                 :pe-ginasios (long (:pe-ginasios componentes 0))
                 :pe-raids (long (:pe-raids componentes 0))
                 :xp-insignias (long (:xp-insignias componentes 0))
                 :xp-missoes (long (:xp-missoes componentes 0))})))

(defn status->str
  [status]
  (cond
    (nil? status) nil
    (keyword? status) (name status)
    (string? status) (when-not (empty? status) status)
    :else (throw (err/error :invalid_status {:status status}))))
