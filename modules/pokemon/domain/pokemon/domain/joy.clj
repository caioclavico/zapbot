(ns pokemon.domain.joy
  "Centro Pokémon / Enfermeira Joy — puro. Espelha treinador.cljs."
  (:require [clojure.string :as str]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.errors :as err])
  (:import [java.security MessageDigest]))

(def tempo-tratamento-minutos 30)
(def tempo-tratamento-ms (* tempo-tratamento-minutos 60 1000))
(def tempo-tratamento-padrao-segundos 1800)

(def ^:private datomic-local-string-limit
  "Limite prático do Datomic Local para valor de atributo (~4096)."
  4000)

(defn- sha12
  [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str s) "UTF-8"))]
    (subs (apply str (map #(format "%02x" %) digest)) 0 12)))

(defn- fit-side-edn
  "Garante string ≤ limite Local (origin/legado do leito)."
  [s]
  (when (some? s)
    (let [s (if (string? s) s (pr-str s))]
      (if (<= (count s) datomic-local-string-limit)
        s
        (pr-str {:a64-edn-omitted true
                 :a64-edn-sha12 (sha12 s)
                 :a64-edn-bytes (count s)})))))

(defn- slim-historico
  [hist]
  (cond
    (map? hist)
    (into {} (map (fn [[k v]]
                    [k (if (map? v) (battle/slim-golpe-for-battle v) v)])
                  hist))
    (sequential? hist)
    (mapv #(if (map? %) (battle/slim-golpe-for-battle %) %) hist)
    :else hist))

(defn bed-pokemon-registro
  "Payload enxuto de :enfermaria-bed/pokemon-edn.
   origin/legado ficam em attrs dedicados do leito (migração 012)."
  [poke]
  (let [golpes (mapv battle/slim-golpe-for-battle (or (:golpes poke) []))
        removidos (mapv #(if (map? %) (battle/slim-golpe-for-battle %) %)
                        (or (:golpes-removidos poke) []))
        ofertas (or (:golpes-oferecidos poke) (:ofertas-golpes poke))
        ofertas' (when (seq ofertas)
                   (mapv #(if (map? %) (battle/slim-golpe-for-battle %) %) ofertas))]
    (cond-> {:id-pokemon (:id-pokemon poke)
             :species (or (:species poke) (:nome poke))
             :nome (or (:nome poke) (:nickname poke) (:species poke))
             :shiny (boolean (:shiny poke))
             :nivel (long (or (:nivel poke) (:level poke) 1))
             :xp-desde-nivel (long (or (:xp-desde-nivel poke) 0))
             :hp (long (:hp poke 0))
             :hp-atual (long (:hp-atual poke 0))
             :amizade (long (:amizade poke 70))
             :ataque (long (:ataque poke 0))
             :defesa (long (:defesa poke 0))
             :atq-esp (long (:atq-esp poke 0))
             :def-esp (long (:def-esp poke 0))
             :veloc (long (:veloc poke 0))
             :raridade (or (:raridade poke) "comum")
             :golpes golpes
             :tipos (vec (or (:tipos poke) []))}
      (:status poke) (assoc :status (:status poke))
      (:habilidade poke) (assoc :habilidade (:habilidade poke))
      (:imagem poke) (assoc :imagem (:imagem poke))
      (:imagem-shiny poke) (assoc :imagem-shiny (:imagem-shiny poke))
      (:item poke) (assoc :item (:item poke))
      (number? (:altura poke)) (assoc :altura (:altura poke))
      (number? (:peso poke)) (assoc :peso (:peso poke))
      (number? (:taxa-captura poke)) (assoc :taxa-captura (:taxa-captura poke))
      (seq removidos) (assoc :golpes-removidos removidos)
      (seq (:historico-golpes poke))
      (assoc :historico-golpes (slim-historico (:historico-golpes poke)))
      (seq ofertas') (assoc :golpes-oferecidos ofertas')
      (:ivs poke) (assoc :ivs (:ivs poke)))))

(defn- try-encode-bed
  "Tenta encode; nil se :datomic_value_too_large (para cascata)."
  [r]
  (try
    (battle/encode-value-edn :enfermaria-bed/pokemon-edn r)
    (catch clojure.lang.ExceptionInfo ex
      (if (= :datomic_value_too_large (err/error-code ex))
        nil
        (throw ex)))))

(defn- encode-bed-pokemon-edn
  "Serializa registro do leito sob o limite Local (plain ou gzip).
   Se ainda estourar, remove blobs opcionais em cascata."
  [reg]
  (let [candidatos [reg
                    (dissoc reg :imagem :imagem-shiny)
                    (dissoc reg :imagem :imagem-shiny :golpes-oferecidos
                            :historico-golpes :ivs)
                    (-> reg
                        (dissoc :imagem :imagem-shiny :golpes-oferecidos
                                :historico-golpes :ivs :golpes-removidos)
                        (update :golpes #(mapv (fn [g]
                                                 (select-keys g [:slug :nome-exibicao
                                                                 :tipo :poder :classe]))
                                               (or % []))))]]
    (or (some try-encode-bed candidatos)
        (throw (err/error :datomic_value_too_large
                          {:attr :enfermaria-bed/pokemon-edn})))))

(defn bed-write-attrs
  "Attrs Datomic do leito a partir da entrada de domínio.
   pokemon-edn enxuto (+ gzip se ainda > limite); origin/legado separados."
  [bed-key sk bed]
  (let [poke (:pokemon bed)
        edn (encode-bed-pokemon-edn (bed-pokemon-registro poke))
        origin (fit-side-edn
                (or (:origin-edn poke)
                    (when (:origin poke) (pr-str (:origin poke)))))
        legado (when (seq (:legado poke))
                 (fit-side-edn (pr-str (:legado poke))))]
    (cond-> {:enfermaria-bed/key bed-key
             :enfermaria-bed/save-key sk
             :enfermaria-bed/bed-id (:bed-id bed)
             :enfermaria-bed/pokemon-edn edn
             :enfermaria-bed/pronto-em (:pronto-em bed)}
      origin (assoc :enfermaria-bed/origin-edn origin)
      legado (assoc :enfermaria-bed/legado-edn legado))))

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
