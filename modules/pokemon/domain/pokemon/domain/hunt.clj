(ns pokemon.domain.hunt
  "Regras de caça selvagem — espelho de core/cacar + shiny + surtos.
  Sem I/O; candidatos já vêm da PokeAPI via Node.
  HP de combate/coleção: canônico JVM (`species-hp-v1`) quando `:hp-base`
  oficial está no DTO (Node ou enricher no adapter). Sem require de
  `species-base` aqui — evita ciclo hunt↔raid-boss."
  (:require [clojure.string :as str]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.errors :as err]
            [pokemon.domain.events :as events]
            [pokemon.domain.mundo :as mundo]
            [pokemon.domain.species-hp :as species-hp])
  (:import [java.time Instant]))

(def total-pokemons 1025)
(def cooldown-cacada-ms (* 30 60 1000))
(def cooldown-cacada-padrao-segundos 1800)
(def poder-alvo-nivel-1 320)
(def incremento-poder-por-nivel 25)
(def tolerancia-poder-caca 60)
(def tentativas-caca 25)
(def nivel-maximo-caca 11)
(def chance-tipos-clima 35)
(def shiny-chance 512)

(def surtos
  [{:nome "Festival das Águas" :especies ["poliwag" "shellder" "staryu" "eevee"]}
   {:nome "Faíscas no Campo" :especies ["pikachu" "magnemite" "voltorb" "eevee"]}
   {:nome "Trilha em Chamas" :especies ["vulpix" "growlithe" "ponyta" "eevee"]}
   {:nome "Floresta em Festa" :especies ["oddish" "bellsprout" "exeggcute" "eevee"]}])

(def duracao-evento-ms (* 6 60 60 1000))
;; Balanceamento Combat UX+Eventos RC: 50 → 25 (aprovado localmente).
;; 75% restantes seguem seleção normal (clima/bioma / thematic bias).
(def chance-surto 25)

(def media-stat-referencia 75)
(def fator-compressao-stat 0.5)

(defn suavizar-stat
  "Espelha core/suavizar-stat."
  [valor]
  (Math/round (+ (double media-stat-referencia)
                 (* fator-compressao-stat
                    (- (double (or valor 0)) media-stat-referencia)))))

(defn poder-alvo-caca
  [nivel]
  (+ poder-alvo-nivel-1
     (* incremento-poder-por-nivel
        (dec (min (max 1 (long (or nivel 1))) nivel-maximo-caca)))))

(defn evento-atual
  "Surtos rotativos a cada 6h (legado aventuras/evento-atual)."
  [agora-ms]
  (let [agora (long agora-ms)
        periodo (quot agora duracao-evento-ms)
        base (nth surtos (mod periodo (count surtos)))]
    (assoc base
           :fim (* (inc periodo) duracao-evento-ms)
           :chance chance-surto)))

(def classificar-raridade
  "Delegado canônico — ver `pokemon.domain.capture/classificar-raridade`."
  capture/classificar-raridade)

(defn com-raridade
  "Carimba raridade canônica (+ fonte) no candidato de caça."
  [pokemon]
  (capture/assegurar-raridade pokemon))

(defn sortear-shiny
  "1/512. `sorteio` injetável (0 = shiny).
   `nil` no sorteio = RNG local (produção não envia shinyRoll)."
  ([pokemon] (sortear-shiny pokemon (rand-int shiny-chance)))
  ([pokemon sorteio]
   (let [roll (long (or sorteio (rand-int shiny-chance)))]
     (if (zero? roll)
       (assoc pokemon :shiny? true :shiny true
              :imagem (or (:imagem-shiny pokemon) (:imagem pokemon)))
       (assoc pokemon :shiny? false :shiny false)))))

(defn pertence-ao-bioma?
  [pokemon tipos]
  (boolean (some (set tipos) (or (:tipos pokemon) []))))

(defn selecionar-selvagem
  "Espelha sortear-selvagem sobre candidatos já buscados (ordem = ordem das tentativas)."
  [candidatos alvo tipos]
  (when (empty? candidatos)
    (throw (err/error :hunt_no_candidates {})))
  (let [alvo (long alvo)
        teto (+ alvo tolerancia-poder-caca)]
    (loop [restantes candidatos
           melhor nil]
      (if (empty? restantes)
        (or melhor (first candidatos))
        (let [c (first restantes)
              ok? (and (pertence-ao-bioma? c tipos)
                       (<= (capture/poder-total c) teto))
              melhor' (if (and (pertence-ao-bioma? c tipos)
                               (or (nil? melhor)
                                   (< (capture/poder-total c) (capture/poder-total melhor))))
                        c
                        melhor)]
          (if ok?
            c
            (recur (next restantes) melhor')))))))

(defn resolver-tipos-encontro
  "Sorteio de tipos do encontro.
  Sem evento: 35% clima / 65% bioma.
  Com active-events (12.8.1): bias% tipos do evento, depois clima, depois bioma.
  Isolamento 12.8.0: montar-plano chama a aridade de 3 args (nil) e
  events/active-events retorna [] com POKEMON_THEMATIC_EVENTS_ENABLED≠true."
  ([bioma clima roll]
   (resolver-tipos-encontro bioma clima roll nil))
  ([bioma clima roll active-events]
   (let [r (long roll)
         bias (events/type-bias-chance active-events)
         favored (events/favored-types active-events)]
     (cond
       (and (pos? bias) (seq favored) (< r bias))
       favored
       (< r (+ bias chance-tipos-clima))
       (set (:tipos clima))
       :else
       (set (:tipos bioma))))))

(defn surto?
  [roll]
  (< (long roll) chance-surto))

(defn environment
  "Indireção pequena para tornar a configuração do processo testável."
  [name]
  (System/getenv name))

(defn cooldown-cacada-segundos
  "Lê a configuração do processo. Zero só é aceito em staging; produção mantém
   o padrão seguro de 30 minutos mesmo se a variável estiver configurada como 0."
  []
  (let [raw (some-> (environment "POKEMON_HUNT_COOLDOWN_SECONDS") str)
        seconds (try (Long/parseLong raw) (catch Exception _ cooldown-cacada-padrao-segundos))
        staging? (= "staging" (some-> (environment "APP_ENV") str/lower-case str/trim))]
    (cond
      (neg? seconds) cooldown-cacada-padrao-segundos
      (and (zero? seconds) (not staging?)) cooldown-cacada-padrao-segundos
      :else seconds)))

(defn cooldown-cacada-atual-ms
  []
  (* 1000 (cooldown-cacada-segundos)))

(defn cooldown-restante-ms
  [ultima-cacada-at agora-ms]
  (let [ultima (long (or ultima-cacada-at 0))
        faltam (- (cooldown-cacada-atual-ms) (- (long agora-ms) ultima))]
    (max 0 faltam)))

(defn pode-cacar?
  [ultima-cacada-at agora-ms]
  (zero? (cooldown-restante-ms ultima-cacada-at agora-ms)))

(defn- g-campo
  [p k]
  (or (get p k)
      (get p (keyword k))
      (when (keyword? k) (get p (name k)))
      (when (= k :hp-base)
        (or (get p "hpBase") (get p :hpBase)))))

(defn resolver-hp-base-candidato
  "Lê `:hp-base` / `hpBase` do DTO. Não consulta catálogo (ver adapter)."
  [p]
  (let [explicit (g-campo p :hp-base)]
    (when (and (number? explicit) (pos? (long explicit)))
      (long explicit))))

(defn canonizar-hp-candidato
  "Fonte canônica JVM: substitui `:hp`/`:hp-atual` por species-hp-v1 quando
  `:hp-base` está presente. Ataque/defesa do Node permanecem. Sem base →
  preserva HP do DTO (legado) sem inventar valor."
  [p]
  (let [nivel (max 1 (long (or (:nivel p) (g-campo p "nivel") 1)))
        base (resolver-hp-base-candidato p)]
    (if-not base
      p
      (let [fields (species-hp/aplicar-hp-especie
                    {:base-hp base :nivel nivel :full? true})]
        (merge p fields)))))

(defn validar-candidato-basico!
  "Rejeita DTO incompleto vindo do Node/PokeAPI.
   `taxa-captura` é opcional (species pode falhar); raridade cai no fallback."
  [p]
  (let [g (fn [k] (g-campo p k))
        num? (fn [k] (number? (g k)))]
    (when-not (and (map? p)
                    (string? (or (:nome p) (:species p) (g "nome") (g "species")))
                    (seq (or (:tipos p) (g "tipos")))
                    (num? :hp)
                    (pos? (long (or (g :hp) 0)))
                    (num? :ataque) (num? :defesa)
                    (num? :atq-esp) (num? :def-esp)
                    (num? :veloc)
                    (seq (or (:golpes p) (g "golpes"))))
      (throw (err/error :invalid_wild_pokemon
                        {:need [:nome-or-species :tipos :hp :ataque :defesa
                                :atq-esp :def-esp :veloc :golpes]
                         :got (select-keys p [:nome :species :tipos :hp :ataque
                                              :defesa :atq-esp :def-esp :veloc
                                              :golpes :taxa-captura])})))
    p))

(defn area-dto
  [area]
  (when area
    (-> area
        (select-keys [:id :nome :emoji :ceu :chao])
        (assoc :tipos (vec (sort (:tipos area)))))))

(defn clima-dto
  [clima]
  (when clima
    (-> clima
        (select-keys [:id :nome :emoji])
        (assoc :tipos (vec (sort (:tipos clima)))))))

(defn texto-evento
  "Espelha core/ver-evento (sem texto de recomeço/loja — fora do escopo A4.1)."
  [agora-ms & {:keys [prefix]}]
  (let [agora (long agora-ms)
        evento (evento-atual agora)
        minutos (long (Math/ceil (/ (double (- (:fim evento) agora)) 60000.0)))
        pref (or prefix "!pk")]
    (str "🎉 *" (:nome evento) "*\n"
         "Espécies em destaque: " (str/join ", " (:especies evento)) "\n"
         "50% das caçadas encontram uma dessas espécies; os demais encontros seguem o bioma.\n"
         "Troca de evento em " minutos " minutos. Os eventos mudam a cada 6 horas.\n"
         "Use " pref " cacar. As três tentativas e a chance de fuga continuam valendo.")))

(defn montar-plano
  "Plano de caça (RNG injetável para testes).
  :active-events — ocorrências/views temáticas ativas (12.8.1); nil = só clima/bioma."
  [{:keys [area-pedida instant timezone-id trainer-level
           roll-tipos roll-surto agora-ms active-events]}]
  (let [instant (or instant (Instant/ofEpochMilli (long (or agora-ms (System/currentTimeMillis)))))
        tz (or timezone-id mundo/default-timezone)
        dia (mundo/dia-atual instant tz)
        clima (mundo/clima-do-dia dia)
        area (if (and area-pedida (not (str/blank? (str area-pedida))))
               (or (mundo/area-disponivel area-pedida dia)
                   (throw (err/error :area_unavailable
                                     {:pedido area-pedida
                                      :dia dia
                                      :resumo-texto (mundo/texto-resumo instant tz)})))
               (mundo/area-padrao instant tz))
        roll-t (long (or roll-tipos (rand-int 100)))
        roll-s (long (or roll-surto (rand-int 100)))
        thematic (when (events/thematic-events-enabled?) active-events)
        tipos (resolver-tipos-encontro area clima roll-t thematic)
        evento (evento-atual (.toEpochMilli instant))
        surto (surto? roll-s)
        nivel (long (or trainer-level 1))
        alvo (poder-alvo-caca nivel)
        evento-dto (select-keys evento [:nome :especies :fim :chance])
        bands (events/combined-type-roll-bands thematic)]
    {:dia dia
     :timezone tz
     :area (area-dto area)
     :clima (clima-dto clima)
     :tipos-encontro (vec (sort tipos))
     :surto? surto
     ;; Surto desta caçada (só quando roll acertou).
     :evento (when surto evento-dto)
     ;; Sempre presente — !pk eventos / apresentação do ciclo 6h.
     :evento-atual evento-dto
     :eventos-tematicos (mapv #(select-keys % [:id :nome :emoji :tipos-favorecidos :bias-tipos
                                               :occurrence-id :inicio-ms :fim-ms])
                              (or thematic []))
     :type-roll-bands bands
     :poder-alvo alvo
     :tolerancia tolerancia-poder-caca
     :tentativas-max tentativas-caca
     :total-pokemons total-pokemons
     :roll-tipos roll-t
     :roll-surto roll-s
     :mundo (mundo/resumo instant tz)
     :resumo-texto (mundo/texto-resumo instant tz)}))
