(ns pokemon.infrastructure.datomic.economy-store
  "TX builders do ledger operacional (migration 028). Sem efeitos externos."
  (:require [pokemon.domain.economy :as economy]
            [pokemon.domain.errors :as err]
            [pokemon.domain.shop :as shop]))

(defn entity->economy-save
  "Projeta attrs de economia a partir da entidade Datomic (:save/*)."
  [e]
  (when e
    (cond-> {:coins (long (:save/coins e 0))
             :slot-id (:save/slot-id e)
             :origin (:save/origin e)
             :legacy-version (:save/legacy-version e)
             :save-id (:save/slot-id e)}
      (some? (:save/economy-status e))
      (assoc :economy-status (:save/economy-status e))
      (some? (:save/coins-operational e))
      (assoc :coins-operational (long (:save/coins-operational e)))
      (some? (:save/coins-operational-earned e))
      (assoc :coins-operational-earned (long (:save/coins-operational-earned e)))
      (some? (:save/coins-operational-spent e))
      (assoc :coins-operational-spent (long (:save/coins-operational-spent e)))
      (some? (:save/coins-historical-status e))
      (assoc :coins-historical-status (:save/coins-historical-status e))
      (some? (:save/coins-historical-amount e))
      (assoc :coins-historical-amount (long (:save/coins-historical-amount e))))))

(defn- operational-attr-missing?
  "Datomic: ausência de attr ≠ 0. `entity->economy-save` omite a chave quando nil."
  [save-map]
  (not (contains? save-map :coins-operational)))

(defn- bootstrap-status-tx
  "CAS nil→status/histórico apenas quando economy-status ainda não existe."
  [sk save-map]
  (when-not (economy/ledger-bootstrapped? save-map)
    (let [b (economy/bootstrap-state save-map)]
      [[:db/cas [:save/key sk] :save/economy-status nil (:economy-status b)]
       [:db/cas [:save/key sk] :save/coins-historical-status nil
        (:coins-historical-status b)]])))

(defn apply-delta-tx
  "Gera TX de crédito/débito operacional + linha de ledger idempotente.
   `:save` — entidade Datomic ou mapa com :save/* / chaves de economy.
   Retorna {:tx :operational-before :operational-after :spendable-after :mirrored?}.

   Invariante Datomic: nunca CAS `0→N` quando o attr operacional está ausente
   (`nil`). Isso gerava `:writer_fence_conflict` permanente no golpe fatal da
   raide (recompensa) — Compare failed: 0 vs nil."
  [{:keys [sk save delta operation-id source role agora]}]
  (when-not (and operation-id (string? (str operation-id)) (pos? (count (str operation-id))))
    (throw (err/error :missing_operation_id {:source source})))
  (let [delta (long delta)]
    (if (zero? delta)
      {:tx []
       :operational-before (economy/coins-operational
                            (economy/with-ledger-defaults
                              (if (:save/coins save)
                                (entity->economy-save save)
                                save)))
       :operational-after nil
       :spendable-after nil
       :mirrored? false}
      (let [save-m (if (or (:save/key save) (:save/coins save) (contains? save :save/coins))
                     (entity->economy-save save)
                     save)
            status-boot (or (bootstrap-status-tx sk save-m) [])
            save' (economy/with-ledger-defaults save-m)
            applied (economy/apply-operational-delta save' delta)
            before (:operational-before applied)
            after (:coins-operational applied)
            op-missing? (operational-attr-missing? save-m)
            kind (if (pos? delta) "credit" "debit")
            amount (long (Math/abs delta))
            role (or role "primary")
            at (if (instance? java.util.Date agora)
                 agora
                 (java.util.Date. (long (or agora (System/currentTimeMillis)))))
            lk (economy/ledger-key sk (str operation-id) role)
            mirror? (:mirror-legacy-coins? applied)
            legacy-coins (long (or (:coins save-m) (:save/coins save 0) 0))
            ;; Attr ausente: um único CAS nil→after (não nil→0 e 0→after no mesmo TX).
            op-cas (if op-missing?
                     [[:db/cas [:save/key sk] :save/coins-operational nil after]
                      [:db/cas [:save/key sk] :save/coins-operational-earned nil
                       (:coins-operational-earned applied)]
                      [:db/cas [:save/key sk] :save/coins-operational-spent nil
                       (:coins-operational-spent applied)]]
                     [[:db/cas [:save/key sk] :save/coins-operational before after]
                      [:db/add [:save/key sk] :save/coins-operational-earned
                       (:coins-operational-earned applied)]
                      [:db/add [:save/key sk] :save/coins-operational-spent
                       (:coins-operational-spent applied)]])
            tx (cond-> (into (vec status-boot) op-cas)
                 true
                 (conj {:coin-ledger/key lk
                        :coin-ledger/save [:save/key sk]
                        :coin-ledger/operation-id (str operation-id)
                        :coin-ledger/role role
                        :coin-ledger/kind kind
                        :coin-ledger/source (str (or source "reward"))
                        :coin-ledger/amount amount
                        :coin-ledger/balance-after after
                        :coin-ledger/at at})
                 ;; trusted/reconciled: espelha :save/coins para leitores legados.
                 ;; unknown-pending: NUNCA altera :save/coins (placeholder/histórico).
                 mirror?
                 (conj [:db/cas [:save/key sk] :save/coins legacy-coins
                        (+ legacy-coins delta)]))]
        {:tx tx
         :operational-before (if op-missing? 0 before)
         :operational-after after
         :spendable-after after
         :mirrored? mirror?
         :economy-status (:economy-status applied)
         :operational-bootstrapped? op-missing?}))))

(defn assert-debit!
  "Valida débito contra saldo comprovado (domínio) antes do TX."
  [save-m amount]
  (economy/assert-can-spend! save-m amount))

(defn spendable-from-entity
  [e]
  (economy/spendable-coins (entity->economy-save e)))

(defn new-save-ledger-defaults
  "Attrs iniciais para ensure-save! de slot trusted novo."
  []
  {:save/economy-status "trusted"
   :save/coins-operational 0
   :save/coins-operational-earned 0
   :save/coins-operational-spent 0
   :save/coins-historical-status "none"})

(defn recovery-ledger-defaults
  "Attrs para import recovery: operacional zero; não promove histórico."
  []
  {:save/economy-status "unknown-pending"
   :save/coins-operational 0
   :save/coins-operational-earned 0
   :save/coins-operational-spent 0
   :save/coins-historical-status "unknown"})

(defn economia-mapa
  "DTO de leitura para bridge/perfil."
  [save-m]
  (let [s (economy/with-ledger-defaults save-m)
        st (economy/economy-status s)
        spend (economy/spendable-coins s)]
    {:economy-status st
     :coins-operational (economy/coins-operational s)
     :coins-operational-earned (long (or (:coins-operational-earned s) 0))
     :coins-operational-spent (long (or (:coins-operational-spent s) 0))
     :coins-historical-status (economy/historical-status s)
     :coins-historical-amount (economy/coins-historical-amount s)
     :coins-spendable spend
     :economia-confiavel (shop/economia-confiavel? s)
     :coins-status (when (= st "unknown-pending") "unknown-pending")}))
