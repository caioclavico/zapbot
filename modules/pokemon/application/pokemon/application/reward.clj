(ns pokemon.application.reward
  "Recompensas M3.2 — caminho único de gameplay com fence;
  admin/seed explícito e separado."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.reward :as reward]))

(defn apply-gameplay-reward!
  "Aplica recompensa no save congelado em `writer-ctx`.

  writer-ctx: {:chat-id :player-id :save-id :fence-generation}
  request: {:operation-id :coins-delta :pokemon :item :trainer-xp-delta :trainer-level}

  A TX valida save ativo + fence e grava recibo com payload-hash."
  [store writer-ctx request]
  (let [merged (merge request
                      (select-keys writer-ctx
                                   [:chat-id :player-id :save-id :fence-generation]))]
    (reward/validate-gameplay-request! merged)
    (when (and (:save-id request)
               (not= (:save-id request) (:save-id writer-ctx)))
      (throw (err/error :reward_save_mismatch
                        {:writer (:save-id writer-ctx)
                         :request (:save-id request)})))
    (ports/apply-gameplay-reward! store writer-ctx request)))

(defn apply-gameplay-reward-on-active!
  "Congela o save ativo agora e aplica a recompensa (comandos síncronos)."
  [store chat-id player-id request]
  (let [w (writer/freeze-writer store chat-id player-id)]
    (apply-gameplay-reward! store w request)))

(defn admin-seed-reward!
  "Seed/harness/migração — SEM fence. Nome explícito; não é comando de jogo."
  [store request]
  (reward/validate-admin-request! request)
  (ports/admin-seed-reward! store request))
