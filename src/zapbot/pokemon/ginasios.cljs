(ns zapbot.pokemon.ginasios
  "Ocupações persistentes por chat. O time defensor é uma cópia da escalação."
  (:require [zapbot.armazenamento :as armazenamento]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.loja :as loja]))

(defonce ^:private ocupacoes (atom (or (armazenamento/obter "ginasios") {})))
(armazenamento/registrar! "ginasios" ocupacoes)

(defn lider [cid id] (get-in @ocupacoes [cid id]))

(defn liderados [cid pid]
  (filter (fn [[_ ocupacao]] (= pid (get ocupacao "pid"))) (get @ocupacoes cid)))

(defn recompensa-permanencia [ocupacao agora]
  (if (and ocupacao (> (- agora (get ocupacao "desde" agora)) (* 6 60 60 1000))) 50 0))

(declare registrar-permanencia!)

(defn ocupar!
  "Troca o líder somente se ainda for o enfrentado. Paga o anterior ao cair."
  [cid id anterior pid nome indices agora]
  (when (and (= anterior (lider cid id)) (not= pid (get anterior "pid")))
    (let [registros (mapv #(get (treinador/equipe cid pid) %) indices)
          moedas (recompensa-permanencia anterior agora)
          nova {"pid" pid "nome" nome "time" (vec registros) "desde" agora}]
      (when-not (and (= 3 (count indices)) (= 3 (count (set indices))) (every? some? registros))
        (throw (js/Error. "Time de ocupação inválido.")))
      ;; Fora da coleção utilizável, como na enfermaria: não pode ser alterado.
      (doseq [idx (sort > indices)] (treinador/remover-pokemon! cid pid idx))
      (doseq [registro (get anterior "time")]
        (treinador/receber-doacao! cid (get anterior "pid") registro))
      (registrar-permanencia! cid id anterior agora)
      (swap! ocupacoes assoc-in [cid id] nova)
      (when (pos? moedas) (loja/creditar-quantia! cid (get anterior "pid") moedas))
      (armazenamento/salvar! "ginasios" @ocupacoes)
      {:anterior anterior :moedas moedas})))

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
                             (js/Math.floor (/ (get entrada chave 0) 60000))
                             (get entrada chave 0)) unidade))
                    (take 10 (sort-by #(get % chave 0) > dados))))
                  "Ainda não há líderes registrados."))]
    (str "🏛️ *Ranking — " id "*\n🛡️ Defesas vencidas\n" (lista "defesas" " vitória(s)")
         "\n\n⏱️ Permanência acumulada\n" (lista "tempo-ms" " min")
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
