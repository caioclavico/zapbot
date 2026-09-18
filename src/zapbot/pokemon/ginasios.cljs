(ns zapbot.pokemon.ginasios
  "Ocupações persistentes por chat. O time defensor é uma cópia da escalação."
  (:require [zapbot.armazenamento :as armazenamento]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.loja :as loja]))

(defonce ^:private ocupacoes (atom (or (armazenamento/obter "ginasios") {})))
(armazenamento/registrar! "ginasios" ocupacoes)

(def motivacao-maxima 100)
(def motivacao-minima 20)
(def perda-motivacao-por-hora 5)
(def perda-motivacao-por-defesa 12)

(defn- salvar-ocupacoes! []
  (armazenamento/salvar! "ginasios" @ocupacoes))

(defn lider [cid id] (get-in @ocupacoes [cid id]))

(defn liderados [cid pid]
  (filter (fn [[_ ocupacao]] (= pid (get ocupacao "pid"))) (get @ocupacoes cid)))

(defn motivacoes
  "Motivação atual dos três defensores. A queda por tempo é calculada sob
  demanda, então não exige cron nem timers para cada ginásio."
  [ocupacao agora]
  (let [quantidade (count (get ocupacao "time"))
        base (vec (or (seq (get ocupacao "motivacao"))
                      (repeat quantidade motivacao-maxima)))
        atualizado-em (get ocupacao "motivacao-em" (get ocupacao "desde" agora))
        horas (js/Math.floor (/ (max 0 (- agora atualizado-em)) (* 60 60 1000)))
        perda (* horas perda-motivacao-por-hora)]
    (mapv #(max motivacao-minima (- (or % motivacao-maxima) perda)) base)))

(defn time-defensor
  "Aplica a motivação ao time copiado do ginásio. Em 100% mantém os atributos;
  no mínimo de 20% luta com 60% da força, sem alterar a coleção do treinador."
  [ocupacao agora]
  (mapv (fn [registro motivacao]
          (let [[pokemon _ _] (treinador/registro->pokemon registro)
                fator (+ 0.5 (* 0.5 (/ motivacao motivacao-maxima)))]
            (-> (reduce (fn [p atributo]
                          (update p atributo #(max 1 (js/Math.round (* % fator)))))
                        pokemon [:hp :ataque :defesa :atq-esp :def-esp :veloc])
                (assoc :motivacao-ginasio motivacao))))
        (get ocupacao "time")
        (motivacoes ocupacao agora)))

(defn desgastar-defesa!
  "Uma defesa vencida cansa todo o time, além do desgaste já acumulado pelo tempo."
  [cid id ocupacao agora]
  (when (= ocupacao (lider cid id))
    (let [novas (mapv #(max motivacao-minima (- % perda-motivacao-por-defesa))
                      (motivacoes ocupacao agora))]
      (swap! ocupacoes update-in [cid id]
             #(assoc % "motivacao" novas "motivacao-em" agora))
      (salvar-ocupacoes!)
      novas)))

(defn- recuperar-motivacao!
  [cid pid id indice agora item consumir!]
  (let [ocupacao (lider cid id)
        atuais (when ocupacao (motivacoes ocupacao agora))]
    (cond
      (nil? ocupacao) {:status :sem-lider}
      (not= pid (get ocupacao "pid")) {:status :nao-e-lider}
      (not (and (number? indice) (<= 0 indice (dec (count atuais))))) {:status :indice-invalido}
      (>= (get atuais indice) motivacao-maxima) {:status :motivacao-cheia}
      :else
      (if-let [pontos (consumir! cid pid)]
        (let [antes (get atuais indice)
              depois (min motivacao-maxima (+ antes pontos))
              novas (assoc atuais indice depois)]
          (swap! ocupacoes update-in [cid id]
                 #(assoc % "motivacao" novas "motivacao-em" agora))
          (salvar-ocupacoes!)
          {:status :ok :antes antes :depois depois
           :item item :nome (get-in ocupacao ["time" indice "nome"])})
        {:status :sem-item :item item}))))

(defn usar-pocao!
  "Consome uma Poção de Vida e recupera 40 pontos de motivação."
  [cid pid id indice agora]
  (recuperar-motivacao! cid pid id indice agora :pocao
                        (fn [c p] (some-> (loja/usar-pocao! c p) (* 100) js/Math.round))))

(defn usar-fruta!
  "Consome Fruta Frambo comum ou dourada para recuperar motivação."
  [cid pid id indice agora fruta]
  (recuperar-motivacao! cid pid id indice agora (keyword fruta)
                        #(loja/usar-fruta! %1 %2 fruta)))

(defn recompensa-permanencia [ocupacao agora]
  (if (and ocupacao (> (- agora (get ocupacao "desde" agora)) (* 6 60 60 1000))) 50 0))

(defn duracao-ms [ocupacao agora]
  (max 0 (- agora (get ocupacao "desde" agora))))

(defn formatar-duracao [ms]
  (let [total-segundos (js/Math.floor (/ (max 0 ms) 1000))
        horas (js/Math.floor (/ total-segundos 3600))
        minutos (js/Math.floor (/ (mod total-segundos 3600) 60))
        segundos (mod total-segundos 60)
        partes (cond-> []
                 (pos? horas) (conj (str horas "h"))
                 (or (pos? minutos) (pos? horas)) (conj (str minutos "m"))
                 (or (pos? segundos) (zero? total-segundos)) (conj (str segundos "s")))]
    (str/join " " partes)))

(defn xp-permanencia
  "XP para cada Pokémon defensor ao sair do ginásio.
  Cresce com o tempo: +1 XP a cada 30 minutos completos, mínimo +1 se ficou
  algum tempo e limite de 24 XP por queda."
  [ocupacao agora]
  (let [ms (duracao-ms ocupacao agora)]
    (if (pos? ms)
      (min 24 (max 1 (js/Math.floor (/ ms (* 30 60 1000)))))
      0)))

(declare registrar-permanencia!)

(defn ocupar!
  "Troca o líder somente se ainda for o enfrentado. Paga o anterior ao cair."
  [cid id anterior pid nome indices agora]
  (when (and (= anterior (lider cid id)) (not= pid (get anterior "pid")))
    (let [registros (mapv #(get (treinador/equipe cid pid) %) indices)
          moedas (recompensa-permanencia anterior agora)
          xp (xp-permanencia anterior agora)
          nova {"pid" pid "nome" nome "time" (vec registros) "desde" agora
                "motivacao" (vec (repeat 3 motivacao-maxima)) "motivacao-em" agora}]
      (when-not (and (= 3 (count indices)) (= 3 (count (set indices))) (every? some? registros))
        (throw (js/Error. "Time de ocupação inválido.")))
      ;; Fora da coleção utilizável, como na enfermaria: não pode ser alterado.
      (doseq [idx (sort > indices)] (treinador/remover-pokemon! cid pid idx))
      (let [pid-anterior (get anterior "pid")
            inicio (count (treinador/equipe cid pid-anterior))]
        (doseq [registro (get anterior "time")]
          (treinador/receber-doacao! cid pid-anterior registro))
        (when (pos? xp)
          (doseq [idx (range inicio (+ inicio (count (get anterior "time"))))]
            (treinador/ganhar-xp-no-indice! cid pid-anterior idx xp))))
      (registrar-permanencia! cid id anterior agora)
      (swap! ocupacoes assoc-in [cid id] nova)
      (when (pos? moedas) (loja/creditar-quantia! cid (get anterior "pid") moedas))
      (salvar-ocupacoes!)
      {:anterior anterior :moedas moedas :xp xp :tempo-ms (duracao-ms anterior agora)})))

(defonce ^:private estatisticas (atom (or (armazenamento/obter "ginasios-estatisticas") {})))
(armazenamento/registrar! "ginasios-estatisticas" estatisticas)

(defn- salvar-estatisticas! []
  (armazenamento/salvar! "ginasios-estatisticas" @estatisticas))

(defn registrar-permanencia! [cid id anterior agora]
  (when-let [pid (get anterior "pid")]
    (swap! estatisticas update-in [cid id "lideres" pid]
           #(-> (or % {})
                (assoc "nome" (get anterior "nome"))
                (update "tempo-ms" (fnil + 0) (max 0 (- agora (get anterior "desde" agora))))))
    (salvar-estatisticas!)))

(defn registrar-resultado! [cid id ocupante desafiante nome venceu? agora]
  (let [entrada {"quando" agora "desafiante" desafiante "nome" nome
                 "lider" (get ocupante "nome" "Líder NPC") "venceu" venceu?}]
    (when (and ocupante (not venceu?))
      (desgastar-defesa! cid id ocupante agora))
    (swap! estatisticas update-in [cid id]
           (fn [estado]
             (cond-> (update (or estado {}) "historico"
                             #(vec (take-last 50 (conj (vec %) entrada))))
               (and ocupante (not venceu?))
               (update-in ["lideres" (get ocupante "pid")]
                          #(-> (or % {}) (assoc "nome" (get ocupante "nome"))
                               (update "defesas" (fnil inc 0)))))))
    (salvar-estatisticas!)))

(defn ranking-dados [cid id agora]
  (let [atual (lider cid id)
        base (get-in @estatisticas [cid id "lideres"] {})
        base (if-let [pid (get atual "pid")]
               (update base pid #(-> (or % {}) (assoc "nome" (get atual "nome"))
                                     (update "tempo-ms" (fnil + 0)
                                             (max 0 (- agora (get atual "desde" agora)))))) base)]
    (vals base)))

(defn ranking [cid id agora]
  (let [dados (ranking-dados cid id agora)
        lista (fn [chave unidade]
                (if (seq dados)
                  (str/join "\n" (map-indexed
                    (fn [i entrada]
                      (str (inc i) ". " (get entrada "nome") " — "
                           (if (= chave "tempo-ms")
                             (formatar-duracao (get entrada chave 0))
                             (get entrada chave 0)) unidade))
                    (take 10 (sort-by #(get % chave 0) > dados))))
                  "Ainda não há líderes registrados."))]
    (str "🏛️ *Ranking — " id "*\n🛡️ Defesas vencidas\n" (lista "defesas" " vitória(s)")
         "\n\n⏱️ Permanência acumulada\n" (lista "tempo-ms" "")
         "\nDefesas são contadas a partir desta atualização.")))

(defn historico [cid id]
  (str "🛡️ *Últimas defesas — " id "*\n"
       (if-let [entradas (seq (get-in @estatisticas [cid id "historico"]))]
         (str/join "\n" (for [e (take 10 (reverse entradas))]
                           (str (.toLocaleString (js/Date. (get e "quando")) "pt-BR"
                                                 #js {:timeZone config/missoes-timezone})
                                " • " (get e "nome") " × " (get e "lider") " — "
                                (if (get e "venceu") "desafiante venceu" "líder defendeu"))))
         "Nenhuma batalha registrada desde esta atualização.")))
