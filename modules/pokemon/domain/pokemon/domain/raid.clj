(ns pokemon.domain.raid
  "Raids cooperativas — regras puras espelhando raids.cljs."
  (:require [pokemon.domain.errors :as err]))

(def duracao-inscricoes (* 45 60 1000))
(def duracao-combate (* 30 60 1000))
(def duracao-captura (* 30 60 1000))
(def intervalo-proxima (* 6 60 60 1000))
(def max-participantes 6)
(def min-participantes 2)
(def hp-por-jogador 220)
(def max-tentativas-captura 3)
(def recompensa-moedas 50)
(def recompensa-pe 6)
(def recompensa-xp-pokemon 6)

(def ligas
  "Faixas de nível por liga (espelho treinador.cljs)."
  [{:id "iniciante" :nome "Iniciante" :min 1 :max 10}
   {:id "bronze" :nome "Bronze" :min 11 :max 25}
   {:id "prata" :nome "Prata" :min 26 :max 40}
   {:id "ouro" :nome "Ouro" :min 41 :max 60}
   {:id "diamante" :nome "Diamante" :min 61 :max 100}])

(defn obter-liga
  [id]
  (some #(when (= (str id) (:id %)) %) ligas))

(defn pokemon-elegivel-liga?
  "Pokémon elegível para a liga da raid (nível na faixa)."
  [liga-id pokemon]
  (let [liga (obter-liga liga-id)
        nivel (long (or (:nivel pokemon) (get pokemon "nivel")
                        (:level pokemon) 1))]
    (boolean (and liga pokemon (<= (:min liga) nivel (:max liga))))))

(defn ativa?
  [raid agora]
  (and (contains? #{"inscricoes" "combate"} (str (:fase raid)))
       (< (long agora) (long (:expira raid 0)))))

(defn no-ginasio?
  [raid gym-id agora]
  (and raid
       (= gym-id (:ginasio raid))
       (ativa? raid agora)))

(defn chance-captura
  [raridade]
  (get {"raro" 35 "lendario" 8 "mitico" 4
        :raro 35 :lendario 8 :mitico 4} raridade 35))

(defn elegivel-recompensa?
  [participante]
  (pos? (long (:dano participante 0))))

(defn captura-pendente?
  [raid pid agora]
  (let [p (get-in raid [:participantes pid])
        cap (get-in raid [:capturas pid])]
    (and (= "vitoria" (str (:fase raid)))
         (< (long agora) (long (:captura-expira raid 0)))
         (elegivel-recompensa? p)
         (< (long (:tentativas cap 0)) max-tentativas-captura)
         (not (:capturou cap)))))

(defn- golpe-dano?
  [golpe]
  (and golpe
       (contains? #{"fisico" "especial" :fisico :especial}
                  (or (:classe golpe) (get golpe "classe")))
       (pos? (long (or (:poder golpe) (get golpe "poder") 0)))))

(defn calcular-dano
  "Espelha atacar do legado: floor(15+0.6*poder*(atk/def)) clamp [10,160]."
  [golpe pokemon raid]
  (let [classe (or (:classe golpe) (get golpe "classe"))
        especial? (contains? #{"especial" :especial} classe)
        ataque (long (or ((if especial? :atq-esp :ataque) pokemon)
                         (get pokemon (if especial? "atq-esp" "ataque"))
                         50))
        defesa (max 1 (long (or ((if especial? :def-esp :defesa) raid)
                                50)))
        poder (long (or (:poder golpe) (get golpe "poder") 0))
        raw (long (Math/floor (+ 15 (* 0.6 poder (/ (double ataque) defesa)))))]
    (min (long (:hp-chefe raid))
         (max 10 (min 160 raw)))))

(defn- media-stat
  [participantes stat]
  (let [regs (map :pokemon (vals participantes))
        n (count regs)]
    (if (zero? n)
      50.0
      (/ (double (reduce + (map #(long (or (get % stat) (get % (name stat)) 50)) regs)))
         n))))

(defn nova-raid
  "Abre inscrições (criação manual/harness — auto-spawn removido no legado)."
  [{:keys [raid-id chat-id ginasio nome-ginasio nivel-ginasio liga chefe agora criador]}]
  {:raid-id raid-id
   :chat-id chat-id
   :ginasio ginasio
   :nome-ginasio (or nome-ginasio ginasio)
   :nivel-ginasio (long (or nivel-ginasio 1))
   :liga (or liga "iniciante")
   :chefe chefe
   :fase "inscricoes"
   :ordem []
   :participantes {}
   :capturas {}
   :expira (+ (long agora) duracao-inscricoes)
   :criador criador
   :turn-index 0
   :revision 0
   :rewards-paid? false})

(defn entrar
  "Puro: retorna raid atualizada ou lança erro de domínio."
  [raid {:keys [player-id nome save-id fence-generation pokemon collection-index]} agora]
  (when-not (and (ativa? raid agora) (= "inscricoes" (str (:fase raid))))
    (throw (err/error :raid_not_open {:fase (:fase raid)})))
  (when (some #{player-id} (:ordem raid))
    (throw (err/error :already_in_raid {:player-id player-id})))
  (when (>= (count (:ordem raid)) max-participantes)
    (throw (err/error :raid_full {:max max-participantes})))
  (let [hp (long (or (:hp-atual pokemon) (:hp pokemon) 0))]
    (when-not (and pokemon (pos? hp) (some golpe-dano? (or (:golpes pokemon) [])))
      (throw (err/error :invalid_raid_pokemon {:player-id player-id})))
    (when-not (pokemon-elegivel-liga? (:liga raid) pokemon)
      (throw (err/error :raid_liga_mismatch
                        {:liga (:liga raid)
                         :nivel (or (:nivel pokemon) (get pokemon "nivel"))})))
    (-> raid
        (update :ordem conj player-id)
        (assoc-in [:participantes player-id]
                  {:nome nome
                   :save-id save-id
                   :fence-generation (long fence-generation)
                   :pokemon pokemon
                   :collection-index (long (or collection-index 0))
                   :hp hp
                   :dano 0}))))

(defn sair
  [raid player-id agora]
  (when-not (and (ativa? raid agora) (= "inscricoes" (str (:fase raid))))
    (throw (err/error :raid_cannot_leave {:fase (:fase raid)})))
  (-> raid
      (update :ordem #(vec (remove #{player-id} %)))
      (update :participantes dissoc player-id)))

(defn iniciar
  [raid player-id agora]
  (when-not (and (ativa? raid agora) (= "inscricoes" (str (:fase raid))))
    (throw (err/error :raid_not_open {:fase (:fase raid)})))
  (when (and (:criador raid) (not= player-id (:criador raid)))
    (throw (err/error :raid_not_creator {:player-id player-id})))
  (when-not (contains? (:participantes raid) player-id)
    (throw (err/error :not_in_raid {:player-id player-id})))
  (when (< (count (:ordem raid)) min-participantes)
    (throw (err/error :raid_need_players
                      {:have (count (:ordem raid)) :min min-participantes})))
  (let [n (count (:ordem raid))
        parts (:participantes raid)]
    (assoc raid
           :fase "combate"
           :hp-chefe (* hp-por-jogador n)
           :hp-max (* hp-por-jogador n)
           :defesa (media-stat parts :defesa)
           :def-esp (media-stat parts :def-esp)
           :vez (first (:ordem raid))
           :expira (+ (long agora) duracao-combate)
           :proxima (+ (long agora) intervalo-proxima)
           :turn-index 0)))

(defn- proxima-vez
  [raid pid]
  (let [ordem (:ordem raid)
        vivos (set (filter #(pos? (get-in raid [:participantes % :hp] 0)) ordem))
        depois (concat (rest (drop-while #(not= pid %) ordem))
                       (take-while #(not= pid %) ordem)
                       [pid])]
    (first (filter vivos depois))))

(defn atacar
  "Aplica um ataque. Retorna {:raid :dano :revide :venceu? :derrota?}."
  [raid player-id move-index agora]
  (when-not (and (ativa? raid agora) (= "combate" (str (:fase raid))))
    (throw (err/error :raid_not_in_combat {:fase (:fase raid)})))
  (when-not (= player-id (:vez raid))
    (throw (err/error :raid_not_your_turn {:vez (:vez raid) :player-id player-id})))
  (let [part (get-in raid [:participantes player-id])
        pokemon (:pokemon part)
        golpes (vec (or (:golpes pokemon) []))
        golpe (when (and (integer? move-index) (<= 0 move-index 3)
                         (< move-index (count golpes)))
                (nth golpes move-index))]
    (when-not (golpe-dano? golpe)
      (throw (err/error :invalid_raid_move {:move-index move-index})))
    (let [dano (calcular-dano golpe pokemon raid)
          hp' (max 0 (- (long (:hp-chefe raid)) dano))
          venceu? (zero? hp')
          revide (if venceu? 0 (max 1 (long (Math/ceil (/ (double (or (:hp pokemon) 100)) 5)))))
          part-hp (max 0 (- (long (:hp part)) revide))
          raid' (-> raid
                    (assoc :hp-chefe hp')
                    (update-in [:participantes player-id :dano] #(+ (long %) dano))
                    (assoc-in [:participantes player-id :hp] part-hp)
                    (update :turn-index (fnil inc 0)))
          vivos (filter #(pos? (get-in raid' [:participantes % :hp] 0)) (:ordem raid'))
          derrota? (and (not venceu?) (empty? vivos))
          raid'' (cond
                   venceu? (assoc raid'
                                  :fase "vitoria"
                                  :captura-expira (+ (long agora) duracao-captura)
                                  :vez nil)
                   derrota? (assoc raid' :fase "derrota" :vez nil)
                   :else (assoc raid' :vez (proxima-vez raid' player-id)))]
      {:raid raid''
       :dano dano
       :revide revide
       :venceu? venceu?
       :derrota? derrota?
       :golpe golpe})))

(defn assert-participant-fence!
  "Bloqueia callback com save/fence diferente do carimbo na inscrição."
  [participante writer-ctx]
  (when-not participante
    (throw (err/error :not_in_raid {:player-id (:player-id writer-ctx)})))
  (when (not= (:save-id participante) (:save-id writer-ctx))
    (throw (err/error :raid_save_mismatch
                      {:sealed (:save-id participante)
                       :writer (:save-id writer-ctx)})))
  (when (not= (long (:fence-generation participante))
              (long (:fence-generation writer-ctx)))
    (throw (err/error :stale_writer_fence
                      {:sealed (:fence-generation participante)
                       :writer (:fence-generation writer-ctx)})))
  true)

(defn participantes-elegiveis
  [raid]
  (filterv (fn [[_ p]] (elegivel-recompensa? p)) (:participantes raid)))
