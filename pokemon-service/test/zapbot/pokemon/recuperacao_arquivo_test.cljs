(ns zapbot.pokemon.recuperacao-arquivo-test
  (:require [cljs.test :refer-macros [deftest is]]
            [zapbot.pokemon.recuperacao :as recuperacao]))

(deftest exemplo-real-local-privado
  (if-let [manifesto (aget js/process.env "LEGACY_VALIDATION_MANIFEST")]
    (try
      (let [fs (js/require "node:fs")
            meta (js->clj (js/JSON.parse (.readFileSync fs manifesto "utf8")))
            antigo (.readFileSync fs (get meta "legacyFile") "utf8")
            atual (js->clj (js/JSON.parse (.readFileSync fs (get meta "currentFile") "utf8")))
            grupo (get meta "chatId") jogador (get meta "playerId")
            dados (case (get meta "format")
                    "partition" {:grupo grupo :particionado [antigo]}
                    "state" {:antigo [antigo]})
            resultado (recuperacao/localizar dados grupo jogador)
            resumo-antigo (when (= :encontrado (:status resultado)) (recuperacao/resumo (:conta resultado)))
            resumo-atual (recuperacao/resumo (get-in atual [grupo jogador]))]
        ;; Assert booleans only: failure output must never reveal player data.
        (is (true? (= :encontrado (:status resultado))) "Identidade exata não validada.")
        (is (true? (= (get meta "expectedLegacySummary") resumo-antigo)) "Resumo antigo divergente.")
        (is (true? (= (get meta "expectedCurrentSummary") resumo-atual)) "Resumo atual divergente.")
        (is (true? (= :ausente (:status (recuperacao/localizar dados "invalid-group" jogador))))
            "Isolamento por grupo não validado.")
        (is (true? (= :ausente (:status (recuperacao/localizar dados grupo "invalid-player"))))
            "Isolamento por jogador não validado."))
      (catch :default _ (is false "Não foi possível validar os arquivos privados; conteúdo omitido.")))
    (js/console.warn "[LegacyValidation] PENDENTE: nenhum arquivo real local fornecido.")))
