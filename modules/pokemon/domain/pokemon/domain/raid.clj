(ns pokemon.domain.raid
  "Raids cooperativas — regras puras (lobby Fase 2 + combate M3.6)."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]))

;; Lobby Fase 2 (abertura manual cooperativa).
;; Fase 12: duração padrão 5 min (raides já abertas preservam :expira gravado).
(def duracao-inscricoes (* 5 60 1000))
(def max-participantes 5)
(def min-participantes 2)

(def duracao-combate (* 30 60 1000))
(def duracao-captura (* 30 60 1000))
(def intervalo-proxima (* 6 60 60 1000))
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

(defn liga-para-nivel
  "Primeira liga cuja faixa contém o nível (ginásio)."
  [nivel]
  (let [n (long (or nivel 1))]
    (or (some #(when (<= (:min %) n (:max %)) %) ligas)
        (last ligas))))

(defn max-da-raid
  [raid]
  (long (or (:max-jogadores raid) max-participantes)))

(defn min-da-raid
  [raid]
  (long (or (:min-jogadores raid) min-participantes)))

(defn pokemon-elegivel-liga?
  "Pokémon elegível para a liga da raid (nível na faixa)."
  [liga-id pokemon]
  (let [liga (obter-liga liga-id)
        nivel (long (or (:nivel pokemon) (get pokemon "nivel")
                        (:level pokemon) 1))]
    (boolean (and liga pokemon (<= (:min liga) nivel (:max liga))))))

(defn inscricoes-abertas?
  [raid agora]
  (and (= "inscricoes" (str (:fase raid)))
       (< (long agora) (long (:expira raid 0)))))

(defn ativa?
  [raid agora]
  (and (contains? #{"inscricoes" "combate"} (str (:fase raid)))
       (< (long agora) (long (:expira raid 0)))))

(defn lobby-ou-combate-vivo?
  "Ponteiro do chat ainda ocupa o slot (inscrições válidas ou combate)."
  [raid agora]
  (boolean
   (and raid
        (or (inscricoes-abertas? raid agora)
            (and (= "combate" (str (:fase raid)))
                 (< (long agora) (long (:expira raid 0))))))))

(defn no-ginasio?
  [raid gym-id agora]
  (and raid
       (= gym-id (:ginasio raid))
       (ativa? raid agora)))

(defn chance-captura
  [raridade]
  (get {"raro" 35 "epico" 20 "lendario" 8 "mitico" 4
        :raro 35 :epico 20 :lendario 8 :mitico 4} raridade 35))

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

(defn- artwork-url
  "URL estática PokeAPI (sem fetch por ataque). dex-id numérico."
  [dex-id]
  (str "https://raw.githubusercontent.com/PokeAPI/sprites/master/"
       "sprites/pokemon/other/official-artwork/" dex-id ".png"))

(def ^:private chefes-ginasio
  "Templates determinísticos dos ases do catálogo A6 (última espécie do time).
   `:imagem` aponta artwork oficial com alpha — evita placeholder/caixa preta no cartão."
  {"onix" {:nome "Onix" :species "onix" :tipos ["rock" "ground"] :raridade "raro"
           :hp 70 :ataque 60 :defesa 100 :atq-esp 40 :def-esp 55 :veloc 50
           :imagem (artwork-url 95)
           :golpes [{:nome-exibicao "Rock Throw" :tipo "rock" :poder 50 :classe :fisico}
                    {:nome-exibicao "Earthquake" :tipo "ground" :poder 80 :classe :fisico}]}
   "starmie" {:nome "Starmie" :species "starmie" :tipos ["water" "psychic"] :raridade "raro"
              :hp 65 :ataque 60 :defesa 70 :atq-esp 95 :def-esp 70 :veloc 100
              :imagem (artwork-url 121)
              :golpes [{:nome-exibicao "Water Gun" :tipo "water" :poder 40 :classe :especial}
                       {:nome-exibicao "Psychic" :tipo "psychic" :poder 80 :classe :especial}]}
   "raichu" {:nome "Raichu" :species "raichu" :tipos ["electric"] :raridade "raro"
             :hp 65 :ataque 80 :defesa 55 :atq-esp 90 :def-esp 70 :veloc 100
             :imagem (artwork-url 26)
             :golpes [{:nome-exibicao "Thunderbolt" :tipo "electric" :poder 80 :classe :especial}
                      {:nome-exibicao "Quick Attack" :tipo "normal" :poder 40 :classe :fisico}]}
   "vileplume" {:nome "Vileplume" :species "vileplume" :tipos ["grass" "poison"] :raridade "raro"
                :hp 80 :ataque 70 :defesa 75 :atq-esp 100 :def-esp 80 :veloc 45
                :imagem (artwork-url 45)
                :golpes [{:nome-exibicao "Petal Dance" :tipo "grass" :poder 80 :classe :especial}
                         {:nome-exibicao "Acid" :tipo "poison" :poder 40 :classe :especial}]}
   "arcanine" {:nome "Arcanine" :species "arcanine" :tipos ["fire"] :raridade "raro"
               :hp 90 :ataque 100 :defesa 75 :atq-esp 85 :def-esp 75 :veloc 90
               :imagem (artwork-url 59)
               :golpes [{:nome-exibicao "Flamethrower" :tipo "fire" :poder 80 :classe :especial}
                        {:nome-exibicao "Take Down" :tipo "normal" :poder 70 :classe :fisico}]}})

(defn boss-do-ginasio
  "Deriva liga + chefe do ginásio A6. Lança :raid_gym_unknown se id inválido."
  [ginasio-id]
  (let [g (gym/obter-ginasio (str/lower-case (str/trim (str ginasio-id))))]
    (when-not g
      (throw (err/error :raid_gym_unknown {:ginasio ginasio-id})))
    (let [species (str/lower-case (str (or (last (:time g)) "onix")))
          base (or (get chefes-ginasio species)
                   (throw (err/error :raid_boss_template_missing {:species species})))
          nivel (long (:nivel g))
          liga (liga-para-nivel nivel)
          chefe (-> (gym/escalar-nivel base nivel)
                    (assoc :species species
                           :nome (:nome base)
                           :tipos (:tipos base)
                           :raridade (:raridade base)
                           :golpes (:golpes base)
                           :hp-atual (:hp (gym/escalar-nivel base nivel)))
                    (cond-> (:imagem base) (assoc :imagem (:imagem base))))]
      {:ginasio (:id g)
       :nome-ginasio (:nome g)
       :nivel-ginasio nivel
       :liga (:id liga)
       :chefe chefe})))

(defn nova-raid
  "Abre inscrições (criação manual). Duração/limites do lobby Fase 2."
  [{:keys [raid-id chat-id ginasio nome-ginasio nivel-ginasio liga chefe agora criador
           min-jogadores max-jogadores duracao-ms]}]
  (let [agora (long agora)
        dur (long (or duracao-ms duracao-inscricoes))
        mn (long (or min-jogadores min-participantes))
        mx (long (or max-jogadores max-participantes))]
    (when-not (and (string? raid-id) (seq raid-id)
                   (string? chat-id) (seq chat-id)
                   (map? chefe) (seq (str (or (:nome chefe) (get chefe "nome") "")))
                   (pos? agora)
                   (<= 1000 dur 900000)
                   (<= 1 mn mx 6))
      (throw (err/error :raid_config_invalid
                        {:raid-id raid-id :duracao-ms dur :min mn :max mx})))
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
     :criada-em agora
     :expira (+ agora dur)
     :min-jogadores mn
     :max-jogadores mx
     :criador criador
     :turn-index 0
     :revision 0
     :rewards-paid? false}))

(defn- assert-combatente-valido!
  [raid pokemon player-id]
  (let [hp (long (or (:hp-atual pokemon) (:hp pokemon) 0))]
    (when-not (and pokemon (pos? hp) (some golpe-dano? (or (:golpes pokemon) [])))
      (throw (err/error :invalid_raid_pokemon {:player-id player-id})))
    (when-not (pokemon-elegivel-liga? (:liga raid) pokemon)
      (throw (err/error :raid_liga_mismatch
                        {:liga (:liga raid)
                         :nivel (or (:nivel pokemon) (get pokemon "nivel"))})))
    hp))

(defn entrar
  "Inscrição. Se já inscrito no lobby com outro índice, troca o combatente
   (snapshot). Em combate, combatente fica travado."
  [raid {:keys [player-id nome save-id fence-generation pokemon collection-index]} agora]
  (when-not (inscricoes-abertas? raid agora)
    (throw (err/error :raid_not_open {:fase (:fase raid)})))
  (let [hp (assert-combatente-valido! raid pokemon player-id)
        part {:nome nome
              :save-id save-id
              :fence-generation (long fence-generation)
              :pokemon pokemon
              :collection-index (long (or collection-index 0))
              :hp hp
              :dano 0}]
    (if (contains? (:participantes raid) player-id)
      ;; Lobby: permite trocar o mon selado (HP do snapshot reinicia no lobby).
      (assoc-in raid [:participantes player-id] part)
      (do
        (when (>= (count (:ordem raid)) (max-da-raid raid))
          (throw (err/error :raid_full {:max (max-da-raid raid)})))
        (-> raid
            (update :ordem conj player-id)
            (assoc-in [:participantes player-id] part))))))

(defn sair
  "Sai durante inscrições (legado)."
  [raid player-id agora]
  (when-not (inscricoes-abertas? raid agora)
    (throw (err/error :raid_cannot_leave {:fase (:fase raid)})))
  (when-not (contains? (:participantes raid) player-id)
    (throw (err/error :not_in_raid {:player-id player-id})))
  (-> raid
      (update :ordem #(vec (remove #{player-id} %)))
      (update :participantes dissoc player-id)))

(defn sair-combate
  "Remove só o jogador em combate. Raid continua se houver vivos;
   se ninguém restar, encerra sem vitória/recompensas (:fase abandonada).
   Não exige ativa?/expira — abandono voluntário é permitido até o fim do combate."
  [raid player-id _agora]
  (when-not (= "combate" (str (:fase raid)))
    (throw (err/error :raid_cannot_leave {:fase (:fase raid)})))
  (when-not (get-in raid [:participantes player-id])
    (throw (err/error :not_in_raid {:player-id player-id})))
  (let [era-vez? (= player-id (:vez raid))
        raid' (-> raid
                  (update :ordem #(vec (remove #{player-id} %)))
                  (update :participantes dissoc player-id)
                  (update :turn-index (fnil inc 0)))
        vivos (vec (filter #(pos? (get-in raid' [:participantes % :hp] 0))
                           (:ordem raid')))]
    (cond
      (empty? vivos)
      (assoc raid' :fase "abandonada" :vez nil)
      era-vez?
      (assoc raid' :vez (first vivos))
      :else raid')))

(defn vez-valida?
  "Invariante: em combate, :vez deve apontar a participante vivo na ordem."
  [raid]
  (let [vez (:vez raid)
        parts (:participantes raid {})]
    (boolean
     (and (string? vez)
          (seq vez)
          (contains? parts vez)
          (some #{vez} (:ordem raid))
          (pos? (long (get-in parts [vez :hp] 0)))))))

(defn- fator-chefe
  "Fator de dificuldade do chefe (1.0 se ausente — raids legadas)."
  [chefe]
  (let [f (or (:fator-dificuldade chefe) (get chefe "fator-dificuldade") 1.0)]
    (max 0.5 (min 2.0 (double f)))))

(defn- aplicar-inicio-combate
  [raid agora]
  (let [n (count (:ordem raid))
        parts (:participantes raid)
        fator (fator-chefe (:chefe raid))
        hp-base (long (Math/round (* (double hp-por-jogador) n fator)))
        media-def (media-stat parts :defesa)
        media-spdef (media-stat parts :def-esp)
        chefe (:chefe raid)
        boss-def (double (or (:defesa chefe) (get chefe "defesa") media-def))
        boss-spdef (double (or (:def-esp chefe) (get chefe "def-esp") media-spdef))
        ;; Mistura defesa dos jogadores com a do chefe (atributos reais influenciam).
        defesa (+ (* 0.55 media-def) (* 0.45 boss-def))
        def-esp (+ (* 0.55 media-spdef) (* 0.45 boss-spdef))
        ;; Primeira vez = primeiro da ordem com HP>0 (participante real).
        vez0 (first (filter #(pos? (get-in parts [% :hp] 0)) (:ordem raid)))]
    (when-not vez0
      (throw (err/error :raid_no_eligible_turn {:ordem (:ordem raid)})))
    (assoc raid
           :fase "combate"
           :hp-chefe hp-base
           :hp-max hp-base
           :defesa defesa
           :def-esp def-esp
           :vez vez0
           :expira (+ (long agora) duracao-combate)
           :proxima (+ (long agora) intervalo-proxima)
           :turn-index 0)))

(defn iniciar
  "Início antecipado pelo criador inscrito (mín. de jogadores)."
  [raid player-id agora]
  (when-not (inscricoes-abertas? raid agora)
    (throw (err/error :raid_not_open {:fase (:fase raid)})))
  (when (and (:criador raid) (not= player-id (:criador raid)))
    (throw (err/error :raid_not_creator {:player-id player-id})))
  (when-not (contains? (:participantes raid) player-id)
    (throw (err/error :not_in_raid {:player-id player-id})))
  (when (< (count (:ordem raid)) (min-da-raid raid))
    (throw (err/error :raid_need_players
                      {:have (count (:ordem raid)) :min (min-da-raid raid)})))
  (aplicar-inicio-combate raid agora))

(defn cancelar
  "Cancela lobby antes do combate (só criador)."
  [raid player-id agora]
  (when-not (= "inscricoes" (str (:fase raid)))
    (throw (err/error :raid_cannot_cancel {:fase (:fase raid)})))
  (when-not (inscricoes-abertas? raid agora)
    (throw (err/error :raid_not_open {:fase (:fase raid)})))
  (when (and (:criador raid) (not= player-id (:criador raid)))
    (throw (err/error :raid_not_creator {:player-id player-id})))
  (assoc raid :fase "cancelada" :expira (long agora)))

(defn expirar-inscricoes
  "Sem timer: se prazo passou em inscricoes, cancela (< min) ou inicia combate.
   Retorna {:raid :acao} onde acao é :noop|:cancelada|:combate."
  [raid agora]
  (cond
    (nil? raid)
    {:raid nil :acao :noop}

    (not= "inscricoes" (str (:fase raid)))
    {:raid raid :acao :noop}

    (< (long agora) (long (:expira raid 0)))
    {:raid raid :acao :noop}

    (< (count (:ordem raid)) (min-da-raid raid))
    {:raid (assoc raid :fase "cancelada") :acao :cancelada}

    :else
    {:raid (aplicar-inicio-combate raid agora) :acao :combate}))

(defn- proxima-vez
  [raid pid]
  (let [ordem (:ordem raid)
        vivos (set (filter #(pos? (get-in raid [:participantes % :hp] 0)) ordem))
        depois (concat (rest (drop-while #(not= pid %) ordem))
                       (take-while #(not= pid %) ordem)
                       [pid])]
    (first (filter vivos depois))))

(defn atacar
  "Aplica um ataque. Retorna {:raid :dano :revide :venceu? :derrota?}.
  Compara player-id autenticado com :vez por igualdade literal (identidade
  canônica do participante) — sem strip de @lid/@c.us."
  [raid player-id move-index agora]
  (when-not (and (ativa? raid agora) (= "combate" (str (:fase raid))))
    (throw (err/error :raid_not_in_combat {:fase (:fase raid)})))
  (when-not (vez-valida? raid)
    (throw (err/error :raid_turn_invalid
                      {:vez (:vez raid)
                       :ordem (:ordem raid)
                       :player-id player-id})))
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
