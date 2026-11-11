(ns pokemon.domain.xp
  "Regras de XP/nível.

  Pokémon (growth-v1): curvas oficiais PokéAPI via `pokemon.domain.growth`
  (XP acumulado + residual derivado). Legado residual/limiar 9 só para conversão.
  Treinador: curva progressiva PE 10 + 5 por nível; curva 5,7,9 preservada só
  para inicialização histórica. PE do treinador ≠ XP do Pokémon."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.growth :as growth]
            [pokemon.domain.species-hp :as species-hp]))

(def nivel-maximo growth/nivel-maximo)
(def fator-crescimento-por-nivel 1.03)
(def ^{:doc "Limiar residual legado (pré-growth-v1). Não usar para progressão nova."}
  xp-por-nivel growth/legacy-xp-por-nivel)
(def xp-por-vitoria 3)
(def xp-por-derrota 1)
(def ^{:doc "Escala explícita de balanceamento: multiplica ganhos de gameplay
  antes de aplicar às tabelas oficiais. 1 = unidade PokéAPI pura."}
  pokemon-xp-grant-scale 1)
(def trainer-xp-inicial-por-nivel 10)
(def trainer-xp-incremento-por-nivel 5)

(defn trainer-xp-necessario
  "PE necessário para subir do nível `nivel` do treinador, a partir da curva progressiva."
  [nivel]
  (+ trainer-xp-inicial-por-nivel
     (* trainer-xp-incremento-por-nivel (max 0 (dec (long nivel))))))

(defn crescer
  "Espelha js/Math.round(* n 1.03)."
  [n]
  (Math/round (* (double n) fator-crescimento-por-nivel)))

(defn- subir-registro
  "Um nível: cresce stats ofensivos/defensivos (*1.03).
  HP: se `hp-formula-version=species-hp-v1` e `hp-base` presente, recalcula
  canonicamente e preserva proporção; senão, legado (crescer + incremento)."
  [registro]
  (let [nivel-novo (inc (long (get registro :nivel (get registro "nivel" 1))))
        hp-antigo (long (get registro :hp (get registro "hp" 0)))
        hp-atual (long (get registro :hp-atual (get registro "hp-atual" 0)))
        base (or (get registro :hp-base) (get registro "hp-base"))
        versao (str (or (get registro :hp-formula-version)
                        (get registro "hp-formula-version")
                        ""))
        v1? (and (= versao species-hp/formula-version) (some? base))
        grow (fn [k]
               (let [v (long (get registro k (get registro (name k) 0)))]
                 (crescer v)))
        hp-fields
        (if v1?
          (species-hp/aplicar-hp-especie
           {:base-hp base :nivel nivel-novo
            :hp-atual hp-atual :hp-max-antigo hp-antigo
            :proporcional? true})
          (let [hp-novo (crescer hp-antigo)
                incremento (- hp-novo hp-antigo)]
            {:hp hp-novo
             :hp-atual (if (pos? hp-atual) (+ hp-atual incremento) 0)}))]
    (-> registro
        (assoc :nivel nivel-novo
               :ataque (grow :ataque)
               :defesa (grow :defesa)
               :atq-esp (grow :atq-esp)
               :def-esp (grow :def-esp)
               :veloc (grow :veloc))
        (merge hp-fields))))
(defn- aplicar-subidas-stats
  "Aplica `subidas` vezes a escala de stats 1.03 / HP species-v1."
  [registro subidas]
  (nth (iterate subir-registro registro) (max 0 (long subidas))))

(defn progresso-pokemon
  "Progresso de XP dentro do nível atual (canônico growth-v1).
  Não inventa XP ausente. Ver `growth/pokemon-progress`."
  [registro]
  (growth/pokemon-progress registro))

(defn aplicar-xp-pokemon
  "Concede XP (unidade oficial × `pokemon-xp-grant-scale`).
  Converte legado residual→growth-v1 na primeira aplicação.
  Retorna {:registro :subidas :nivel-final :nome ...}."
  [registro quantidade]
  (let [nivel-antes (long (get registro :nivel (get registro "nivel" 1)))
        scaled (long (Math/floor (* (double (max 0 (long quantidade)))
                                    (double pokemon-xp-grant-scale))))
        granted (growth/grant-pokemon-xp registro scaled)
        r0 (:registro granted)
        subidas (long (:subidas granted 0))
        amizade (min 255 (+ (long (get r0 :amizade (get r0 "amizade" 70))) 10))
        r1 (-> r0
               (assoc :amizade amizade
                      ;; stats sobem a partir do nível pré-ganho
                      :nivel nivel-antes)
               (dissoc :vitorias-desde-nivel "vitorias-desde-nivel"))
        r-stats (if (pos? subidas) (aplicar-subidas-stats r1 subidas) r1)
        r-final (assoc r-stats
                       :nivel (long (:nivel-final granted))
                       :xp-total (long (:xp-total r0))
                       :xp-desde-nivel (long (:xp-desde-nivel r0))
                       :xp (long (:xp-desde-nivel r0))
                       :xp-progress-version growth/progress-version
                       :growth-rate (:growth-rate r0))]
    {:registro r-final
     :subidas subidas
     :nivel-final (long (:nivel-final granted))
     :nome (get r-final :nome (get r-final "nome"))
     :xp-total (:xp-total r0)
     :growth-rate (:growth-rate r0)}))

(defn progresso-treinador-legado
  "Curva histórica 5, 7, 9… para preservar nível e PE residual de saves existentes."
  [xp]
  (loop [nivel 1 restante (max 0 (long xp)) necessario 5]
    (if (< restante necessario)
      {:nivel nivel :xp-atual restante :xp-necessario necessario}
      (recur (inc nivel) (- restante necessario) (+ necessario 2)))))

(defn progresso-treinador
  "Distribui PE acumulado pela curva nova; saves persistidos preservam seu nível anterior."
  [xp]
  (loop [nivel 1 restante (max 0 (long xp))]
    (let [necessario (trainer-xp-necessario nivel)]
      (if (< restante necessario)
        {:nivel nivel :xp-atual restante :xp-necessario necessario}
        (recur (inc nivel) (- restante necessario))))))

(defn avancar-nivel-treinador
  "Aplica PE a um nível/resíduo já estabelecidos. Retorna todos os níveis cruzados."
  [nivel-atual xp-residual pe]
  (loop [nivel (max 1 (long nivel-atual))
         restante (+ (max 0 (long xp-residual)) (max 0 (long pe)))
         subidas []]
    (let [necessario (trainer-xp-necessario nivel)]
      (if (< restante necessario)
        {:nivel nivel :xp-atual restante :xp-necessario necessario :subidas subidas}
        (recur (inc nivel) (- restante necessario) (conj subidas (inc nivel)))))))

(def catalogo-insignias
  "Espelha treinador/catalogo-insignias da master (PE único por marco)."
  [{:id "doador-pokemon" :nome "Doador de Pokémon" :requisito "Doar 1 Pokémon"
    :metric :doacoes :minimo 1 :xp-recompensa 3}
   {:id "doador-generoso" :nome "Doador Generoso" :requisito "Doar 10 Pokémon"
    :metric :doacoes :minimo 10 :xp-recompensa 6}
   {:id "benfeitor" :nome "Benfeitor Pokémon" :requisito "Doar 50 Pokémon"
    :metric :doacoes :minimo 50 :xp-recompensa 12}
   {:id "mestre-doador" :nome "Mestre das Doações" :requisito "Doar 100 Pokémon"
    :metric :doacoes :minimo 100 :xp-recompensa 24}
   {:id "primeira-vitoria" :nome "Primeira vitória" :requisito "1 vitória"
    :metric :vitorias :minimo 1 :xp-recompensa 3}
   {:id "batalhador" :nome "Batalhador" :requisito "10 vitórias"
    :metric :vitorias :minimo 10 :xp-recompensa 6}
   {:id "veterano" :nome "Veterano" :requisito "50 vitórias"
    :metric :vitorias :minimo 50 :xp-recompensa 12}
   {:id "campeao" :nome "Campeão" :requisito "100 vitórias"
    :metric :vitorias :minimo 100 :xp-recompensa 24}
   {:id "capturador" :nome "Capturador" :requisito "3 capturas seguidas"
    :metric :capturas :minimo 3 :xp-recompensa 3}
   {:id "cacador" :nome "Caçador" :requisito "5 capturas seguidas"
    :metric :capturas :minimo 5 :xp-recompensa 6}
   {:id "especialista-captura" :nome "Especialista" :requisito "10 capturas seguidas"
    :metric :capturas :minimo 10 :xp-recompensa 12}
   {:id "mestre-captura" :nome "Mestre da captura" :requisito "20 capturas seguidas"
    :metric :capturas :minimo 20 :xp-recompensa 24}])

(def catalogo-insignias-pe
  "Alias estável — testes/código legado."
  catalogo-insignias)

(defn insignias-treinador
  "Lista completa de conquistas com :conquistada (bool), para `!pk treinador`."
  [{:keys [doacoes-pokemon vitorias-treinador maior-sequencia-capturas]
    :or {doacoes-pokemon 0 vitorias-treinador 0 maior-sequencia-capturas 0}}]
  (let [metricas {:doacoes (long doacoes-pokemon)
                  :vitorias (long vitorias-treinador)
                  :capturas (long maior-sequencia-capturas)}]
    (mapv (fn [{:keys [id nome requisito metric minimo xp-recompensa]}]
            {:id id
             :nome nome
             :requisito requisito
             :xp-recompensa (long xp-recompensa)
             :conquistada (>= (get metricas metric 0) (long minimo))})
          catalogo-insignias)))

(defn xp-insignias-from-metrics
  "Deriva PE de conquistas a partir dos marcos persistidos (legado xp-insignias).
   Não grava; evita duplicar XP ao reiniciar ou reconsultar o perfil."
  [metrics]
  (reduce + 0 (map :xp-recompensa (filter :conquistada (insignias-treinador metrics)))))

(defn titulo-label
  "Resolve id persistido (:save/title) → nome exibível da master."
  [title-id]
  (when title-id
    (some (fn [{:keys [id nome]}]
            (when (= id (str title-id)) nome))
          catalogo-insignias)))

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
  (let [xp (max (xp-treinador-total componentes)
                (long (:trainer-xp componentes 0)))
        legado (progresso-treinador-legado xp)
        curva (progresso-treinador xp)
        nivel-salvo (max 1 (long (:trainer-level componentes 1)))
        progress-persisted? (some? (:trainer-xp-level-progress componentes))
        nivel (if progress-persisted?
                nivel-salvo
                (max nivel-salvo (:nivel curva)))
        progresso (if progress-persisted?
                    (max 0 (long (:trainer-xp-level-progress componentes)))
                    (if (> nivel-salvo (:nivel legado)) 0 (:xp-atual legado)))
        prog {:nivel nivel
              :xp-atual progresso
              :xp-necessario (trainer-xp-necessario nivel)}]
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
