(ns zapbot.piadas
  "Comando !piada: humor brasileiro variado com histórico antirrepetição."
  (:require [zapbot.conteudo-dinamico :as conteudo]))

(defn piada-aleatoria
  ([] (piada-aleatoria ""))
  ([tema] (conteudo/gerar! :piada tema)))
