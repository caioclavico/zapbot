(ns pokemon.domain.xp
  "Regras de XP/nível.

  Pokémon (growth-v2): `xpNecessario(n) = 10 + 2*(n-1)` via `pokemon.domain.growth`.
  growth-v1 (curvas PokéAPI) permanece só para conversão proporcional.
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
(def ^{:doc "Pontos de atividade legados por nível (vitória=3 ⇒ ~3 vitórias/nível)."}
  activity-points-per-level growth/legacy-xp-por-nivel)
(def ^{:doc "Teto anti-exploit: fração máxima do span do nível por grant único."}
  pokemon-xp-grant-span-cap 0.60)
(def ^{:doc "Multiplicador global opcional sobre o XP growth-v2 já balanceado.
  1 = ritmo ~activity-points-per-level por nível."}
  pokemon-xp-grant-scale 1)
(def trainer-xp-inicial-por-nivel 10)
(def trainer-xp-incremento-por-nivel 5)

(defn calcular-ganho-pokemon
  "Converte pontos de atividade (vitória=3, derrota=1, raid=6…) em XP growth-v2.

  Base: `(pts / 9) × span-simples(nível)` — preserva ~3 vitórias/nível sobre a
  fórmula linear. Ajustes: nível do adversário (0.5–1.5), atividade e
  categoria de raid. Cap: `pokemon-xp-grant-span-cap` do span atual."
  ([registro activity-points]
   (calcular-ganho-pokemon registro activity-points nil))
  ([registro activity-points
    {:keys [nivel-adversario atividade categoria-raid]
     :or {atividade :batalha}}]
   (let [r (growth/ensure-growth-v2 registro)
         nivel (long (:nivel r 1))
         pts (max 0 (long activity-points))]
     (cond
       (or (zero? pts) (>= nivel growth/nivel-maximo)) 0
       :else
       (let [span (growth/level-span-simple nivel)
             base (if (pos? span)
                    (max 1 (long (Math/floor
                                  (* (double span)
                                     (/ (double pts)
                                        (double activity-points-per-level))))))
                    0)
             ratio (let [adv (when (some? nivel-adversario) (long nivel-adversario))]
                     (if (and adv (pos? nivel))
                       (max 0.5 (min 1.5 (/ (double adv) (double nivel))))
                       1.0))
             act-mult (case atividade
                        (:raid :raids) 1.0
                        (:ginasio :gym) 1.10
                        (:captura :capture) 1.0
                        (:pvp) 1.05
                        (:cacada :hunt :batalha :battle) 1.0
                        1.0)
             cat (some-> categoria-raid str clojure.string/lower-case)
             cat-mult (case cat
                        ("raro" "rare" "comum" "common") 1.0
                        ("epico" "épico" "epic") 1.25
                        ("lendario" "lendário" "legendary") 1.50
                        ("mitico" "mítico" "mythical") 1.75
                        1.0)
             raw (long (Math/floor (* base ratio act-mult cat-mult
                                      (double pokemon-xp-grant-scale))))
             cap (if (pos? span)
                   (max 1 (long (Math/floor (* (double span)
                                               pokemon-xp-grant-span-cap))))
                   0)]
         (if (pos? span) (min raw cap) 0))))))

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
  "Progresso de XP dentro do nível atual (canônico growth-v2).
  Não inventa XP ausente. Ver `growth/pokemon-progress`."
  [registro]
  (growth/pokemon-progress registro))

(defn aplicar-xp-pokemon
  "Concede XP na unidade growth-v2.
  Converte legado/v1 → growth-v2 na primeira aplicação (proporcional; v1→v2
  sem level-up; legado→v1 pode subir se residual ≥ 9, política anterior).
  Aplica crescimento de stats para subidas (inclui Δ de conversão legado).
  Retorna {:registro :subidas :nivel-final :nome ...}.

  opts:
  - `:unidade` `:oficial` (default) | `:atividade` — se `:atividade`, passa por
    `calcular-ganho-pokemon` antes de aplicar.
  - demais chaves de contexto do calculador (nível adversário, raid, etc.)."
  ([registro quantidade] (aplicar-xp-pokemon registro quantidade nil))
  ([registro quantidade {:keys [unidade] :as opts}]
   ;; XP desconhecido: zero/ganho inválido não pode convertê-lo silenciosamente.
   ;; No nível máximo também não existe ganho positivo, logo não inicializa.
   (if (and (not (growth/xp-known? registro))
            (or (not (pos? (long quantidade)))
                (>= (long (get registro :nivel (get registro "nivel" 1)))
                    growth/nivel-maximo)))
     {:registro registro
      :subidas 0
      :nivel-final (long (get registro :nivel (get registro "nivel" 1)))
      :nome (get registro :nome (get registro "nome"))
      :xp-total nil
      :xp-oficial-aplicado 0
      :growth-rate nil}
   (let [opts (or opts {})
         nivel-antes (long (get registro :nivel (get registro "nivel" 1)))
         ;; Conversão lazy antes do grant.
         converted (growth/ensure-growth-v2 registro)
         nivel-pos-conv (long (:nivel converted 1))
         subidas-conv (max 0 (- nivel-pos-conv nivel-antes))
         official (if (= unidade :atividade)
                    (calcular-ganho-pokemon converted (max 0 (long quantidade)) opts)
                    (max 0 (long quantidade)))
         granted (growth/grant-pokemon-xp converted official)
         r0 (:registro granted)
         subidas-grant (long (:subidas granted 0))
         subidas (+ subidas-conv subidas-grant)
         ;; No nível 100 o grant descarta o excedente — reportar o efetivamente
         ;; incorporado ao total (0 no cap), não o pedido bruto.
         aplicado (long (:xp-ganho granted 0))
         amizade (min 255 (+ (long (get r0 :amizade (get r0 "amizade" 70))) 10))
         r1 (-> r0
                (assoc :amizade amizade
                       ;; stats sobem a partir do nível pré-conversão/pré-ganho
                       :nivel nivel-antes)
                (dissoc :vitorias-desde-nivel "vitorias-desde-nivel"))
         r-stats (if (pos? subidas) (aplicar-subidas-stats r1 subidas) r1)
         r-final (assoc r-stats
                        :nivel (long (:nivel-final granted))
                        :xp-total (long (:xp-total r0))
                        :xp-desde-nivel (long (:xp-desde-nivel r0))
                        :xp (long (:xp-desde-nivel r0))
                        :xp-progress-version growth/progress-version
                        :growth-rate growth/simple-growth-rate)]
     {:registro r-final
      :subidas subidas
      :nivel-final (long (:nivel-final granted))
      :nome (get r-final :nome (get r-final "nome"))
      :xp-total (:xp-total r0)
      :xp-oficial-aplicado aplicado
      :growth-rate growth/simple-growth-rate}))))

(defn aplicar-xp-atividade
  "Atalho: pontos de atividade → XP oficial balanceado → aplicar."
  ([registro activity-points]
   (aplicar-xp-pokemon registro activity-points {:unidade :atividade}))
  ([registro activity-points opts]
   (aplicar-xp-pokemon registro activity-points
                       (assoc (or opts {}) :unidade :atividade))))

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
