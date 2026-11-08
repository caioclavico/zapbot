(ns pokemon.domain.species-hp-migrate
  "Política de transição legado → species-hp-v1 (pura / idempotente).

  NÃO executa em produção. Não altera HP em leitura.
  Pensada para um processo transacional futuro (batch por save) com GO."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.species-base :as species-base]
            [pokemon.domain.species-hp :as species-hp]))

(def process-version "species-hp-migrate-v1")

(defn ocupado-em-combate?
  "True se o registro deve ser excluído do lote (batalha/raid ativa)."
  [registro]
  (boolean (or (:battle-active? registro)
               (:raid-active? registro)
               (:ocupado-combate? registro)
               (= :skip-combat (:migrate-skip registro)))))

(defn plano-registro
  "Calcula plano de conversão sem mutar. Idempotente se já v1.
  Espécie sem base → lança :species_hp_base_missing (usar plano-seguro para quarentena)."
  [registro]
  (let [species (or (:species registro) (:nome registro))
        nivel (or (:nivel registro) (:level registro) 1)
        versao (str (or (:hp-formula-version registro) ""))
        ja-v1? (= versao species-hp/formula-version)]
    (cond
      (ocupado-em-combate? registro)
      {:status :skip-combat
       :reason :active-combat
       :registro registro
       :process-version process-version}

      ja-v1?
      {:status :noop
       :reason :already-v1
       :registro registro
       :process-version process-version}

      :else
      (let [base (or (:hp-base registro)
                     (when (species-base/base-hp-known? species)
                       (species-base/base-hp species)))]
        (when (nil? base)
          (throw (err/error :species_hp_base_missing
                            {:species species
                             :process process-version})))
        (let [novo (species-hp/converter-registro
                    (assoc registro :hp-base base))
              nums-mudaram? (or (not= (long (:hp registro 0)) (long (:hp novo)))
                                (not= (long (:hp-atual registro 0)) (long (:hp-atual novo))))
              meta-mudou? (or (not= (str (or (:hp-formula-version registro) ""))
                                    (str (:hp-formula-version novo)))
                              (not= (long (or (:hp-base registro) -1))
                                    (long (:hp-base novo -1))))]
          {:status (if (or nums-mudaram? meta-mudou?) :convert :noop)
           :reason (cond nums-mudaram? :formula-upgrade
                         meta-mudou? :metadata-upgrade
                         :else :same-numbers)
           :antes {:hp (:hp registro) :hp-atual (:hp-atual registro)
                   :hp-formula-version versao}
           :depois (select-keys novo [:hp :hp-atual :hp-base :hp-formula-version])
           :registro novo
           :process-version process-version})))))
(defn plano-seguro
  "Como plano-registro, mas espécies desconhecidas → :quarantine (sem throw)."
  [registro]
  (try
    (plano-registro registro)
    (catch clojure.lang.ExceptionInfo ex
      (if (= :species_hp_base_missing (err/error-code ex))
        {:status :quarantine
         :reason :species-base-missing
         :species (or (:species registro) (:nome registro))
         :registro registro
         :process-version process-version}
        (throw ex)))))

(defn aplicar-plano
  "Aplica plano em memória (retorno = registro). Idempotente.
  Planos :quarantine / :skip-combat devolvem o registro original."
  [plano]
  (case (:status plano)
    (:quarantine :skip-combat) (:registro plano)
    (:registro plano)))

(defn converter-colecao
  "Converte vetor de registros. Falha fechada se qualquer espécie sem base."
  [registros]
  (mapv (comp aplicar-plano plano-registro) registros))

(defn migrar-lote
  "Processa até `tamanho` registros a partir de `offset`.
  Retorna recibo idempotente com contagens — puro, sem I/O."
  [registros {:keys [offset tamanho batch-id]
              :or {offset 0 tamanho 50 batch-id "batch"}}]
  (let [slice (->> registros (drop offset) (take tamanho) vec)
        planos (mapv plano-seguro slice)
        agrup (group-by :status planos)
        convertidos (mapv :registro (get agrup :convert []))
        noops (mapv :registro (get agrup :noop []))
        quarentena (mapv #(select-keys % [:species :reason :registro])
                         (get agrup :quarantine []))
        skips (mapv :registro (get agrup :skip-combat []))]
    {:batch-id batch-id
     :process-version process-version
     :offset (long offset)
     :tamanho (count slice)
     :convert (count convertidos)
     :noop (count noops)
     :quarantine (count quarentena)
     :skip-combat (count skips)
     :planos planos
     :registros-convertidos convertidos
     :quarentena quarentena
     :idempotent-receipt
     {:batch-id batch-id
      :offset (long offset)
      :convert (count convertidos)
      :noop (count noops)
      :quarantine (count quarentena)
      :skip-combat (count skips)}}))
