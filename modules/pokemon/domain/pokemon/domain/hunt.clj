(ns pokemon.domain.hunt
  "Regras de caça selvagem — espelho de core/cacar + shiny + surtos.
  Sem I/O; candidatos já vêm da PokeAPI via Node."
  (:require [clojure.string :as str]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.errors :as err]
            [pokemon.domain.mundo :as mundo])
  (:import [java.time Instant]))

(def total-pokemons 1025)
(def cooldown-cacada-ms (* 30 60 1000))
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
(def chance-surto 50)

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

(defn- peso-raridade
  [r]
  (get {"comum" 0 "incomum" 1 "raro" 2 "epico" 3 "lendario" 4 "mitico" 5} r 0))

(defn- maior-raridade
  [a b]
  (if (> (peso-raridade b) (peso-raridade a)) b a))

(defn classificar-raridade
  "Espelha core/classificar-raridade (stats já suavizados)."
  [pokemon]
  (cond
    (:mitico-api? pokemon) "mitico"
    (or (:lendario-api? pokemon) (:paradox-api? pokemon)) "lendario"
    :else
    (let [poder-original (- (* 2 (capture/poder-total pokemon)) 450)
          por-poder (cond (<= poder-original 400) "comum"
                          (<= poder-original 480) "incomum"
                          (<= poder-original 540) "raro"
                          :else "epico")
          taxa (:taxa-captura pokemon)
          por-captura (cond (nil? taxa) "comum"
                            (<= (long taxa) 10) "epico"
                            (<= (long taxa) 45) "raro"
                            (<= (long taxa) 120) "incomum"
                            :else "comum")]
      (maior-raridade por-poder por-captura))))

(defn com-raridade
  [pokemon]
  (assoc pokemon :raridade (classificar-raridade pokemon)))

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
  "35% clima / 65% bioma. roll 0–99."
  [bioma clima roll]
  (if (< (long roll) chance-tipos-clima)
    (set (:tipos clima))
    (set (:tipos bioma))))

(defn surto?
  [roll]
  (< (long roll) chance-surto))

(defn cooldown-restante-ms
  [ultima-cacada-at agora-ms]
  (let [ultima (long (or ultima-cacada-at 0))
        faltam (- cooldown-cacada-ms (- (long agora-ms) ultima))]
    (max 0 faltam)))

(defn pode-cacar?
  [ultima-cacada-at agora-ms]
  (zero? (cooldown-restante-ms ultima-cacada-at agora-ms)))

(defn validar-candidato-basico!
  "Rejeita DTO incompleto vindo do Node/PokeAPI.
   `taxa-captura` é opcional (species pode falhar); raridade cai no fallback."
  [p]
  (let [g (fn [k]
            (or (get p k)
                (get p (keyword k))
                (when (keyword? k) (get p (name k)))))
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

(defn montar-plano
  "Plano de caça (RNG injetável para testes)."
  [{:keys [area-pedida instant timezone-id trainer-level
           roll-tipos roll-surto agora-ms]}]
  (let [instant (or instant (Instant/ofEpochMilli (long (or agora-ms (System/currentTimeMillis)))))
        tz (or timezone-id mundo/default-timezone)
        dia (mundo/dia-atual instant tz)
        clima (mundo/clima-do-dia dia)
        area (if (and area-pedida (not (str/blank? (str area-pedida))))
               (or (mundo/area-disponivel area-pedida dia)
                   (throw (err/error :area_unavailable {:pedido area-pedida :dia dia})))
               (mundo/area-padrao instant tz))
        roll-t (long (or roll-tipos (rand-int 100)))
        roll-s (long (or roll-surto (rand-int 100)))
        tipos (resolver-tipos-encontro area clima roll-t)
        evento (evento-atual (.toEpochMilli instant))
        surto (surto? roll-s)
        nivel (long (or trainer-level 1))
        alvo (poder-alvo-caca nivel)]
    {:dia dia
     :timezone tz
     :area (area-dto area)
     :clima (clima-dto clima)
     :tipos-encontro (vec (sort tipos))
     :surto? surto
     :evento (when surto (select-keys evento [:nome :especies :fim :chance]))
     :poder-alvo alvo
     :tolerancia tolerancia-poder-caca
     :tentativas-max tentativas-caca
     :total-pokemons total-pokemons
     :roll-tipos roll-t
     :roll-surto roll-s
     :mundo (mundo/resumo instant tz)}))
