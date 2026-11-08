(ns pokemon.domain.species-hp-migrate
  "Política de transição legado → species-hp-v1 (pura / idempotente).

  NÃO executa em produção. Não altera HP em leitura.
  Pensada para um processo transacional futuro (batch por save) com GO."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.species-base :as species-base]
            [pokemon.domain.species-hp :as species-hp]))

(def process-version "species-hp-migrate-v1")

(defn plano-registro
  "Calcula plano de conversão sem mutar. Idempotente se já v1."
  [registro]
  (let [species (or (:species registro) (:nome registro))
        nivel (or (:nivel registro) (:level registro) 1)
        versao (str (or (:hp-formula-version registro) ""))
        ja-v1? (= versao species-hp/formula-version)]
    (if ja-v1?
      {:status :noop
       :reason :already-v1
       :registro registro
       :process-version process-version}
      (let [base (or (:hp-base registro)
                     (when (species-base/base-hp-known? species)
                       (species-base/base-hp species)))]
        (when (nil? base)
          (throw (err/error :species_hp_base_missing
                            {:species species
                             :process process-version})))
        (let [novo (species-hp/converter-registro
                    (assoc registro :hp-base base))
              mudou? (or (not= (long (:hp registro 0)) (long (:hp novo)))
                         (not= (long (:hp-atual registro 0)) (long (:hp-atual novo))))]
          {:status (if mudou? :convert :noop)
           :reason (if mudou? :formula-upgrade :same-numbers)
           :antes {:hp (:hp registro) :hp-atual (:hp-atual registro)
                   :hp-formula-version versao}
           :depois (select-keys novo [:hp :hp-atual :hp-base :hp-formula-version])
           :registro novo
           :process-version process-version})))))

(defn aplicar-plano
  "Aplica plano em memória (retorno = registro). Idempotente."
  [plano]
  (:registro plano))

(defn converter-colecao
  "Converte vetor de registros. Falha fechada se qualquer espécie sem base."
  [registros]
  (mapv (comp aplicar-plano plano-registro) registros))
