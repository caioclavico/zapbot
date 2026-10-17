(ns pokemon.domain.evolution
  "Aplicação de evolução no registro — espelha treinador/evoluir-no-indice!.
  A resolução da cadeia (PokeAPI) permanece fora do domínio; o caller passa
  os stats já escalados da espécie evoluída."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.moves :as moves]))

(defn elegivel-nivel?
  "Condições mínimas espelhando proxima-evolucao (nível + amizade).
  Demais flags especiais (chuva, move, etc.) ficam a cargo do caller."
  [registro {:keys [nivel-min amizade-min]}]
  (and (>= (long (:nivel registro 1)) (long (or nivel-min 1)))
       (>= (long (:amizade registro 70)) (long (or amizade-min 0)))))

(defn aplicar-evolucao
  "Substitui campos de espécie; golpes/status/nível preservados;
  hp-atual sobe pelo incremento do HP máximo (desmaiado permanece 0)."
  [registro {:keys [nome-novo imagem imagem-shiny tipos habilidade
                    hp ataque defesa atq-esp def-esp veloc]}]
  (when-not registro
    (throw (err/error :pokemon_not_found {})))
  (let [incremento-hp (- (long hp) (long (:hp registro 0)))
        tipos (vec tipos)
        golpes (moves/garantir-ataque-do-tipo
                (mapv moves/registro->golpe (or (:golpes registro) []))
                tipos)
        hp-atual (long (:hp-atual registro 0))]
    (-> registro
        (assoc :nome nome-novo
               :species nome-novo
               :imagem (if (:shiny registro) (or imagem-shiny imagem) imagem)
               :imagem-shiny imagem-shiny
               :tipos tipos
               :habilidade habilidade
               :hp (long hp)
               :ataque (long ataque)
               :defesa (long defesa)
               :atq-esp (long atq-esp)
               :def-esp (long def-esp)
               :veloc (long veloc)
               :golpes (mapv moves/golpe->registro golpes)
               :hp-atual (if (pos? hp-atual) (+ hp-atual incremento-hp) 0)))))
