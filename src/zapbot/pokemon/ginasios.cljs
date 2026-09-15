(ns zapbot.pokemon.ginasios
  "Ocupações persistentes por chat. O time defensor é uma cópia da escalação."
  (:require [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.loja :as loja]))

(defonce ^:private ocupacoes (atom (or (armazenamento/obter "ginasios") {})))
(armazenamento/registrar! "ginasios" ocupacoes)

(defn lider [cid id] (get-in @ocupacoes [cid id]))

(defn liderados [cid pid]
  (filter (fn [[_ ocupacao]] (= pid (get ocupacao "pid"))) (get @ocupacoes cid)))

(defn recompensa-permanencia [ocupacao agora]
  (if (and ocupacao (> (- agora (get ocupacao "desde" agora)) (* 6 60 60 1000))) 50 0))

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
      (swap! ocupacoes assoc-in [cid id] nova)
      (when (pos? moedas) (loja/creditar-quantia! cid (get anterior "pid") moedas))
      (armazenamento/salvar! "ginasios" @ocupacoes)
      {:anterior anterior :moedas moedas})))
