(ns zapbot.curiosidades
  "Comando !curiosidade: fatos educativos gerados por IA com antirrepetição."
  (:require [zapbot.conteudo-dinamico :as conteudo]))

(defn curiosidade-aleatoria
  ([] (curiosidade-aleatoria ""))
  ([tema] (conteudo/gerar! :curiosidade tema)))
