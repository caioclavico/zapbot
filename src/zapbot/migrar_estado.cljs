(ns zapbot.migrar-estado
  "Script descartável: lê data/estado.json (persistência antiga) e grava
  cada chave no Cassandra via zapbot.armazenamento - apagar depois de usar."
  (:require [promesa.core :as p]
            ["fs" :as fs]
            [zapbot.armazenamento :as armazenamento]))

(defn -main [& _]
  (p/let [_ (armazenamento/iniciar!)]
    (let [dados (js->clj (js/JSON.parse (.readFileSync fs "data/estado.json" "utf8")))]
      (js/console.log "Chaves a migrar:" (pr-str (keys dados)))
      (doseq [[chave valor] dados]
        (armazenamento/salvar! chave valor)
        (js/console.log "  ->" chave "gravado (assincrono, ver confirmacao no cqlsh depois)"))
      ;; espera as gravações assíncronas terminarem antes de sair
      (p/let [_ (p/create (fn [resolve _] (js/setTimeout resolve 3000)))]
        (js/console.log "FIM DA MIGRACAO")))))
