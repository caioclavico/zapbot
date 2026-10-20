(ns pokemon.domain.moves
  "Golpes — regras de zapbot.pokemon.treinador/preparar-aprendizado e golpes.
  Sem I/O; catálogo completo de nomes fica no legado Node."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]))

(def maximo-golpes 4)

(def ^:private ataques-iniciais
  {"normal" ["Investida" 40 :fisico] "fire" ["Brasas" 40 :especial]
   "water" ["Jato de Água" 40 :especial] "electric" ["Choque do Trovão" 40 :especial]
   "grass" ["Chicote de Cipó" 45 :fisico] "ice" ["Neve em Pó" 40 :especial]
   "fighting" ["Soco Rápido" 40 :fisico] "poison" ["Ferrão Venenoso" 15 :fisico]
   "ground" ["Tapa de Lama" 20 :especial] "flying" ["Bicada" 35 :fisico]
   "psychic" ["Confusão" 50 :especial] "bug" ["Picada" 60 :fisico]
   "rock" ["Arremesso de Pedra" 50 :fisico] "ghost" ["Lambida" 30 :fisico]
   "dragon" ["Tornado" 40 :especial] "dark" ["Mordida" 60 :fisico]
   "steel" ["Garra de Metal" 50 :fisico] "fairy" ["Vento de Fada" 40 :especial]})

(defn- normalizar-nome
  [nome]
  (-> (or nome "")
      str/trim
      str/lower-case
      (java.text.Normalizer/normalize java.text.Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      (str/replace #"[ _]+" "-")
      (str/replace #"[^a-z0-9\-]" "")))

(defn identificador
  [nome]
  (normalizar-nome nome))

(defn chave
  [golpe]
  (identificador (or (:slug golpe) (:nome-exibicao golpe)
                     (get golpe "slug") (get golpe "nome-exibicao"))))

(defn ataque-do-tipo?
  [golpe tipos]
  (let [tipo (or (:tipo golpe) (get golpe "tipo"))
        classe (or (:classe golpe) (get golpe "classe"))
        classe (if (keyword? classe) classe (keyword classe))
        poder (or (:poder golpe) (get golpe "poder") 0)]
    (and (contains? (set tipos) tipo)
         (contains? #{:fisico :especial} classe)
         (pos? (long poder)))))

(defn golpe->registro
  [g]
  (cond-> {"slug" (chave g)
           "nome-exibicao" (or (:nome-exibicao g) (get g "nome-exibicao") (chave g))
           "tipo" (or (:tipo g) (get g "tipo"))
           "poder" (or (:poder g) (get g "poder") 0)
           "classe" (name (or (:classe g)
                              (when-let [c (get g "classe")] (keyword c))
                              :fisico))}
    (:alvo g) (assoc "alvo" (name (:alvo g)))
    (:precisao g) (assoc "precisao" (:precisao g))))

(defn registro->golpe
  [g]
  {:slug (get g "slug")
   :nome-exibicao (get g "nome-exibicao")
   :tipo (get g "tipo")
   :poder (get g "poder")
   :classe (keyword (get g "classe" "fisico"))})

(defn unicos
  [golpes]
  (second
   (reduce (fn [[vistos acc] g]
             (let [id (chave g)]
               (if (contains? vistos id)
                 [vistos acc]
                 [(conj vistos id) (conj acc g)])))
           [#{} []]
           golpes)))

(defn garantir-ataque-do-tipo
  "Pelo menos um ataque ofensivo de um dos tipos; se faltar, concede básico."
  [golpes tipos]
  (let [golpes (unicos golpes)]
    (if (some #(ataque-do-tipo? % tipos) golpes)
      golpes
      (if-let [[nome poder classe] (get ataques-iniciais (first tipos))]
        (conj (vec (take 3 golpes))
              {:slug (identificador nome)
               :nome-exibicao nome
               :tipo (first tipos)
               :poder poder
               :classe classe})
        golpes))))

(def preco-reaprender 50)

(defn ofertas
  "Fila de ofertas pendentes (mapas de golpe)."
  [registro]
  (mapv registro->golpe
        (or (:ofertas-golpes registro) (:golpes-oferecidos registro) [])))

(defn- ids-ja-oferecidos
  "Ids que não devem ser reofertados por nível (conhecidos + fila + removidos)."
  [registro]
  (set (concat
        (map chave (mapv registro->golpe (or (:golpes registro) [])))
        (map chave (ofertas registro))
        (map identificador (or (:golpes-removidos registro) [])))))
(defn preparar-aprendizado
  "Valida troca sem I/O. slot nil = vaga livre; inteiro = substitui.
  Nunca elimina o último ataque do próprio tipo. Lança em falha."
  [registro golpe slot]
  (let [atuais (mapv registro->golpe (or (:golpes registro) []))
        tipos (or (:tipos registro) [])
        id (chave golpe)
        novo (cond
               (and (nil? slot) (< (count atuais) maximo-golpes)) (conj atuais golpe)
               (and (integer? slot) (<= 0 slot) (< slot (count atuais)))
               (assoc atuais slot golpe)
               :else nil)]
    (when-not novo
      (throw (err/error :invalid_move_slot {:slot slot :count (count atuais)})))
    (when (some #(= id (chave %)) atuais)
      (throw (err/error :move_already_known {:slug id})))
    (when-not (some #(ataque-do-tipo? % tipos) novo)
      (throw (err/error :must_keep_stab_move {:tipos tipos})))
    (let [antigo (when (some? slot) (get atuais slot))
          removidos (set (or (:golpes-removidos registro) []))
          historico (or (:historico-golpes registro) {})]
      (cond-> (assoc registro
                     :golpes (mapv golpe->registro novo)
                     :historico-golpes (assoc historico id (golpe->registro golpe))
                     :golpes-removidos (vec (remove #{id} removidos)))
        antigo (assoc-in [:historico-golpes (chave antigo)] (golpe->registro antigo))
        antigo (update :golpes-removidos #(vec (distinct (conj (vec %) (chave antigo)))))))))

(defn aplicar-aprendizado
  "Aplica preparar-aprendizado; se oferta?, consome a primeira oferta (deve casar)."
  [registro golpe slot oferta?]
  (let [fila (ofertas registro)
        id (chave golpe)]
    (when (and oferta?
               (or (empty? fila) (not= id (chave (first fila)))))
      (throw (err/error :no_move_offer {:expected id
                                        :have (some-> (first fila) chave)})))
    (let [novo (preparar-aprendizado registro golpe slot)]
      (cond-> novo
        oferta? (assoc :ofertas-golpes (mapv golpe->registro (vec (rest fila)))
                       :golpes-oferecidos (mapv golpe->registro (vec (rest fila))))))))

(defn oferecer-golpe
  "Espelha treinador/oferecer-golpe!: auto-aprende se houver vaga; senão enfileira.
  Retorna {:resultado :aprendido|:pendente :registro ...}."
  [registro golpe]
  (let [id (chave golpe)
        atuais (mapv registro->golpe (or (:golpes registro) []))]
    (when (contains? (ids-ja-oferecidos registro) id)
      (throw (err/error :move_already_known {:slug id})))
    (when (some #(= id (chave %)) atuais)
      (throw (err/error :move_already_known {:slug id})))
    (if (< (count atuais) maximo-golpes)
      (let [aprendido (preparar-aprendizado registro golpe nil)]
        {:resultado :aprendido
         :registro aprendido})
      (let [salvo (golpe->registro golpe)
            fila (conj (mapv golpe->registro (ofertas registro)) salvo)
            hist (assoc (or (:historico-golpes registro) {}) id salvo)]
        {:resultado :pendente
         :registro (-> registro
                       (assoc :ofertas-golpes fila
                              :golpes-oferecidos fila
                              :historico-golpes hist))}))))

(defn recusar-oferta
  "Remove a primeira oferta da fila."
  [registro]
  (let [fila (ofertas registro)]
    (when (empty? fila)
      (throw (err/error :no_move_offer {})))
    (let [restante (mapv golpe->registro (vec (rest fila)))]
      (assoc registro
             :ofertas-golpes restante
             :golpes-oferecidos restante))))

(defn remover-golpe
  "Remove golpe no slot (0-based); exige ≥2 golpes e preserva STAB."
  [registro slot]
  (let [atuais (mapv registro->golpe (or (:golpes registro) []))
        tipos (or (:tipos registro) [])]
    (when (< (count atuais) 2)
      (throw (err/error :cannot_forget_last_move {:count (count atuais)})))
    (when-not (and (integer? slot) (<= 0 slot) (< slot (count atuais)))
      (throw (err/error :invalid_move_slot {:slot slot :count (count atuais)})))
    (let [golpe (nth atuais slot)
          restantes (vec (concat (subvec atuais 0 slot) (subvec atuais (inc slot))))]
      (when-not (some #(ataque-do-tipo? % tipos) restantes)
        (throw (err/error :must_keep_stab_move {:tipos tipos})))
      (-> registro
          (assoc :golpes (mapv golpe->registro restantes))
          (assoc-in [:historico-golpes (chave golpe)] (golpe->registro golpe))
          (update :golpes-removidos
                  #(vec (distinct (conj (vec (or % [])) (chave golpe)))))))))

(defn opcoes-reaprender
  "Slugs reaprendíveis: histórico + removidos − conhecidos − pendentes."
  [registro]
  (let [conhecidos (set (map chave (mapv registro->golpe (or (:golpes registro) []))))
        pendentes (set (map chave (ofertas registro)))
        hist-keys (map identificador (keys (or (:historico-golpes registro) {})))
        rem (map identificador (or (:golpes-removidos registro) []))]
    (->> (concat hist-keys rem)
         distinct
         (remove #(or (contains? conhecidos %) (contains? pendentes %)))
         sort
         vec)))

(defn golpe-do-historico
  [registro slug]
  (let [id (identificador slug)
        raw (get (or (:historico-golpes registro) {}) id
                 (get (or (:historico-golpes registro) {}) slug))]
    (when raw (registro->golpe raw))))