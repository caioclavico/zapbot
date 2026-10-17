(ns pokemon.infrastructure.datomic.gym-store
  "Montagem de TX de ginásio (fora do store monolítico)."
  (:require [pokemon.domain.collection :as coll]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.domain.xp :as xp]))

(defn occupation->map
  "Nil se slot vazio (stub rev 0 sem player-key)."
  [e edn-read present?]
  (when (and (present? e) (:gym-occupation/player-key e))
    {:key (:gym-occupation/key e)
     :chat-id (:gym-occupation/chat-id e)
     :gym-id (:gym-occupation/gym-id e)
     :player-key (:gym-occupation/player-key e)
     :pid (when-let [pk (:gym-occupation/player-key e)]
            (:player-id (id/parse-player-key pk)))
     :save-id (:gym-occupation/save-id e)
     :nome (:gym-occupation/nome e)
     :desde (:gym-occupation/desde e)
     :motivacao (or (edn-read (:gym-occupation/motivacao-edn e)) [100 100 100])
     :motivacao-em (:gym-occupation/motivacao-em e)
     :time (or (edn-read (:gym-occupation/team-edn e)) [])
     :revision (long (:gym-occupation/revision e 0))}))

(defn- registro-retorno
  [reg xp-ant]
  (let [base {:id-pokemon (or (:id-pokemon reg) (get reg "id-pokemon") (coll/new-id-pokemon))
              :nome (or (:nome reg) (get reg "nome"))
              :species (or (:species reg) (:nome reg) (get reg "nome"))
              :shiny (boolean (or (:shiny reg) (get reg "shiny")))
              :hp (long (or (:hp reg) (get reg "hp") 0))
              :hp-atual 0
              :ataque (long (or (:ataque reg) (get reg "ataque") 0))
              :defesa (long (or (:defesa reg) (get reg "defesa") 0))
              :atq-esp (long (or (:atq-esp reg) (get reg "atq-esp") 0))
              :def-esp (long (or (:def-esp reg) (get reg "def-esp") 0))
              :veloc (long (or (:veloc reg) (get reg "veloc") 0))
              :nivel (long (or (:nivel reg) (get reg "nivel") 1))
              :xp-desde-nivel (long (or (:xp-desde-nivel reg) (get reg "xp-desde-nivel") 0))
              :amizade (long (or (:amizade reg) (get reg "amizade") 70))
              :tipos (vec (or (:tipos reg) (get reg "tipos") []))
              :golpes (or (:golpes reg) (get reg "golpes") [])
              :habilidade (or (:habilidade reg) (get reg "habilidade"))
              :raridade (or (:raridade reg) (get reg "raridade") "comum")}]
    (if (pos? xp-ant)
      (:registro (xp/aplicar-xp-pokemon
                  (assoc base :amizade (min 255 (+ (:amizade base) 10)))
                  xp-ant))
      base)))

(defn build-resolve-tx
  "Retorna {:tx :result} para resolve-gym-challenge!.
  slot-revision: revisão atual do stub/ocupação (0 se vazio)."
  [{:keys [db save writer-ctx request ocupacao colecao slot-revision
           query-collection save-entity player-entity
           pokemon-entity-map apply-pokemon-xp-tx inventory-adjust-tx]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        gym-id (:gym-id request)
        g (or (gym/obter-ginasio gym-id)
              (throw (err/error :unknown_gym {:gym-id gym-id})))
        pk (id/player-key chat-id player-id)
        sk (id/save-key chat-id player-id save-id)
        gk (id/gym-occupation-key chat-id gym-id)
        actual-rev (long (or slot-revision (:revision ocupacao 0)))
        insignias (or (:ginasios request) {})
        _ (when-not (gym/desbloqueado? insignias gym-id)
            (throw (err/error :gym_locked {:gym-id gym-id})))
        venceu? (boolean (:venceu? request))
        dia (or (:dia request) "1970-01-01")
        agora (long (or (:agora request) (System/currentTimeMillis)))
        premio (when venceu? (gym/registrar-insignia insignias gym-id dia))
        vit-rew (when premio (gym/recompensa-vitoria premio (:pedra-roll request)))
        pe-delta (if venceu? (long (:pe vit-rew 0)) (gym/pe-derrota))
        coins-delta (long (:moedas vit-rew 0))
        participacao (or (:participacao request) {})
        rev (long (:save/revision save 0))
        pe (long (:save/pe-ginasios save 0))
        coins (long (:save/coins save 0))
        vitorias (long (:save/vitorias-treinador save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)
        occupy-indices (when (and venceu? (seq (:indices request)))
                         (set (map long (:indices request))))
        expected-rev (when occupy-indices
                       (long (or (:expected-revision request) 0)))
        _ (when (and occupy-indices (not= expected-rev actual-rev))
            (throw (err/error :gym_cas_conflict
                              {:expected expected-rev :actual actual-rev})))
        _ (when (and occupy-indices ocupacao
                     (nil? (:expected-revision request))
                     (not= (:player-key ocupacao) pk))
            (throw (err/error :gym_cas_conflict
                              {:expected nil :actual (:pid ocupacao)})))
        xp-txs (mapcat
                (fn [[idx nocautes]]
                  (let [i (long idx)
                        poke (when (< i (count colecao)) (nth colecao i))]
                    (when (and poke (not (contains? occupy-indices i)))
                      (:tx (apply-pokemon-xp-tx
                            poke
                            (gym/xp-ginasio-participante venceu? premio nocautes))))))
                participacao)
        insignias' (if premio (assoc insignias gym-id dia) insignias)
        base-tx (cond-> [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                         [:db/add [:save/key sk] :save/pe-ginasios (+ pe pe-delta)]
                         {:operation-receipt/id operation-id
                          :operation-receipt/kind "gym-resolve"
                          :operation-receipt/channel "gameplay"
                          :operation-receipt/payload-hash phash
                          :operation-receipt/player-key pk
                          :operation-receipt/save-id save-id
                          :operation-receipt/summary
                          (pr-str {:gym-id gym-id :venceu? venceu? :premio premio})}]
                  premio (conj [:db/add [:save/key sk] :save/ginasios-edn (pr-str insignias')])
                  (pos? coins-delta)
                  (conj [:db/cas [:save/key sk] :save/coins coins
                         (model/apply-coin-delta coins coins-delta)])
                  (:vitoria-treinador? vit-rew)
                  (conj [:db/add [:save/key sk] :save/vitorias-treinador (inc vitorias)])
                  (and (:pedra? vit-rew) (:item g))
                  (into (inventory-adjust-tx db sk (:item g) 1)))
        wear-tx (when (and (not venceu?) ocupacao (not occupy-indices))
                  (let [worn (gym/desgastar-motivacao ocupacao agora)]
                    [[:db/add [:gym-occupation/key gk]
                      :gym-occupation/motivacao-edn (pr-str (:motivacao worn))]
                     [:db/add [:gym-occupation/key gk]
                      :gym-occupation/motivacao-em agora]
                     [:db/cas [:gym-occupation/key gk]
                      :gym-occupation/revision actual-rev (inc actual-rev)]]))
        occupy-tx
        (when occupy-indices
          (let [indices (mapv long (:indices request))
                registros
                (mapv (fn [i]
                        (let [poke (nth colecao i)
                              nk (or (get participacao i)
                                     (get participacao (str i))
                                     0)
                              xp-amt (gym/xp-ginasio-participante venceu? premio nk)]
                          (if (pos? xp-amt)
                            (:registro (xp/aplicar-xp-pokemon poke xp-amt))
                            poke)))
                      indices)
                _ (gym/validar-time-ocupacao! indices registros)
                _ (when (and ocupacao
                             (= (:player-key ocupacao) pk)
                             (= (:save-id ocupacao) save-id))
                    (throw (err/error :cannot_challenge_own_gym
                                      {:save-id save-id :player-id player-id})))
                moedas-ant (if ocupacao (gym/recompensa-permanencia ocupacao agora) 0)
                xp-ant (if ocupacao (gym/xp-permanencia ocupacao agora) 0)
                nova (gym/nova-ocupacao
                      {:player-id player-id :save-id save-id
                       :nome (or (:nome request) player-id)
                       :time registros :agora agora})
                removed-ids (set (map :id-pokemon registros))
                remaining (vec (remove #(contains? removed-ids (:id-pokemon %)) colecao))
                remove-tx (mapcat (fn [i]
                                    [[:db/retractEntity
                                      [:pokemon/id-pokemon (:id-pokemon (nth colecao i))]]])
                                  (sort > indices))
                reindex-tx (map-indexed
                            (fn [i poke]
                              [:db/add [:pokemon/id-pokemon (:id-pokemon poke)]
                               :pokemon/collection-index (long i)])
                            remaining)
                return-tx
                (when ocupacao
                  (let [prev (id/parse-player-key (:player-key ocupacao))
                        prev-sk (id/save-key (:chat-id prev) (:player-id prev)
                                             (:save-id ocupacao))
                        prev-colecao (query-collection db prev-sk)
                        start (count prev-colecao)]
                    (map-indexed
                     (fn [i reg]
                       (pokemon-entity-map prev-sk
                                           (registro-retorno reg xp-ant)
                                           (+ start i)))
                     (:time ocupacao))))
                prev-coins-tx
                (when (and ocupacao (pos? moedas-ant))
                  (let [prev (id/parse-player-key (:player-key ocupacao))
                        prev-sk (id/save-key (:chat-id prev) (:player-id prev)
                                             (:save-id ocupacao))
                        prev-save (save-entity db (:chat-id prev) (:player-id prev)
                                               (:save-id ocupacao))
                        pc (long (:save/coins prev-save 0))]
                    [[:db/cas [:save/key prev-sk] :save/coins pc
                      (model/apply-coin-delta pc moedas-ant)]]))
                occ-entity {:gym-occupation/key gk
                            :gym-occupation/chat-id chat-id
                            :gym-occupation/gym-id gym-id
                            :gym-occupation/player-key pk
                            :gym-occupation/save-id save-id
                            :gym-occupation/nome (:nome nova)
                            :gym-occupation/desde agora
                            :gym-occupation/motivacao-edn (pr-str (:motivacao nova))
                            :gym-occupation/motivacao-em agora
                            :gym-occupation/team-edn (pr-str (:time nova))}
                occ-tx [occ-entity
                        [:db/cas [:gym-occupation/key gk]
                         :gym-occupation/revision expected-rev (inc expected-rev)]]
                prev-defense-tx
                (when (and ocupacao (not= (:player-key ocupacao) pk))
                  (let [prev (id/parse-player-key (:player-key ocupacao))
                        n (long (:player/gym-defense-count
                                 (player-entity db (:chat-id prev) (:player-id prev)) 0))]
                    [[:db/cas [:player/key (:player-key ocupacao)]
                      :player/gym-defense-count n (max 0 (dec n))]]))
                n-chal (long (:player/gym-defense-count
                              (player-entity db chat-id player-id) 0))
                challenger-defense
                [[:db/cas [:player/key pk] :player/gym-defense-count n-chal (inc n-chal)]]]
            {:tx (concat remove-tx reindex-tx (or return-tx [])
                         (or prev-coins-tx []) occ-tx
                         (or prev-defense-tx []) challenger-defense)
             :meta {:moedas-permanencia moedas-ant :xp-permanencia xp-ant}}))
        tx (into (into base-tx (concat (or xp-txs []) (or wear-tx [])))
                 (or (:tx occupy-tx) []))]
    {:tx tx
     :result {:operation-id operation-id
              :duplicate false
              :channel "gameplay"
              :payload-hash phash
              :venceu? venceu?
              :premio premio
              :reward vit-rew
              :pe-delta pe-delta
              :occupied? (boolean occupy-tx)
              :permanencia (or (:meta occupy-tx) {})}}))

(defn build-heal-tx
  [{:keys [db save writer-ctx request ocupacao inventory-adjust-tx]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        gym-id (:gym-id request)
        pk (id/player-key chat-id player-id)
        sk (id/save-key chat-id player-id save-id)
        _ (when-not ocupacao (throw (err/error :gym_empty {:gym-id gym-id})))
        _ (when-not (and (= (:player-key ocupacao) pk)
                         (= (:save-id ocupacao) save-id))
            (throw (err/error :not_gym_leader
                              {:save-id save-id :occupation-save (:save-id ocupacao)})))
        agora (long (or (:agora request) (System/currentTimeMillis)))
        healed (gym/recuperar-motivacao ocupacao (:indice request) agora (:pontos request))
        item-tx (inventory-adjust-tx db sk (:item-id request) -1)
        rev (long (:save/revision save 0))
        gym-rev (long (:revision ocupacao 0))
        gk (id/gym-occupation-key chat-id gym-id)
        operation-id (:operation-id request)
        phash (:payload-hash request)]
    {:tx (into item-tx
               [[:db/add [:gym-occupation/key gk]
                 :gym-occupation/motivacao-edn
                 (pr-str (get-in healed [:ocupacao :motivacao]))]
                [:db/add [:gym-occupation/key gk] :gym-occupation/motivacao-em agora]
                [:db/cas [:gym-occupation/key gk]
                 :gym-occupation/revision gym-rev (inc gym-rev)]
                [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                {:operation-receipt/id operation-id
                 :operation-receipt/kind "gym-heal"
                 :operation-receipt/channel "gameplay"
                 :operation-receipt/payload-hash phash
                 :operation-receipt/player-key pk
                 :operation-receipt/save-id save-id
                 :operation-receipt/summary
                 (pr-str {:gym-id gym-id :antes (:antes healed)
                          :depois (:depois healed)})}])
     :result {:operation-id operation-id
              :duplicate false
              :antes (:antes healed)
              :depois (:depois healed)}}))
