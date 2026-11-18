(ns pokemon.domain.move-names
  "Nomes PT-BR de apresentação. O slug da PokéAPI permanece o identificador."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pokemon.domain.json-min :as json]))

(def ^:private ambiguos
  {"furacao" ["drill-run" "hurricane"]})

(def ^:private aliases-explicitos
  {"revolver-dagua" "water-gun"
   "jato-de-agua" "water-gun"
   "jato-dagua" "water-gun"
   "chicote-de-vinha" "vine-whip"
   "chicote-de-cipo" "vine-whip"
   "trovoada-de-choques" "thunder-shock"
   "recolher" "withdraw"
   "gigaimpacto" "giga-impact"
   "lanca-chamas" "flamethrower"
   "rajada-de-fogo" "fire-blast"
   "relampago" "thunderbolt"
   "raio-congelante" "ice-beam"
   "deslize-de-pedras" "rock-slide"
   "enrolar-defensivo" "defense-curl"
   "plano-maligno" "nasty-plot"
   "descansar" "rest"
   "encarar" "leer"
   "mastigada" "crunch"
   "talho" "slash"
   "pancada-corporal" "body-slam"
   "multiplicar" "double-team"
   "garra-de-dragao" "dragon-claw"
   "onda-de-choque" "thunder-wave"})

(defn- sem-acento [s]
  (-> (str (or s ""))
      (java.text.Normalizer/normalize java.text.Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      (str/replace #"[’']" "")))

(defn normalizar-acento [s]
  (-> (str (or s ""))
      str/lower-case
      str/trim
      (str/replace #"[ _]+" "-")
      (str/replace #"[^\p{L}\p{N}'’\-]" "")))

(defn normalizar [s]
  (-> (sem-acento (normalizar-acento s))
      (str/replace #"[^a-z0-9-]" "")))

(def nomes
  (json/read-str (slurp (io/resource "move-names-ptbr.json"))))

(defn- registrar [m slug chave]
  (cond
    (or (str/blank? chave) (contains? nomes chave) (contains? ambiguos chave)) m
    (not (contains? m chave)) (assoc m chave slug)
    (= (get m chave) slug) m
    :else (dissoc m chave)))

(def ^:private aliases
  (reduce-kv
   (fn [m slug nome]
     (-> m
         (registrar slug (normalizar (str/replace slug "-" " ")))
         (registrar slug (normalizar nome))))
   aliases-explicitos
   nomes))

(def ^:private aliases-acento
  (reduce-kv
   (fn [m slug nome]
     (registrar m slug (normalizar-acento nome)))
   {}
   nomes))

(defn resolver
  [nome]
  (let [chave-acento (normalizar-acento nome)
        chave (normalizar nome)]
    (cond
      (contains? nomes chave) {:slug chave}
      (get aliases-acento chave-acento) {:slug (get aliases-acento chave-acento)}
      (get ambiguos chave) {:ambiguo true :candidatos (get ambiguos chave)}
      (get aliases chave) {:slug (get aliases chave)}
      :else {:slug chave :desconhecido? (not (contains? nomes chave))})))

(defn apresentar
  "Nome PT-BR para exibir. Não altera o mapa persistido."
  [golpe]
  (let [slug (some-> (or (:slug golpe) (get golpe "slug")) str)
        bruto (or (:nome-exibicao golpe) (get golpe "nome-exibicao")
                  (:nomeExibicao golpe) (get golpe "nomeExibicao")
                  (:nome golpe) slug)]
    (or (get nomes slug)
        (when bruto
          (let [r (resolver bruto)]
            (get nomes (:slug r))))
        bruto
        "golpe")))
