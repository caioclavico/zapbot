(ns pokemon.infrastructure.datomic.missions-store
  "TX builders de progresso/resgate de missões (A4.3)."
  (:require [pokemon.domain.capacity :as capacity]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.missions :as missions]
            [pokemon.domain.model :as model]
            [pokemon.domain.xp :as xp]))

(defn- parse-edn
  [edn-read s]
  (or (when s (edn-read s)) {}))

(defn mission-account
  "Visão de conta no formato string-keys usado pelo domínio."
  [save-m edn-read]
  {"moedas" (long (:coins save-m 0))
   "xp-missoes" (long (:xp-missoes save-m 0))
   "missoes-diarias" (parse-edn edn-read (:missoes-diarias-edn save-m))
   "missoes-semanais" (parse-edn edn-read (:missoes-semanais-edn save-m))
   "recompensas-pendentes" (parse-edn edn-read (:recompensas-pendentes-edn save-m))})

(defn- inventory-qty-map
  [inventory]
  (into {} (map (fn [it] [(:item-id it) (long (:qty it 0))]) inventory)))

(defn- bag-free-slots
  [save-m inventory]
  (let [perfil (xp/perfil-treinador save-m)
        nivel (:nivel perfil (long (:trainer-level save-m 1)))
        cap (capacity/capacidade-mochila
             nivel
             (:expansoes-mochila-50 save-m 0)
             (:capacidade-mochila-legada save-m 50))
        used (reduce + 0 (map #(long (:qty % 0)) inventory))]
    (max 0 (- (long cap) (long used)))))

(defn progress-daily-tx
  "Fragmento TX: incrementa evento diário no save."
  [sk save-m evento dia nivel edn-read]
  (let [conta (mission-account save-m edn-read)
        antes (missions/estado-do-dia conta dia nivel)
        novo (missions/registrar-evento-diario conta dia evento nivel)
        depois (get novo "missoes-diarias")
        completou? (and (missions/evento-diario? evento)
                        (let [missao (some #(when (= evento (:id %)) %)
                                           (missions/catalogo-do-dia depois))]
                          (and missao
                               (< (long (get-in antes ["progresso" evento] 0)) (:meta missao))
                               (>= (long (get-in depois ["progresso" evento] 0)) (:meta missao)))))]
    {:tx [[:db/add [:save/key sk] :save/missoes-diarias-edn (pr-str depois)]]
     :completou? (boolean completou?)
     :estado depois}))

(defn progress-weekly-tx
  [sk save-m evento valores semana edn-read]
  (let [conta (mission-account save-m edn-read)
        novo (missions/registrar-evento-semanal conta semana evento valores)
        depois (get novo "missoes-semanais")]
    {:tx [[:db/add [:save/key sk] :save/missoes-semanais-edn (pr-str depois)]]
     :estado depois}))

(defn- grant-items-tx
  "Adiciona recompensas respeitando capacidade; excesso → pendentes."
  [db sk inventory save-m recompensas inventory-adjust-tx edn-read]
  (let [livres0 (bag-free-slots save-m inventory)
        pend0 (parse-edn edn-read (:recompensas-pendentes-edn save-m))
        [adds pend' _]
        (reduce
         (fn [[adds pend livre] item-id]
           (let [qtd (long (get recompensas item-id 0))
                 recebidas (min qtd livre)
                 resto (- qtd recebidas)]
             [(cond-> adds (pos? recebidas) (assoc item-id recebidas))
              (cond-> pend (pos? resto) (update item-id (fnil + 0) resto))
              (- livre recebidas)]))
         [{} pend0 livres0]
         missions/ordem-recompensas)
        item-txs (mapcat (fn [[item-id delta]]
                           (inventory-adjust-tx db sk item-id (long delta)))
                         adds)
        pend-tx [[:db/add [:save/key sk] :save/recompensas-pendentes-edn (pr-str pend')]]]
    {:tx (into (vec item-txs) pend-tx)
     :adicionados adds
     :pendentes pend'
     :teve-pendente? (boolean (some #(pos? (long (val %))) pend'))}))

(defn build-claim-daily-tx
  [{:keys [db writer-ctx request save inventory inventory-adjust-tx edn-read agora]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        sk (id/save-key chat-id player-id save-id)
        save-m {:coins (long (:save/coins save 0))
                :xp-missoes (long (:save/xp-missoes save 0))
                :trainer-level (long (:save/trainer-level save 1))
                :expansoes-mochila-50 (long (:save/expansoes-mochila-50 save 0))
                :capacidade-mochila-legada (long (:save/capacidade-mochila-legada save 50))
                :missoes-diarias-edn (:save/missoes-diarias-edn save)
                :missoes-semanais-edn (:save/missoes-semanais-edn save)
                :recompensas-pendentes-edn (:save/recompensas-pendentes-edn save)
                :vitorias-treinador (long (:save/vitorias-treinador save 0))
                :pe-ginasios (long (:save/pe-ginasios save 0))
                :pe-raids (long (:save/pe-raids save 0))
                :xp-insignias (long (:save/xp-insignias save 0))}
        dia (or (:dia request) (missions/dia-atual
                                (java.time.Instant/ofEpochMilli
                                 (long (or agora (System/currentTimeMillis))))))
        conta (mission-account save-m edn-read)
        nivel (long (or (get-in conta ["missoes-diarias" "nivel"])
                        (:nivel (xp/perfil-treinador save-m))
                        (:trainer-level save-m 1)))
        ;; Garante faixa do dia persistida antes do resgate.
        estado (missions/estado-do-dia conta dia nivel)
        conta (assoc conta "missoes-diarias" estado)
        result (missions/resgatar-diario conta dia nivel (or (:rolls request) {}))
        _ (when (:vazio result)
            (throw (err/error :missions_nothing_to_claim {:kind "daily" :dia dia})))
        xp (long (:xp result 0))
        xp-antes (long (:xp-missoes save-m 0))
        grant (grant-items-tx db sk inventory save-m (:recompensas result)
                              inventory-adjust-tx edn-read)
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)]
    {:tx (into [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                [:db/add [:save/key sk] :save/missoes-diarias-edn
                 (pr-str (get-in result [:conta "missoes-diarias"]))]
                [:db/add [:save/key sk] :save/xp-missoes (+ xp-antes xp)]
                {:operation-receipt/id operation-id
                 :operation-receipt/kind "missions-claim-daily"
                 :operation-receipt/channel "gameplay"
                 :operation-receipt/payload-hash phash
                 :operation-receipt/player-key (id/player-key chat-id player-id)
                 :operation-receipt/save-id save-id
                 :operation-receipt/summary
                 (pr-str {:dia dia :xp xp :ids (mapv :id (:prontas result))})}]
               (:tx grant))
     :result {:operation-id operation-id
              :duplicate false
              :kind "daily"
              :dia dia
              :xp xp
              :prontas (mapv :id (:prontas result))
              :recompensas (:recompensas result)
              :pendentes (:pendentes grant)
              :teve-pendente? (:teve-pendente? grant)}}))

(defn build-claim-weekly-tx
  [{:keys [db writer-ctx request save inventory inventory-adjust-tx edn-read agora]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        sk (id/save-key chat-id player-id save-id)
        save-m {:coins (long (:save/coins save 0))
                :xp-missoes (long (:save/xp-missoes save 0))
                :trainer-level (long (:save/trainer-level save 1))
                :expansoes-mochila-50 (long (:save/expansoes-mochila-50 save 0))
                :capacidade-mochila-legada (long (:save/capacidade-mochila-legada save 50))
                :missoes-diarias-edn (:save/missoes-diarias-edn save)
                :missoes-semanais-edn (:save/missoes-semanais-edn save)
                :recompensas-pendentes-edn (:save/recompensas-pendentes-edn save)}
        dia (or (:dia request) (missions/dia-atual
                                (java.time.Instant/ofEpochMilli
                                 (long (or agora (System/currentTimeMillis))))))
        semana (or (:semana request) (missions/semana-de dia))
        conta (mission-account save-m edn-read)
        result (missions/resgatar-semanal conta semana (or (:rolls request) {}))
        _ (when (empty? (:prontas result))
            (throw (err/error :missions_nothing_to_claim {:kind "weekly" :semana semana})))
        moedas (long (:moedas result 0))
        coins (long (:save/coins save 0))
        next-coins (model/apply-coin-delta coins moedas)
        grant (grant-items-tx db sk inventory save-m (:recompensas result)
                              inventory-adjust-tx edn-read)
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)]
    {:tx (into [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                [:db/cas [:save/key sk] :save/coins coins next-coins]
                [:db/add [:save/key sk] :save/missoes-semanais-edn
                 (pr-str (get-in result [:conta "missoes-semanais"]))]
                {:operation-receipt/id operation-id
                 :operation-receipt/kind "missions-claim-weekly"
                 :operation-receipt/channel "gameplay"
                 :operation-receipt/payload-hash phash
                 :operation-receipt/player-key (id/player-key chat-id player-id)
                 :operation-receipt/save-id save-id
                 :operation-receipt/summary
                 (pr-str {:semana semana :moedas moedas :ids (mapv :id (:prontas result))})}]
               (:tx grant))
     :result {:operation-id operation-id
              :duplicate false
              :kind "weekly"
              :semana semana
              :moedas moedas
              :coins-depois next-coins
              :prontas (mapv :id (:prontas result))
              :recompensas (:recompensas result)
              :pendentes (:pendentes grant)
              :teve-pendente? (:teve-pendente? grant)}}))

(defn build-send-gift-tx
  "Consome cartão, registra missões do remetente, entrega bolas ao alvo."
  [{:keys [db writer-ctx request save inventory inventory-adjust-tx
           edn-read agora target-save target-inventory]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        target-id (:target-player-id request)
        _ (when (or (nil? target-id) (= target-id player-id))
            (throw (err/error :invalid_gift_target {:target target-id})))
        sk (id/save-key chat-id player-id save-id)
        t-save-id (:slot-id target-save (:save-id target-save))
        _ (when-not t-save-id
            (throw (err/error :no_active_save {:player-id target-id})))
        tsk (id/save-key chat-id target-id t-save-id)
        card-qty (long (or (:qty (some #(when (= "cartao-presente" (:item-id %)) %) inventory)) 0))
        _ (when-not (pos? card-qty)
            (throw (err/error :insufficient_item {:item-id "cartao-presente"})))
        roll (long (or (:roll request) (rand-int 100)))
        recompensa (cond (< roll 10) {"ultra-bola" 1}
                         (< roll 35) {"grande-bola" 2}
                         :else {"pokebola" 3})
        nivel (long (or (:nivel request)
                        (:trainer-level
                         {:trainer-level (long (:save/trainer-level save 1))} 1)))
        dia (or (:dia request) (missions/dia-atual
                                (java.time.Instant/ofEpochMilli
                                 (long (or agora (System/currentTimeMillis))))))
        semana (missions/semana-de dia)
        save-m {:coins (long (:save/coins save 0))
                :xp-missoes (long (:save/xp-missoes save 0))
                :trainer-level (long (:save/trainer-level save 1))
                :missoes-diarias-edn (:save/missoes-diarias-edn save)
                :missoes-semanais-edn (:save/missoes-semanais-edn save)
                :recompensas-pendentes-edn (:save/recompensas-pendentes-edn save)
                :expansoes-mochila-50 (long (:save/expansoes-mochila-50 save 0))
                :capacidade-mochila-legada (long (:save/capacidade-mochila-legada save 50))}
        daily (progress-daily-tx sk save-m "presentes" dia nivel edn-read)
        ;; Após daily, estado semanal sobre a conta atualizada.
        save-m2 (assoc save-m :missoes-diarias-edn (pr-str (:estado daily)))
        weekly (progress-weekly-tx sk save-m2 "presentes" [] semana edn-read)
        t-save-m {:expansoes-mochila-50 (long (:save/expansoes-mochila-50 target-save
                                                                          (:expansoes-mochila-50 target-save 0)))
                  :capacidade-mochila-legada (long (:save/capacidade-mochila-legada target-save
                                                                                    (:capacidade-mochila-legada target-save 50)))
                  :trainer-level (long (:save/trainer-level target-save
                                                            (:trainer-level target-save 1)))
                  :recompensas-pendentes-edn (:save/recompensas-pendentes-edn target-save
                                                                              (:recompensas-pendentes-edn target-save))}
        grant (grant-items-tx db tsk target-inventory t-save-m recompensa
                              inventory-adjust-tx edn-read)
        card-tx (inventory-adjust-tx db sk "cartao-presente" -1)
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)]
    {:tx (into (into (into (vec card-tx) (:tx daily)) (:tx weekly))
               (concat (:tx grant)
                       [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                        {:operation-receipt/id operation-id
                         :operation-receipt/kind "gift-send"
                         :operation-receipt/channel "gameplay"
                         :operation-receipt/payload-hash phash
                         :operation-receipt/player-key (id/player-key chat-id player-id)
                         :operation-receipt/save-id save-id
                         :operation-receipt/summary
                         (pr-str {:target target-id :recompensa recompensa :roll roll})}]))
     :result {:operation-id operation-id
              :duplicate false
              :target-player-id target-id
              :recompensa recompensa
              :roll roll
              :completou-diario? (:completou? daily)
              :pendentes (:pendentes grant)
              :teve-pendente? (:teve-pendente? grant)}}))
