(ns pokemon.domain.moves-audit
  "Auditoria reutilizável de golpes — pura, sem PII.
  Detecta nome/slug/poder/tipo ausentes ou divergentes do catálogo mínimo."
  (:require [clojure.string :as str]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.moves :as moves]))

(defn- blank? [x]
  (or (nil? x) (and (string? x) (str/blank? x))))

(defn- poder-val [g]
  (let [p (or (:poder g) (get g "poder") (:power g))]
    (when (number? p) (long p))))

(defn- classe-kw [g]
  (let [c (or (:classe g) (get g "classe") (:class g) "fisico")]
    (keyword c)))

(defn- ofensivo? [g]
  (contains? #{:fisico :especial :physical :special} (classe-kw g)))

(defn analisar-golpe
  "Classifica um golpe individual (já normalizado ou bruto)."
  [g]
  (let [n (battle/golpe-normalizado g)
        irrecuperavel? (boolean (:golpe-irrecuperavel? n))
        nome (:nome-exibicao n)
        slug (:slug n)
        tipo (:tipo n)
        poder (poder-val n)
        ofensivo (ofensivo? n)
        problemas (cond-> []
                    (blank? nome) (conj :sem-nome)
                    ;; slug ausente só é crítico se também não há nome recuperável
                    (and (blank? slug) (blank? nome)) (conj :sem-slug)
                    (blank? tipo) (conj :tipo-desconhecido)
                    (and ofensivo (nil? poder)) (conj :ofensivo-sem-poder)
                    irrecuperavel? (conj :irrecuperavel))]
    {:status (if (seq problemas) :problema :ok)
     :problemas problemas
     :ofensivo? ofensivo
     :poder-zero-legitimo? (and ofensivo (some? poder) (zero? poder)
                                (or (= :status (classe-kw n))
                                    (true? (:autodesmaio n))))
     :nome nome
     :slug slug
     :tipo tipo
     :poder poder
     :classe (classe-kw n)
     :autodesmaio? (boolean (:autodesmaio n))
     :golpe-irrecuperavel? irrecuperavel?}))

(defn analisar-pokemon
  "Audita vetor :golpes de um registro (sem PII — só species/nome)."
  [registro]
  (let [species (or (:species registro) (:nome registro) (get registro "species") "unknown")
        golpes (or (:golpes registro) (get registro "golpes") [])
        analyses (mapv analisar-golpe golpes)
        ruins (filterv #(= :problema (:status %)) analyses)]
    {:species (str species)
     :n-golpes (count golpes)
     :n-problemas (count ruins)
     :golpes analyses
     :ok? (empty? ruins)}))

(defn aggregate
  "Agrega auditoria de coleção anônima de Pokémon."
  [pokemons]
  (let [rows (mapv analisar-pokemon pokemons)
        n (count rows)
        ruins (filterv (complement :ok?) rows)
        problemas (frequencies (mapcat #(mapcat :problemas (:golpes %)) rows))]
    {:ok true
     :total-pokemon n
     :com-problemas (count ruins)
     :sem-problemas (- n (count ruins))
     :problemas-freq problemas
     :amostras-species (mapv :species (take 20 ruins))
     :notes ["Auditoria agregada — sem player-id/chat-id."
             "Poder 0 em status/autodesmaio não é problema."
             "Correção canônica: battle/golpe-normalizado + moves/golpe->registro."]}))

(defn roundtrip-legado-ok?
  "True se mapa só com \"nome\"/\"poder\" sobrevive a registro↔golpe↔slim."
  [legado]
  (let [reg (moves/golpe->registro legado)
        back (moves/registro->golpe reg)
        slim (battle/slim-golpe-for-battle legado)
        slim2 (battle/slim-golpe-for-battle back)]
    (and (not (blank? (:nome-exibicao slim)))
         (not (blank? (get reg "nome-exibicao")))
         (not (blank? (:nome-exibicao slim2)))
         (or (nil? (poder-val legado))
             (= (poder-val legado) (poder-val slim))))))
