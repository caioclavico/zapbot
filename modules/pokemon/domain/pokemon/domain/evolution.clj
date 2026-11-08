(ns pokemon.domain.evolution
  "Aplicação de evolução no registro — espelha treinador/evoluir-no-indice!.
  A resolução da cadeia (PokeAPI) permanece fora do domínio; o caller passa
  os stats já escalados da espécie evoluída.
  HP: quando a base oficial é conhecida, a JVM sobrescreve o HP do DTO Node
  com species-hp-v1 (ataque/defesa do caller permanecem)."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.species-base :as species-base]
            [pokemon.domain.species-hp :as species-hp]))

(defn elegivel-nivel?
  "Condições mínimas espelhando proxima-evolucao (nível + amizade).
  Demais flags especiais (chuva, move, etc.) ficam a cargo do caller."
  [registro {:keys [nivel-min amizade-min]}]
  (and (>= (long (:nivel registro 1)) (long (or nivel-min 1)))
       (>= (long (:amizade registro 70)) (long (or amizade-min 0)))))

(defn canonizar-dados-hp
  "Se a espécie-alvo tiver HP base conhecido, recalcula `:hp` via species-hp-v1
  no nível do registro e força política proporcional. Sem base → dados intactos."
  [registro dados]
  (let [species (or (:nome-novo dados) (:species dados)
                    (get dados "nome-novo") (get dados "species"))
        nivel (long (:nivel registro 1))
        base (or (:hp-base dados) (get dados "hp-base") (get dados "hpBase")
                 (when (and species (species-base/base-hp-known? species))
                   (species-base/base-hp species)))]
    (if-not base
      dados
      (let [mx (species-hp/hp-max base nivel)]
        (assoc dados
               :hp mx
               :hp-base (long base)
               :hp-formula-version species-hp/formula-version
               :politica-hp :proporcional)))))

(defn aplicar-evolucao
  "Substitui campos de espécie; golpes/status/nível preservados.

  Política de HP:
  - default / `:incremento` (legado): hp-atual sobe pelo Δ do max; desmaiado=0
  - `:proporcional` (species-hp-v1): preserva razão atual/max; desmaiado=0; sem cura automática

  Sem alterar ataque/defesa além dos valores fornecidos pelo caller.
  DTOs Node com HP suavizado são canonizados quando a base oficial é conhecida."
  [registro dados]
  (when-not registro
    (throw (err/error :pokemon_not_found {})))
  (let [dados (canonizar-dados-hp registro dados)
        {:keys [nome-novo imagem imagem-shiny tipos habilidade
                hp ataque defesa atq-esp def-esp veloc
                hp-base hp-formula-version politica-hp]} dados
        tipos (vec tipos)
        golpes (moves/garantir-ataque-do-tipo
                (mapv moves/registro->golpe (or (:golpes registro) []))
                tipos)
        hp-atual (long (:hp-atual registro 0))
        hp-antigo (long (:hp registro 0))
        hp-novo (long hp)
        politica (or politica-hp
                     (when (= (str hp-formula-version) species-hp/formula-version)
                       :proporcional)
                     :incremento)
        hp-atual'
        (case politica
          :proporcional
          (:hp-atual (species-hp/converter-proporcional hp-atual hp-antigo hp-novo))
          ;; legado
          (if (pos? hp-atual)
            (+ hp-atual (- hp-novo hp-antigo))
            0))]
    (cond-> (-> registro
                (assoc :nome nome-novo
                       :species nome-novo
                       :imagem (if (:shiny registro) (or imagem-shiny imagem) imagem)
                       :imagem-shiny imagem-shiny
                       :tipos tipos
                       :habilidade habilidade
                       :hp hp-novo
                       :ataque (long ataque)
                       :defesa (long defesa)
                       :atq-esp (long atq-esp)
                       :def-esp (long def-esp)
                       :veloc (long veloc)
                       :golpes (mapv moves/golpe->registro golpes)
                       :hp-atual (max 0 (min hp-novo (long hp-atual')))))
      (some? hp-base) (assoc :hp-base (long hp-base))
      (some? hp-formula-version) (assoc :hp-formula-version (str hp-formula-version)))))