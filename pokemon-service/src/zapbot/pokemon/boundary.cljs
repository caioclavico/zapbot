(ns zapbot.pokemon.boundary
  "Porta de saída neutra: bytes e dados, independente do transporte do usuário."
  (:require [promesa.core :as p]))

(defn midia [mime buffer filename]
  {:mime mime :buffer buffer :filename filename})

(defn png-buffer!
  "Mede a execução real da pipeline Sharp, incluindo resize/composição."
  [^js pipeline]
  (let [metricas (js/require "../runtime/metrics.cjs")]
    ((.-measure metricas) "image_processing_ms" #(.toBuffer pipeline))))

(defn resposta [conteudo opcoes]
  (let [opcoes (if (map? opcoes) opcoes (js->clj opcoes :keywordize-keys true))]
    (cond-> (cond
              (string? conteudo) {:texto conteudo}
              (:mime conteudo) {:media conteudo}
              (map? conteudo) conteudo
              :else {})
      (:caption opcoes) (assoc :texto (:caption opcoes))
      (seq (:mentions opcoes)) (assoc :mentions (vec (:mentions opcoes))))))

(defn emitir!
  ([contexto conteudo] (emitir! contexto conteudo nil nil))
  ([contexto conteudo _ opcoes]
   (if-let [emitir (:emit! contexto)]
     (p/promise (emitir (resposta conteudo opcoes)))
     (p/resolved nil))))
