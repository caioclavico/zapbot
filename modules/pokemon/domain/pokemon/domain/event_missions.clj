(ns pokemon.domain.event-missions
  "Progresso e resgate de missões de eventos temáticos (regras puras).
  Progresso só conta dentro da janela da ocorrência. Doces usam professor-cards."
  (:require [clojure.string :as str]
            [pokemon.domain.events :as events]
            [pokemon.domain.profile :as profile]))

(defn occurrence-state
  "Estado do jogador para uma ocorrência (mapa strings)."
  [events-edn occurrence-id]
  (get-in (or events-edn {}) ["thematic" (str occurrence-id)]
          {"progresso" {} "premiados" [] "candy-picks" {}}))

(defn upsert-occurrence-state
  [events-edn occurrence-id state]
  (assoc-in (or events-edn {}) ["thematic" (str occurrence-id)] state))

(defn mission-defs
  [catalog-id]
  (or (:missoes (events/by-id catalog-id)) []))

(defn- mission-estado [definition progress rewarded?]
  (assoc definition
         :progresso (long progress)
         :estado (cond rewarded? "rewarded"
                       (>= (long progress) (long (:meta definition))) "completed"
                       (pos? (long progress)) "in_progress"
                       :else "available")))

(defn project-missions
  "Lista missões da ocorrência com progresso do jogador."
  [catalog-id player-state]
  (let [st (or player-state {"progresso" {} "premiados" []})
        premiados (set (get st "premiados" []))]
    (mapv #(mission-estado %
                           (get-in st ["progresso" (:id %)] 0)
                           (contains? premiados (:id %)))
          (mission-defs catalog-id))))

(defn action-matches?
  "A ação confirma progresso da missão? Valores tipados/família/espécie."
  [mission {:keys [type types family species raid-won?]}]
  (let [obj (str (:objetivo-tipo mission (:tipo mission "")))
        need-types (set (map str/lower-case (or (:tipos mission) [])))
        need-families (set (map str/lower-case (or (:familias mission) [])))]
    (case (keyword (or (:acao mission) obj "captura"))
      (:captura :wild-captured)
      (and (= type :wild-captured)
           (or (empty? need-types)
               (some need-types (map str/lower-case (or types []))))
           (or (empty? need-families)
               (contains? need-families (str/lower-case (str (or family ""))))))

      (:evolucao :evolved)
      (and (= type :evolved)
           (or (empty? need-families)
               (contains? need-families (str/lower-case (str (or family species ""))))))

      (:raid-join :raid-participou)
      (and (#{:raid-joined :raid-won} type) true)

      (:raid-win :raid-venceu)
      (and (= type :raid-won) (or raid-won? true))

      (:batalha :battle-won)
      (and (#{:pvp-won :wild-defeated :gym-won} type)
           (or (empty? need-types)
               (some need-types (map str/lower-case (or types [])))))

      false)))

(defn apply-action
  "Incrementa progresso se dentro da janela e missão casar. Idempotente por source-key
  fica a cargo do produtor (mission-event). Aqui só aplica 1 tick."
  [player-state catalog-id action]
  (let [st (or player-state {"progresso" {} "premiados" [] "candy-picks" {}})
        premiados (set (get st "premiados" []))]
    (reduce
     (fn [s mission]
       (let [id (:id mission)
             meta (long (:meta mission 1))
             cur (long (get-in s ["progresso" id] 0))]
         (if (or (contains? premiados id)
                 (>= cur meta)
                 (not (action-matches? mission action)))
           s
           (assoc-in s ["progresso" id] (min meta (inc cur))))))
     st
     (mission-defs catalog-id))))

(defn- resolve-candy-pick
  "Escolha idempotente da família de doce no primeiro resgate."
  [mission event-def existing-pick draw]
  (or (not-empty existing-pick)
      (when-let [fixed (first (keys (:doces mission)))]
        (profile/family fixed))
      (events/pick-candy-family event-def draw)))

(defn claim-ready
  "Missões completed ainda não premiadas."
  [catalog-id player-state]
  (let [st (or player-state {"progresso" {} "premiados" []})
        premiados (set (get st "premiados" []))]
    (filterv (fn [m]
               (and (not (contains? premiados (:id m)))
                    (>= (long (get-in st ["progresso" (:id m)] 0))
                        (long (:meta m)))))
             (mission-defs catalog-id))))

(defn build-claim
  "Calcula itens + doces e novo estado. draw: fn [[mission-id :candy] n] ou (fn [] long)."
  [catalog-id player-state draw]
  (let [event-def (events/by-id catalog-id)
        ready (claim-ready catalog-id player-state)
        st0 (or player-state {"progresso" {} "premiados" [] "candy-picks" {}})
        draw* (if (fn? draw)
                (fn [key n]
                  (try (draw key n)
                       (catch Exception _
                         (mod (long (or (draw) 0)) (max 1 n)))))
                (fn [_key n] (mod 0 (max 1 n))))]
    (reduce
     (fn [{:keys [state items doces ids]} mission]
       (let [id (:id mission)
             base (or (:recompensas mission) {})
             items' (merge-with + items base)
             existing (get-in state ["candy-picks" id])
             fam (when (or (seq (:doces mission)) (seq (:familias-doce event-def)))
                   (resolve-candy-pick mission event-def existing
                                       #(draw* [id :candy] (max 1 (count (:familias-doce event-def))))))
             qty (long (or (first (vals (:doces mission)))
                           (when fam 1)
                           0))
             doces' (cond-> doces
                      (and fam (pos? qty))
                      (update (profile/family fam) (fnil + 0) qty))
             state' (-> state
                        (update "premiados" #(vec (distinct (conj (vec %) id))))
                        (cond-> fam (assoc-in ["candy-picks" id] (profile/family fam))))]
         {:state state' :items items' :doces doces' :ids (conj ids id)}))
     {:state st0 :items {} :doces {} :ids []}
     ready)))

(defn within-window?
  [occurrence agora-ms]
  (and occurrence
       (= "active" (str (:status occurrence)))
       (<= (long (:inicio-ms occurrence 0)) (long agora-ms))
       (< (long agora-ms) (long (:fim-ms occurrence 0)))))
