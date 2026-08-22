(ns zapbot.resetar-pokemon
  "Script pra rodar UMA VEZ na VM (não daqui do sandbox - precisa alcançar
  o Cassandra de produção): zera só os dados de !pokemon (zapbot.treinador
  - time capturado, nível/evolução de cada pokémon, ativo, cooldown de
  caçada e nível de treinador de TODOS os jogadores em todos os chats).
  NÃO mexe em !loja (moedas/inventário) nem no !rank (placar geral).
  Apagar depois de usar."
  (:require [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]))

(defn -main [& _]
  (p/let [_ (armazenamento/iniciar!)]
    (js/console.log "Estado anterior de 'treinador':" (pr-str (armazenamento/obter "treinador")))
    (armazenamento/salvar! "treinador" {})
    (js/console.log "-> 'treinador' zerado (times/níveis/cooldowns de todo mundo apagados).")
    (p/let [_ (p/create (fn [resolve _] (js/setTimeout resolve 3000)))]
      (js/console.log "FIM DO RESET - confirme com: docker exec -it <container_cassandra> cqlsh -e \"SELECT * FROM zapbot.estado WHERE chave='treinador'\""))))
