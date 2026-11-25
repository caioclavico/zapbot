(ns pokemon.domain.battle
  "Estado e turnos de batalha selvagem — puro, sem I/O.
  Espelha o loop atacar-selvagem / turno-selvagem do legado (sem UI).
  PvP 1×1 reutiliza dano/status; turnos alternados sem resposta NPC."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.damage :as dmg]
            [pokemon.domain.errors :as err]
            [pokemon.domain.mechanics :as mechanics]
            [pokemon.domain.move-effects :as move-effects]
            [pokemon.domain.move-names :as move-names]
            [pokemon.domain.pvp :as pvp]
            [pokemon.domain.rng :as rng]
            [pokemon.domain.shop :as shop]
            [pokemon.domain.status :as status])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util Base64]
           [java.util.zip GZIPInputStream GZIPOutputStream]
           [java.nio.charset StandardCharsets]))

(def golpe-padrao
  {:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
   :poder 40 :classe :fisico})

(def ^:private datomic-local-string-limit
  "Limite prático do Datomic Local para valor de atributo (~4096)."
  4000)

(def ^:private compact-edn-prefix
  "Prefixo de state-edn/team-edn gzip+Base64 quando o EDN plain > limite."
  "odisseu.gz;")

(def ^:private battle-pokemon-drop-keys
  "Campos de coleção/legado não necessários no loop de batalha.
   historico/ofertas/ivs incham battle/state-edn além do limite Local."
  [:historico-golpes "historico-golpes"
   :golpes-oferecidos "golpes-oferecidos"
   :ofertas-golpes "ofertas-golpes"
   :golpes-removidos "golpes-removidos"
   :ivs "ivs"
   :legado "legado"
   :origin-edn "origin-edn"
   :pokemon-key "pokemon-key"
   ;; :id-pokemon preservado — raide liquida XP do mon via este id (Fase 12.6).
   :nickname "nickname"
   :xp "xp"
   :xp-desde-nivel "xp-desde-nivel"
   :amizade "amizade"
   :in-party "in-party"
   :location "location"
   ;; :raridade permanece — captura/UI de caça leem do snapshot (sem `?`).
   :altura "altura"
   :peso "peso"
   :pronto-em "pronto-em"
   :collection-index "collection-index"])

(def ^:private battle-pokemon-drop-keys-bench
  "Reservas 3×3: URLs de sprite são pequenas (~gzip); manter evita caixa preta no switch."
  [])

(def ^:private battle-pokemon-keep-keys
  "Allowlist pós-drop — evita campos residuais incharem o snapshot 3×3.
   taxa-captura fica: captura pós-caça lê do selvagem no state-edn.
   id-pokemon é obrigatório em raide: liquidação de XP do mon participante."
  #{:id-pokemon :species :nome :shiny :nivel :level :hp :hp-atual :status
    :ataque :defesa :atq-esp :def-esp :veloc :tipos :habilidade :golpes :item
    :taxa-captura :raridade :imagem :imagem-shiny :altura :motivacao-ginasio})

(defn- kw-status [s] (status/->kw s))

(defn- golpe-get
  [g & ks]
  (some #(let [v (get g %)]
           (when (some? v) v))
        ks))

(defn- nome-golpe-exibivel
  "Nome canônico para UI. Nunca devolve `?`/`\"\"` se houver slug/tipo recuperável.
   Movimento irrecuperável (nil total) → nil (caller marca pendência, sem inventar poder)."
  [nome-ex slug tipo]
  (let [n (some-> nome-ex str str/trim)
        s (some-> slug str str/trim)
        t (some-> tipo str str/trim)]
    (cond
      (and n (not= n "?") (seq n)) n
      (and s (not= s "?") (seq s)) s
      (and t (seq t)) (str "Golpe " t)
      :else nil)))

(defn golpe-normalizado
  "Normaliza golpe com chaves keyword OU string (EDN legado / migração).
  Sem gate em :slug — maps só com \"nome\"/\"poder\" deixavam slim-golpe vazio
  (select-keys em keywords) → UI `? — Poder 0`.
  Strings/números soltos no vetor :golpes → mapa mínimo com slug (sem inventar poder)."
  [g]
  (cond
    (string? g)
    (let [s (str/trim g)]
      (when (seq s)
        {:slug s :nome-exibicao s :poder 0 :classe :status}))

    (not (map? g))
    g

    :else
    (let [classe (golpe-get g :classe "classe")
          status (golpe-get g :status-causado "status-causado" :statusCausado "statusCausado")
          alvo (golpe-get g :alvo "alvo")
          slug-bruto (golpe-get g :slug "slug")
          tipo (golpe-get g :tipo "tipo")
          nome-bruto (golpe-get g :nome-exibicao "nome-exibicao" :nomeExibicao "nomeExibicao" :nome "nome")
          nome-ex (nome-golpe-exibivel nome-bruto slug-bruto tipo)
          ;; Slug canônico: legado só com "nome" → deriva do nome (nunca deixa slim sem id).
          slug-legado (when-let [s (some-> slug-bruto str str/trim)]
                        (when (seq s) s))
          wave-crash? (= "wave-crash" (:slug (move-names/resolver (or slug-legado nome-ex))))
          slug (or (when wave-crash? "wave-crash")
                   slug-legado
                   (when-let [n (some-> nome-ex str str/trim)]
                     (when (and (seq n) (not= n "?"))
                       (-> n str/lower-case
                           (java.text.Normalizer/normalize java.text.Normalizer$Form/NFD)
                           (str/replace #"\p{M}" "")
                           (str/replace #"[ _]+" "-")
                           (str/replace #"[^a-z0-9\-]" "")))))]
      (cond-> {:nome-exibicao nome-ex
               :tipo tipo
               :poder (or (golpe-get g :poder "poder") 0)
               :classe (keyword (or classe "fisico"))
               :precisao (golpe-get g :precisao "precisao")
               :status-causado (when status (keyword status))
               :chance-status (golpe-get g :chance-status "chance-status" :chanceStatus "chanceStatus")
               :cura (golpe-get g :cura "cura")
               :dreno (golpe-get g :dreno "dreno")
               :recuo (golpe-get g :recuo "recuo")
               :autodesmaio (or (golpe-get g :autodesmaio "autodesmaio") false)
               :min-acertos (golpe-get g :min-acertos "min-acertos" :minAcertos "minAcertos")
               :max-acertos (golpe-get g :max-acertos "max-acertos" :maxAcertos "maxAcertos")
               :alteracoes (golpe-get g :alteracoes "alteracoes")
               :alvo (when alvo (keyword alvo))}
        (seq slug) (assoc :slug slug)
        (and (nil? nome-ex) (str/blank? (str slug)) (nil? tipo))
        (assoc :golpe-irrecuperavel? true)))))

(defn- slim-golpe-default?
  "Defaults V1 (0/[]/precisao 100/alvo adversário) — ausente = mesmo efeito no loop."
  [[k v]]
  (or (nil? v)
      (and (#{:cura :dreno :recuo :chance-status} k) (number? v) (zero? (long v)))
      (and (= :autodesmaio k) (not v))
      (and (= :alteracoes k) (or (nil? v) (and (sequential? v) (empty? v))))
      (and (= :precisao k) (number? v) (= 100 (long v)))
      (and (= :alvo k) (or (= :adversario v) (= "adversario" v)))))

(defn slim-golpe-for-battle
  "Mantém só campos usados em dano/status/UI de menu — descarta descricao/efeitos/defaults.
   Também usado em leitos Joy / snapshots fora de batalha."
  [g]
  (when-let [n (golpe-normalizado g)]
    (when (map? n)
      (let [combat (select-keys n [:slug :nome-exibicao :tipo :poder :classe
                                   :precisao :status-causado :chance-status
                                   :cura :dreno :recuo :autodesmaio :min-acertos :max-acertos
                                   :alteracoes :alvo :golpe-irrecuperavel?])]
        (into {} (remove slim-golpe-default? combat))))))

(defn slim-pokemon-for-battle
  "Remove blobs de coleção do Pokémon embutido no estado de batalha."
  ([pokemon] (slim-pokemon-for-battle pokemon {:keep-images? true}))
  ([pokemon {:keys [keep-images?] :or {keep-images? true}}]
   (when pokemon
     (let [keep (cond-> battle-pokemon-keep-keys
                  (not keep-images?) (disj :imagem :imagem-shiny))
           raw-golpes (or (when (sequential? (:golpes pokemon)) (:golpes pokemon))
                          (when (sequential? (get pokemon "golpes")) (get pokemon "golpes")))
           base (-> pokemon
                    (#(apply dissoc % battle-pokemon-drop-keys))
                    (cond-> (and (not keep-images?) (seq battle-pokemon-drop-keys-bench))
                      (#(apply dissoc % battle-pokemon-drop-keys-bench)))
                    (dissoc "golpes")
                    (cond-> raw-golpes
                      (assoc :golpes (mapv slim-golpe-for-battle raw-golpes))))]
       (select-keys base keep)))))

(defn- slim-reserva-entry
  "Reserva do desafiante: {:collection-index :pokemon}; NPC/PvP: mapa Pokémon.
   Mantém imagem* — gzip cobre; sem URL o WA mostra caixa preta no switch."
  [entry]
  (cond
    (and (map? entry) (contains? entry :pokemon))
    (update entry :pokemon #(slim-pokemon-for-battle % {:keep-images? true}))
    (map? entry)
    (slim-pokemon-for-battle entry {:keep-images? true})
    :else entry))

(defn- slim-reservas
  [reservas]
  (when reservas
    (cond-> reservas
      (sequential? (:x reservas)) (update :x #(mapv slim-reserva-entry %))
      (sequential? (:o reservas)) (update :o #(mapv slim-reserva-entry %)))))

(defn slim-battle-state
  "Compacta snapshots embutidos (ativos + reservas) sem tocar na coleção.
   Estratégia única caça/ginásio/PvP.
   Ativos mantêm imagem* (UI da arena). Reservas 3×3 sem sprites (gzip cobre o resto)."
  [state]
  (cond-> state
    (map? (get-in state [:pokemons :x]))
    (update-in [:pokemons :x] #(slim-pokemon-for-battle % {:keep-images? true}))
    (map? (get-in state [:pokemons :o]))
    (update-in [:pokemons :o] #(slim-pokemon-for-battle % {:keep-images? true}))
    (map? (:reservas state))
    (update :reservas slim-reservas)))

(defn- assert-edn-within-limit!
  [attr s]
  (when (> (count s) datomic-local-string-limit)
    (throw (err/error :datomic_value_too_large
                      {:attr attr
                       :bytes (count s)
                       :limit datomic-local-string-limit})))
  s)

(defn- gzip-b64
  [^String s]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [gz (GZIPOutputStream. baos)]
      (.write gz (.getBytes s StandardCharsets/UTF_8)))
    (.encodeToString (Base64/getEncoder) (.toByteArray baos))))

(defn- gunzip-b64
  [^String b64]
  (let [bytes (.decode (Base64/getDecoder) b64)
        bais (ByteArrayInputStream. bytes)]
    (with-open [gz (GZIPInputStream. bais)]
      (slurp gz :encoding "UTF-8"))))

(defn decode-compact-edn
  "Lê state-edn/team-edn plain ou compactado (odisseu.gz;…)."
  [s]
  (when (and s (not (str/blank? s)))
    (if (str/starts-with? s compact-edn-prefix)
      (edn/read-string (gunzip-b64 (subs s (count compact-edn-prefix))))
      (edn/read-string s))))

(defn encode-value-edn
  "EDN plain se couber; senão gzip+Base64 com prefixo, ainda sob o limite Local.
   Usado por batalha, ginásio e leitos Joy (:enfermaria-bed/pokemon-edn)."
  [attr value]
  (let [plain (pr-str value)]
    (if (<= (count plain) datomic-local-string-limit)
      plain
      (assert-edn-within-limit!
       attr
       (str compact-edn-prefix (gzip-b64 plain))))))

(defn- encode-compact-edn!
  [attr value]
  (encode-value-edn attr value))

(defn encode-state-edn
  "Serializa estado de batalha para :battle/state-edn dentro do limite Local.
   Não altera a coleção persistida — só o snapshot embutido na batalha.
   Caça e ginásio/PvP compartilham a mesma estratégia (ativos + reservas).
   Se o slim ainda exceder 4000 (ginásio 3×3 gordo), compacta com gzip."
  [state]
  (encode-value-edn :battle/state-edn (slim-battle-state state)))

(defn encode-gym-team-edn
  "Snapshot de ocupação (:gym-occupation/team-edn) — mesmos drops da batalha.
   Mantém :imagem/:imagem-shiny (URLs curtas): sem elas a arena do desafiante
   renderiza caixa preta no lado do líder. Histórico canônico fica em :pokemon/*."
  [team]
  (encode-value-edn
   :gym-occupation/team-edn
   (mapv #(slim-pokemon-for-battle % {:keep-images? true}) (or team []))))

(defn create-wild-battle
  "Cria estado inicial de caçada. Intimidate aplica-se na criação."
  [{:keys [battle-id chat-id player-id save-id fence-generation
           pokemon-x pokemon-o hp-x hp-o status-x status-o
           collection-index]}]
  (when-not battle-id
    (throw (err/error :missing_battle_id {})))
  (let [pokemon-x (slim-pokemon-for-battle pokemon-x)
        ;; Selvagem: raridade canônica obrigatória no snapshot (captura/UI/KO).
        pokemon-o (-> pokemon-o
                      capture/assegurar-raridade
                      slim-pokemon-for-battle
                      capture/assegurar-raridade)
        base {:battle-id battle-id
              :kind :wild
              :chat-id chat-id
              :player-id player-id
              :save-id save-id
              :fence-generation fence-generation
              :collection-index (long (or collection-index 0))
              :pokemons {:x pokemon-x :o pokemon-o}
              ;; `or` trata 0 como ausente — HP 0 legítimo (desmaio) deve permanecer.
              :hp {:x (long (if (some? hp-x)
                              hp-x
                              (let [mx (max 0 (long (or (:hp pokemon-x) 0)))
                                    cur (:hp-atual pokemon-x)]
                                (cond (nil? cur) mx
                                      (neg? (long cur)) 0
                                      :else (min (long cur) mx)))))
                   :o (long (if (some? hp-o)
                              hp-o
                              (let [mx (max 0 (long (or (:hp pokemon-o) 0)))
                                    cur (:hp-atual pokemon-o)]
                                (cond (nil? cur) mx
                                      (neg? (long cur)) 0
                                      :else (min (long cur) mx)))))}
              :status {:x (kw-status status-x) :o (kw-status status-o)}
              :estagios {:x {} :o {}}
              :defendendo {:x false :o false}
              :itens-usados {:x {} :o {}}
              :turn-index 0
              :phase :active
              :outcome nil
              :active? true
              ;; Índice canônico na coleção — XP de derrota/vitória resolve por id,
              ;; mas indices-ativos cobre fallbacks e UI de troca.
              :indices-ativos {:x (long (or collection-index 0))}}
        x-int? (= "intimidate" (or (:habilidade pokemon-x) (get pokemon-x "habilidade")))
        o-int? (= "intimidate" (or (:habilidade pokemon-o) (get pokemon-o "habilidade")))
        reduzir (fn [b marca]
                  (update-in b [:pokemons marca :ataque]
                             #(Math/round (* (double (or % 0)) (/ 2.0 3.0)))))]
    (cond-> base
      (and x-int? o-int?) (-> (reduzir :x) (reduzir :o))
      (and x-int? (not o-int?)) (reduzir :o)
      (and o-int? (not x-int?)) (reduzir :x))))

(defn- aplicar-restos
  [battle marca]
  (let [pokemon (get-in battle [:pokemons marca])
        atual (get-in battle [:hp marca])
        max-hp (long (:hp pokemon 0))
        cura (if (and (= "restos" (:item pokemon)) (pos? atual) (< atual max-hp))
               (max 1 (quot max-hp 16))
               0)
        novo (min max-hp (+ atual cura))]
    [(assoc-in battle [:hp marca] novo) (- novo atual)]))

(defn- aplicar-dot
  [battle marca]
  (let [st (status/->kw (get-in battle [:status marca]))
        maximo (get-in battle [:pokemons marca :hp])]
    (if (= :envenenado-grave st)
      (let [tick (mechanics/tick-veneno-grave battle marca)]
        [(:battle tick) (:dot tick) (when (:mensagem tick) [(:mensagem tick)])])
      (let [dot (status/dano-por-status st maximo)]
        (if (pos? dot)
          [(update-in battle [:hp marca] #(max 0 (- % dot))) dot nil]
          [battle 0 nil])))))

(defn aplicar-fim-de-turno
  [battle marca & {:keys [fecha-rodada?]}]
  (let [[b _] (aplicar-restos battle marca)
        seme (mechanics/tick-semente b marca)
        res (mechanics/tick-residual (:battle seme) marca)
        [b2 dot msgs-dot] (aplicar-dot (:battle res) marca)
        fim (mechanics/tick-fim b2 marca :fecha-rodada? fecha-rodada?)]
    {:battle (:battle fim)
     :dot dot
     :mensagens (vec (concat (:mensagens seme) (:mensagens res) msgs-dot
                             (:mensagens fim)))}))

(defn- anexar-linhas
  [la linhas]
  (if (seq linhas)
    (update la :mensagem #(str % (when (and % (seq %)) "\n") (str/join "\n" linhas)))
    la))

(defn- aplicar-alteracoes-resultado
  "Aplica estágios e devolve o que mudou de fato, já limitado a −6..+6."
  [battle marca alteracoes]
  (reduce (fn [{:keys [battle aplicadas]} alt]
            (let [atributo (or (:atributo alt) (get alt "atributo"))
                  attr (if (keyword? atributo) atributo (keyword (str atributo)))
                  pedido (long (or (:estagios alt) (get alt "estagios") 0))
                  antes (long (get-in battle [:estagios marca attr] 0))
                  soma (+ antes pedido)
                  depois (-> soma (max -6) (min 6))]
              {:battle (assoc-in battle [:estagios marca attr] depois)
               :aplicadas (conj aplicadas {:atributo attr
                                           :antes antes
                                           :depois depois
                                           :pedido pedido
                                           :limitado? (not= depois soma)})}))
          {:battle battle :aplicadas []}
          (or alteracoes [])))

(defn- aplicar-alteracoes
  [battle marca alteracoes]
  (:battle (aplicar-alteracoes-resultado battle marca alteracoes)))

(defn- anexar-efeitos
  [la nome-atk nome-def alvo aplicadas status-aplicado cura & {:keys [linhas]}]
  (let [extras (vec (concat (move-effects/linhas-estagios aplicadas nome-atk nome-def alvo)
                            (keep identity [(move-effects/linha-status status-aplicado nome-def)
                                            (move-effects/linha-cura cura nome-atk)])
                            (or linhas [])))]
    (cond-> la
      (seq aplicadas) (assoc :efeitos-aplicados (vec aplicadas))
      status-aplicado (assoc :status-aplicado status-aplicado)
      cura (assoc :cura-aplicada cura)
      (seq extras) (update :mensagem #(str % (when (and % (seq %)) "\n")
                                           (str/join "\n" extras))))))

(declare forcar-troca resolve-faint)

(defn- executar-golpe-status
  [battle golpe ator alvo rng]
  (let [golpe (assoc golpe :precisao
                     (mechanics/precisao-no-clima golpe (get-in battle [:clima :tipo])))
        bloqueado? (mechanics/protecao-bloqueia? battle alvo golpe)
        battle (if bloqueado? (mechanics/consumir-protecao battle alvo) battle)
        chance (dmg/chance-acerto (:precisao golpe)
                                  (get-in battle [:estagios ator])
                                  (get-in battle [:estagios alvo])
                                  (:ignora-precisao golpe))
        errou? (and (not bloqueado?)
                    chance (< chance 100)
                    (>= (rng/roll rng :status-accuracy 100) chance))
        familia (:familia golpe)
        prot (when (and (not errou?) (not bloqueado?) (= :protecao familia))
               (mechanics/tentar-protecao battle ator rng))
        battle (or (:battle prot) battle)
        semente (when (and (not errou?) (not bloqueado?) (nil? prot) (= :semente familia))
                  (mechanics/aplicar-semente battle alvo ator
                                              (get-in battle [:pokemons alvo :tipos])))
        battle (or (:battle semente) battle)
        campo (when (and (not errou?) (not bloqueado?) (nil? prot) (nil? semente))
                (mechanics/aplicar-familia-de-campo battle golpe alvo ator))
        battle (or (:battle campo) battle)
        battle (cond
                 (or errou? bloqueado? prot semente campo) battle
                 (= :limpar-status-proprio familia)
                 (mechanics/limpar-status-lado battle ator :proprio)
                 (= :limpar-status-equipe familia)
                 (mechanics/limpar-status-lado battle ator :equipe)
                 (= :limpar-estagios familia)
                 (mechanics/limpar-estagios battle)
                 :else battle)
        max-hp (long (get-in battle [:pokemons ator :hp] 0))
        cura (when (and (not errou?) (not bloqueado?) (:cura golpe) (pos? max-hp))
               (max 1 (quot (* max-hp (long (:cura golpe))) 100)))
        battle (if cura
                 (update-in battle [:hp ator] #(min max-hp (+ (long %) cura)))
                 battle)
        marca-est (if (= :adversario (:alvo golpe)) alvo ator)
        alt (if (and (not errou?) (not bloqueado?) (nil? prot)
                     (seq (:alteracoes golpe))
                     (mechanics/chance-de-alteracao? golpe rng))
              (aplicar-alteracoes-resultado battle marca-est (:alteracoes golpe))
              {:battle battle :aplicadas []})
        battle (:battle alt)
        marca-st (if (= :proprio (:alvo golpe)) ator alvo)
        tipos-st (get-in battle [:pokemons marca-st :tipos])
        bloqueio-campo? (mechanics/terreno-bloqueia-status?
                         battle marca-st (:status-causado golpe) tipos-st)
        aplicado (when (and (not errou?) (not bloqueado?) (nil? prot) (nil? semente)
                            (nil? campo) (not bloqueio-campo?)
                            (:status-causado golpe))
                   (status/tentar-status-do-golpe
                    golpe (get-in battle [:status marca-st]) rng tipos-st))
        battle (cond-> battle
                 aplicado (assoc-in [:status marca-st] aplicado)
                 (= :envenenado-grave aplicado)
                 (mechanics/iniciar-veneno-grave marca-st))
        troca (when (and (not errou?) (not bloqueado?) (nil? prot)
                         (= :troca-forcada familia)
                         (pos? (long (get-in battle [:hp alvo] 0))))
                (forcar-troca battle alvo))
        battle (or (:battle troca) battle)
        linhas (cond
                 bloqueado? []
                 errou? []
                 (:mensagem prot) [(:mensagem prot)]
                 (:mensagem semente) [(:mensagem semente)]
                 (:imune? semente) ["⚠️ A semente não pegou neste tipo!"]
                 (:mensagem campo) [(:mensagem campo)]
                 bloqueio-campo? ["🌫️ O terreno impediu o status!"]
                 (:mensagem troca) [(:mensagem troca)]
                 (= :limpar-status-equipe familia) ["✨ Os status da equipe foram curados!"]
                 (= :limpar-status-proprio familia) ["✨ O status foi curado!"]
                 (= :limpar-estagios familia) ["✨ Os estágios dos dois lados foram zerados!"]
                 :else [])]
    {:battle battle
     :errou? (boolean errou?)
     :bloqueado? (boolean bloqueado?)
     :troca-forcada? (boolean (:troca-forcada? troca))
     :aplicadas (:aplicadas alt)
     :status-aplicado aplicado
     :cura cura
     :linhas linhas
     :alvo (:alvo golpe)}))

(defn- maybe-focus-band
  [battle def-marca hp-antes dano acertou?]
  (let [defensor (get-in battle [:pokemons def-marca])
        item (:item defensor)
        usado? (get-in battle [:itens-usados def-marca :faixa-foco])
        faixa? (and acertou?
                    (= "faixa-foco" item)
                    (= (long hp-antes) (long (:hp defensor)))
                    (>= (long dano) (long hp-antes))
                    (not usado?))]
    (if faixa?
      (-> battle
          (assoc-in [:hp def-marca] 1)
          (assoc-in [:itens-usados def-marca :faixa-foco] true))
      (assoc-in battle [:hp def-marca] (max 0 (- (long hp-antes) (long dano)))))))

(defn- finish-lose [battle]
  (assoc battle :phase :finished :outcome :lose :active? false
         :aguardando-captura? false))

(defn- finish-flee [battle]
  (assoc battle :phase :finished :outcome :flee :active? false
         :aguardando-captura? false))

(defn- finish-win [battle]
  (assoc battle :phase :opponent-fainted :outcome :win :active? false
         :aguardando-captura? true))

(defn- finish-gym-win [battle]
  (assoc battle :phase :finished :outcome :win :active? false
         :aguardando-captura? false))

(defn- finish-gym-lose [battle]
  (assoc battle :phase :finished :outcome :lose :active? false
         :aguardando-captura? false))

(defn- finish-gym-draw [battle]
  (assoc battle :phase :finished :outcome :draw :active? false
         :aguardando-captura? false))

(defn- finish-pvp
  "outcome: :x | :o (vencedor) | :draw | :forfeit-x | :forfeit-o"
  [battle outcome]
  (assoc battle :phase :finished :outcome outcome :active? false
         :aguardando-captura? false
         :aguardando-substituicao nil
         :desafio-expira-em nil))

(defn finalize-pvp-replacement-gate
  "Se há substituição pendente e o lado não tem mon elegível, encerra o PvP.
  Coleções são snapshots atuais (HP/status); domínio puro além disso."
  [battle colecao-x colecao-o]
  (let [w (pvp/aguardando-substituicao-de battle)]
    (if-not w
      battle
      (let [can-x? (pvp/tem-substituicao-elegivel? battle :x colecao-x)
            can-o? (pvp/tem-substituicao-elegivel? battle :o colecao-o)]
        (case w
          :both (cond
                  (and can-x? can-o?) battle
                  can-x? (finish-pvp battle :x)
                  can-o? (finish-pvp battle :o)
                  :else (finish-pvp battle :draw))
          :x (if can-x? battle (finish-pvp battle :o))
          :o (if can-o? battle (finish-pvp battle :x))
          battle)))))

(defn- registrar-nocaute
  "Conta KO para o Pokémon ativo do lado oposto ao caído."
  [battle caido]
  (let [marca (if (= caido :x) :o :x)
        idx (get-in battle [:indices-ativos marca])]
    (if (and (zero? (long (get-in battle [:hp caido] 0))) (some? idx))
      (update-in battle [:participacao marca idx] (fnil inc 0))
      battle)))

(defn- enviar-proximo
  "Traz o próximo da reserva; retorna nil se não houver."
  [battle marca]
  (let [reservas (vec (get-in battle [:reservas marca] []))]
    (when (seq reservas)
      (let [prox (first reservas)
            npc? (and (= :gym (:kind battle)) (= marca :o))
            pokemon (if npc?
                      prox
                      (:pokemon prox))
            idx (when-not npc? (:collection-index prox))
            hp (long (or (:hp-atual pokemon) (:hp pokemon) 0))
            status (:status pokemon)
            saida (-> battle
                      (assoc-in [:reservas marca] (vec (rest reservas)))
                      (assoc-in [:pokemons marca] pokemon)
                      (assoc-in [:hp marca] hp)
                      (assoc-in [:status marca] (kw-status status))
                      (assoc-in [:estagios marca] {})
                      (mechanics/limpar-volatil marca)
                      (assoc-in [:defendendo marca] false)
                      (assoc-in [:itens-usados marca] {})
                      (cond-> idx (assoc-in [:indices-ativos marca] idx)
                              (and idx (nil? (get-in battle [:participacao marca idx])))
                              (assoc-in [:participacao marca idx] 0))
                      (assoc :last-switch {:side marca :nome (:nome pokemon)}))
            ent (mechanics/aplicar-entrada saida marca)
            saida (update-in (:battle ent) [:last-switch :mensagens]
                             (fnil into []) (:mensagens ent))]
        (if (and (:active? saida) (zero? (long (get-in saida [:hp marca] 0))))
          (resolve-faint saida marca)
          saida)))))

(defn- forcar-troca
  "Rugido e cauda de dragão. Caça encerra sem captura. Ginásio puxa a reserva.
  PvP não escolhe o Pokémon do outro: obriga a troca. Armadilha bloqueia."
  [battle def-marca]
  (cond
    (mechanics/preso? battle def-marca)
    {:battle battle :mensagem "🕸️ A armadilha impede a troca!"}

    (not (#{:pvp :gym} (:kind battle)))
    {:battle (finish-flee battle)
     :troca-forcada? true
     :mensagem (if (= def-marca :o)
                 "😱 O adversário foi obrigado a fugir!"
                 "😱 Você foi obrigado a fugir!")}

    (= :pvp (:kind battle))
    {:battle (assoc battle :aguardando-substituicao def-marca :vez def-marca)
     :troca-forcada? true
     :mensagem "🔄 O adversário foi obrigado a trocar!"}

    :else
    (if-let [b (enviar-proximo battle def-marca)]
      {:battle b
       :troca-forcada? true
       :mensagem "🔄 O adversário foi obrigado a trocar!"}
      {:battle battle
       :mensagem "⚠️ Não há para onde trocar!"})))

(defn- mark-fainted-x
  [battle]
  (if-let [idx (get-in battle [:indices-ativos :x])]
    (update battle :fainted-x (fnil conj #{}) (long idx))
    battle))

(defn- mark-eliminado-pvp
  "Marca o índice ativo do lado como eliminado nesta partida."
  [battle side]
  (if-let [idx (get-in battle [:indices-ativos side])]
    (update-in battle [:eliminados side] (fnil conj #{}) (long idx))
    battle))

(defn- request-pvp-replacement
  "Após nocaute: aguarda substituição obrigatória (store valida elegíveis)."
  [battle side]
  (let [vez (if (= side :both) nil side)]
    (-> battle
        (assoc :aguardando-substituicao side
               :vez vez
               :active? true
               :phase :active)
        (assoc :last-faint {:side side
                            :nome (when-not (= side :both)
                                    (get-in battle [:pokemons side :nome]))
                            :index (when-not (= side :both)
                                     (get-in battle [:indices-ativos side]))}))))

(defn- resolve-faint
  "Após HP 0: em caça encerra; em ginásio troca reserva ou encerra o match;
   em PvP marca eliminado e aguarda substituição (até 3 distintos); sem apto
   o store finaliza. Caça permanece 1×1 sem troca.

  Ginásio + Explosion: se X e O caem no mesmo golpe, marcar o índice de X em
  `fainted-x` *antes* de `enviar-proximo` — senão o sync pós-vitória não zera
  o HP do mon que explodiu (causa-raiz P0)."
  [battle fainted-side]
  (cond
    (= :pvp (:kind battle))
    (let [b (-> battle
                (registrar-nocaute fainted-side)
                (mark-eliminado-pvp fainted-side))
          hx (long (get-in b [:hp :x] 0))
          ho (long (get-in b [:hp :o] 0))]
      (cond
        (and (zero? hx) (zero? ho))
        (-> b
            (mark-eliminado-pvp (if (= fainted-side :x) :o :x))
            (request-pvp-replacement :both))
        (zero? hx) (request-pvp-replacement b :x)
        (zero? ho) (request-pvp-replacement b :o)
        :else b))

    (not= :gym (:kind battle))
    (if (= fainted-side :o)
      (finish-win battle)
      (finish-lose battle))

    :else
    (let [b0 (cond-> battle (= fainted-side :x) mark-fainted-x)
          b (registrar-nocaute b0 fainted-side)
          both-down? (and (zero? (long (get-in b [:hp :x] 0)))
                          (zero? (long (get-in b [:hp :o] 0))))]
      (if both-down?
        ;; Marca X desmaiado mesmo quando o nocaute reportado foi :o (Explosion).
        (let [b (cond-> b (zero? (long (get-in b [:hp :x] 0))) mark-fainted-x)
              bx (or (when (zero? (get-in b [:hp :x])) (enviar-proximo b :x)) b)
              bo (or (when (zero? (get-in bx [:hp :o])) (enviar-proximo bx :o)) bx)
              still-x (pos? (long (get-in bo [:hp :x] 0)))
              still-o (pos? (long (get-in bo [:hp :o] 0)))]
          (cond
            (and still-x still-o) bo
            still-x (finish-gym-win bo)
            still-o (finish-gym-lose bo)
            :else (finish-gym-draw bo)))
        (if-let [swapped (enviar-proximo b fainted-side)]
          swapped
          (if (= fainted-side :o)
            (finish-gym-win b)
            (finish-gym-lose b)))))))

(defn- resolve-status-gate
  "Aplica cura espontânea / impedimento com RNG já no mapa `rng`."
  [battle marca rng & {:keys [fecha-rodada?]}]
  (let [st (get-in battle [:status marca])
        imp (status/impedimento-status st rng)
        battle (cond-> battle (:curou? imp) (assoc-in [:status marca] nil))]
    (if (:impedido? imp)
      (let [auto (if (:auto-dano? imp)
                   (status/auto-dano-confusao (get-in battle [:pokemons marca :hp]))
                   0)
            battle (update-in battle [:hp marca] #(max 0 (- % auto)))
            fim (aplicar-fim-de-turno battle marca :fecha-rodada? fecha-rodada?)]
        {:battle (:battle fim)
         :impedido? true
         :auto-dano auto
         :texto (:texto imp)})
      {:battle battle :impedido? false :texto (:texto imp)})))

(defn- apply-offensive-hit
  [battle atk-marca def-marca golpe defendendo? rng & {:keys [alvo-ja-agiu?]}]
  (let [        golpe (merge (golpe-normalizado golpe)
                    (select-keys golpe [:familia :flinch :so-primeiro-turno
                                        :atravessa-protecao :ignora-precisao
                                        :invulneravel :chance-alteracao
                                        :turnos-min :turnos-max
                                        :min-acertos :max-acertos
                                        :chance-critico :critico-garantido
                                        :precisao-chuva :precisao-sol
                                        :precisao-neve :pula-carga-no-sol
                                        :campo]))
        bloqueado? (mechanics/protecao-bloqueia? battle def-marca golpe)
        invulneravel? (and (get-in battle [:volatil def-marca :invulneravel?])
                           (not (:atravessa-protecao golpe))
                           (not= :aterrar (:familia golpe)))
        atacante (get-in battle [:pokemons atk-marca])
        defensor (get-in battle [:pokemons def-marca])
        resolved (cond
                   bloqueado?
                   {:dano 0 :acertou? false :bloqueado? true :errou? false
                    :esquivou? false :multiplicador 1 :acertos 1 :critico? false}
                   invulneravel?
                   {:dano 0 :acertou? false :errou? false :sumido? true :esquivou? false
                    :bloqueado? false :multiplicador 1 :acertos 1 :critico? false}
                   :else
                   (dmg/resolver-ataque
                    {:golpe golpe
                     :atacante atacante
                     :defensor defensor
                     :estagios-atacante (get-in battle [:estagios atk-marca])
                     :estagios-defensor (get-in battle [:estagios def-marca])
                     :status-defensor (get-in battle [:status def-marca])
                     :defendendo? defendendo?
                     :hp-atacante-atual (get-in battle [:hp atk-marca])
                     :clima (get-in battle [:clima :tipo])
                     :aterrado? (get-in battle [:volatil def-marca :aterrado?])
                     :rng rng}))
        hp-vivo (long (get-in battle [:hp def-marca] 0))
        visao? (= :visao (:familia golpe))
        resolved (cond
                   (or (not visao?) bloqueado? invulneravel?) resolved
                   (get-in battle [:campo :previsto def-marca])
                   (assoc resolved :dano 0 :acertou? false :errou? true
                          :visao-ocupada? true :critico? false)
                   (:acertou? resolved)
                   (assoc resolved :visao? true :dano-previsto (:dano resolved) :dano 0)
                   :else resolved)
        resolved (if (or (not= :nocaute (:familia golpe)) bloqueado? invulneravel?)
                   resolved
                   (if (and (:acertou? resolved)
                            (>= (long (or (:nivel atacante) 1))
                                (long (or (:nivel defensor) 1))))
                     (assoc resolved :dano hp-vivo :nocaute? true)
                     (assoc resolved :dano 0 :acertou? false :errou? true
                            :critico? false :nocaute? false)))
        battle (cond-> battle
                 bloqueado? (mechanics/consumir-protecao def-marca))
        hp-antes (get-in battle [:hp def-marca])
        battle (if (or bloqueado? invulneravel?)
                 battle
                 (maybe-focus-band battle def-marca hp-antes (:dano resolved) (:acertou? resolved)))
        st-def (get-in battle [:status def-marca])
        classe (let [c (:classe golpe)] (if (keyword? c) c (keyword c)))
        tipos-def (get-in battle [:pokemons def-marca :tipos])
        bloqueio-campo? (and (:acertou? resolved)
                             (mechanics/terreno-bloqueia-status?
                              battle def-marca (:status-causado golpe) tipos-def))
        tri (when (and (:acertou? resolved) (= :tri-ataque (:familia golpe)))
               (mechanics/status-tri-ataque st-def rng tipos-def))
        tri (when (and tri (not (mechanics/terreno-bloqueia-status?
                                 battle def-marca tri tipos-def)))
              tri)
        novo-status (or tri
                        (when (and (:acertou? resolved) (not bloqueio-campo?))
                          (status/tentar-status-do-golpe golpe st-def rng tipos-def))
                        (when (and (:acertou? resolved)
                                   (not bloqueio-campo?)
                                   (nil? (:status-causado golpe))
                                   (contains? #{:fisico :especial} classe))
                          (status/tentar-contagiar (:tipo golpe) st-def rng)))
        battle (cond-> battle
                 (:visao? resolved)
                 (assoc-in [:campo :previsto def-marca]
                           {:restante 3
                            :dano (long (:dano-previsto resolved 0))
                            :nome (move-names/apresentar golpe)})
                 (and (:acertou? resolved) (= :aterrar (:familia golpe)))
                 (assoc-in [:volatil def-marca :aterrado?] true)
                 (and (:acertou? resolved) (= :aterrar (:familia golpe))
                      (get-in battle [:volatil def-marca :no-ar?]))
                 (-> (update-in [:volatil def-marca] dissoc :no-ar? :invulneravel?)
                     (update-in [:volatil atk-marca] dissoc :no-ar? :invulneravel? :carga))
                 novo-status (assoc-in [:status def-marca] novo-status)
                 (= :envenenado-grave novo-status)
                 (mechanics/iniciar-veneno-grave def-marca)
                 (and (:acertou? resolved) (= :residual (:familia golpe)))
                 (mechanics/aplicar-residual def-marca golpe rng)
                 (and (:acertou? resolved) (= :limpar-volatil (:familia golpe)))
                 (mechanics/limpar-semente-e-armadilha atk-marca)
                 (and (:acertou? resolved) (= :limpar-volatil (:familia golpe)))
                 (mechanics/limpar-hazards atk-marca))
        dreno (when (and (:acertou? resolved) (:dreno golpe))
                (max 1 (quot (* (:dano resolved) (long (:dreno golpe))) 100)))
        dano-infligido (- (long hp-antes) (long (get-in battle [:hp def-marca] 0)))
        recuo (when (and (:acertou? resolved) (pos? dano-infligido) (:recuo golpe)
                         (not (:autodesmaio golpe)))
                (max 1 (quot (* dano-infligido (long (:recuo golpe))) 100)))
        ;; Explosion/Self-Destruct: após hit válido, usuário desmaia (HP→0).
        autodesmaio? (and (:acertou? resolved)
                          (or (:autodesmaio golpe) (get golpe "autodesmaio"))
                          (pos? (long (:dano resolved 0))))
        max-atk (get-in battle [:pokemons atk-marca :hp])
        battle (cond-> battle
                 dreno (update-in [:hp atk-marca] #(min max-atk (+ % dreno)))
                 recuo (update-in [:hp atk-marca] #(max 0 (- % recuo)))
                 autodesmaio? (assoc-in [:hp atk-marca] 0)
                 ;; Snapshot embutido acompanha o HP de combate (evita UI/resync com HP cheio).
                 autodesmaio? (assoc-in [:pokemons atk-marca :hp-atual] 0))
        battle (if (and (:acertou? resolved) (seq (:alteracoes golpe))
                        (mechanics/chance-de-alteracao? golpe rng))
                 (let [alvo (if (= :proprio (:alvo golpe)) atk-marca def-marca)
                       resultado (aplicar-alteracoes-resultado battle alvo (:alteracoes golpe))]
                   (vary-meta (:battle resultado) assoc :odisseu/aplicadas (:aplicadas resultado)
                              :odisseu/alvo-alt (:alvo golpe)))
                 battle)
        battle (-> battle
                   (mechanics/marcar-flinch def-marca golpe (:acertou? resolved)
                                            (boolean alvo-ja-agiu?) rng)
                   (mechanics/agendar-recarga atk-marca golpe))
        troca (when (and (:acertou? resolved)
                         (= :troca-forcada (:familia golpe))
                         (pos? (long (get-in battle [:hp def-marca] 0))))
                (forcar-troca battle def-marca))
        battle (or (:battle troca) battle)]
    {:battle (with-meta battle nil)
     :aplicadas (:odisseu/aplicadas (meta battle))
     :alvo-alteracao (:odisseu/alvo-alt (meta battle))
     :resolved resolved
     :status-aplicado novo-status
     :troca-forcada? (boolean (:troca-forcada? troca))
     :linhas (cond-> []
               recuo (conj (str "💥 O atacante sofreu " recuo " HP de recuo!"))
               bloqueio-campo? (conj "🌫️ O terreno impediu o status!")
               (and (:acertou? resolved) (= :aterrar (:familia golpe)))
               (conj "🪨 O Pokémon foi jogado no chão!")
               (:mensagem troca) (conj (:mensagem troca)))
     :autodesmaio? (boolean autodesmaio?)
     :hp-antes (long hp-antes)
     :hp-depois (long (get-in battle [:hp def-marca] 0))
     :hp-atk-depois (long (get-in battle [:hp atk-marca] 0))}))

(defn- pick-wild-move
  [selvagem rng]
  (let [golpes (mapv #(move-effects/enriquecer (golpe-normalizado %))
                     (or (:golpes selvagem) []))
        n (count golpes)]
    (if (zero? n)
      golpe-padrao
      (let [g (nth golpes (rng/roll rng :wild-move n))]
        (if (or (contains? #{:fisico :especial} (:classe g))
                (:familia g)
                (:status-causado g)
                (seq (:alteracoes g))
                (:flinch g))
          g
          golpe-padrao)))))

(defn- wild-response
  "Resposta do adversário (selvagem ou líder de ginásio) após ação do jogador.
  Ginásio: sem fuga."
  [battle player-was-defending? rng]
  (cond
    (zero? (get-in battle [:hp :x]))
    (resolve-faint battle :x)

    (and (not= :gym (:kind battle))
         (< (rng/roll rng :flee 100)
            (get capture/fuga-raridade
                 (capture/raridade-de (get-in battle [:pokemons :o])) 8)))
    (finish-flee battle)

    :else
      (let [gate (resolve-status-gate battle :o rng :fecha-rodada? true)]
      (if (:impedido? gate)
        (let [b (:battle gate)]
          (cond
            (zero? (get-in b [:hp :o])) (resolve-faint b :o)
            (zero? (get-in b [:hp :x])) (resolve-faint b :x)
            :else (assoc-in b [:defendendo :x] false)))
        (let [pre (mechanics/preparar-acao (:battle gate) :o
                                          (pick-wild-move (get-in battle [:pokemons :o]) rng)
                                          rng)
              golpe (:golpe pre)
              classe (:classe golpe)
              ofensivo? (contains? #{:fisico :especial} classe)
              alt (if (and (:pular? pre) (seq (:alteracoes-carga pre)))
                    (aplicar-alteracoes-resultado (:battle pre) :o (:alteracoes-carga pre))
                    {:battle (:battle pre) :aplicadas []})
              b0 (:battle alt)
              hit (when-not (:pular? pre)
                    (if ofensivo?
                      (apply-offensive-hit b0 :o :x golpe player-was-defending? rng
                                           :alvo-ja-agiu? true)
                      (let [st (executar-golpe-status b0 golpe :o :x rng)]
                        {:battle (:battle st)
                         :resolved {:dano 0 :acertou? (not (:errou? st))
                                    :errou? (:errou? st)
                                    :bloqueado? (:bloqueado? st)
                                    :multiplicador 1 :acertos 1}
                         :aplicadas (:aplicadas st)
                         :status-aplicado (:status-aplicado st)
                         :cura (:cura st)
                         :linhas (:linhas st)
                         :alvo-alteracao (:alvo st)})))
              base-la (if (:pular? pre)
                        {:action :attack
                         :mensagem (:mensagem pre)
                         :golpe-nome (move-names/apresentar golpe)}
                        (anexar-efeitos
                         (if ofensivo?
                           (dmg/last-action-ataque
                            golpe
                            (get-in b0 [:pokemons :o])
                            (get-in b0 [:pokemons :x])
                            (:resolved hit)
                            player-was-defending?)
                           {:action :attack
                            :dano 0
                            :acertou? (not (:errou? (:resolved hit)))
                            :errou? (:errou? (:resolved hit))
                            :golpe-nome (move-names/apresentar golpe)
                            :mensagem (cond
                                        (:bloqueado? (:resolved hit))
                                        "🛡️ Proteção bloqueou o ataque!"
                                        (:errou? (:resolved hit))
                                        (str "📊 *" (move-names/apresentar golpe) "* errou!")
                                        :else
                                        (str "📊 *" (move-names/apresentar golpe) "*!"))})
                         (or (:nome (get-in b0 [:pokemons :o])) "adversário")
                         (or (:nome (get-in b0 [:pokemons :x])) "Pokémon")
                         (:alvo-alteracao hit)
                         (concat (:aplicadas alt) (:aplicadas hit))
                         (:status-aplicado hit)
                         (:cura hit)
                         :linhas (:linhas hit)))
              b (assoc-in (or (:battle hit) b0) [:defendendo :x] false)
              fim (aplicar-fim-de-turno b :o :fecha-rodada? true)
              rival-la (anexar-linhas base-la (:mensagens fim))
              b (assoc (:battle fim) :last-rival-action rival-la)]
          (cond
            (zero? (get-in b [:hp :x])) (resolve-faint b :x)
            (zero? (get-in b [:hp :o])) (resolve-faint b :o)
            :else b))))))

(defn- apply-item-heal
  "Poção mid-batalha (legado pocao-na-cacada). Inventário é consumido no store."
  [battle item-id]
  (let [id (shop/normalizar-item (str item-id))
        meta (shop/item id)
        fracao (:cura-hp meta)]
    (when-not fracao
      (throw (err/error :item_use_unsupported
                        {:item-id id :allowed ["pocao" "pocao-maxima"]})))
    (let [px (get-in battle [:pokemons :x])
          heal (shop/aplicar-pocao
                {:hp (:hp px)
                 :hp-atual (get-in battle [:hp :x])}
                fracao)]
      (-> battle
          (assoc-in [:hp :x] (long (:hp-depois heal)))
          (assoc :last-item {:item-id id
                             :hp-antes (:hp-antes heal)
                             :hp-depois (:hp-depois heal)
                             :hp-max (:hp-max heal)})))))

(defn- apply-item-status-cure
  "Cura de status mid-batalha (legado curar-na-cacada)."
  [battle item-id]
  (let [id (shop/normalizar-item (str item-id))
        st (get-in battle [:status :x])
        esperado (shop/cura-status-item (when st (name st)))]
    (when-not st
      (throw (err/error :no_status_to_cure {})))
    (when-not (= id esperado)
      (throw (err/error :wrong_status_item
                        {:item-id id :status (name st) :expected esperado})))
    (-> battle
        (assoc-in [:status :x] nil)
        (mechanics/limpar-contador-veneno :x)
        (assoc :last-item {:item-id id :status-cleared (name st)}))))

(defn apply-player-turn
  "Aplica :attack | :defend | :item do jogador e a resposta do selvagem.
  Incrementa turn-index. Exige phase=:active.
  `:item` exige `:item-id` (pocao / pocao-maxima / cura de status); consumo no store."
  [battle {:keys [action move-index item-id]} rng]
  (when-not (and (= :active (:phase battle)) (:active? battle))
    (throw (err/error :battle_not_active {:phase (:phase battle)})))
  (let [rng (or rng {})
        defending-before? (boolean (get-in battle [:defendendo :x]))]
    (case action
      :defend
      (let [gate (resolve-status-gate battle :x rng)]
        (if (:impedido? gate)
          (let [b (wild-response (:battle gate) false rng)]
            (-> b (update :turn-index inc)
                (assoc :last-action {:action :defend :impedido? true})))
          (let [b (assoc-in (:battle gate) [:defendendo :x] true)
                fim (aplicar-fim-de-turno b :x)
                b (wild-response (:battle fim) true rng)]
            (-> b (update :turn-index inc)
                (assoc :last-action {:action :defend})))))

      :item
      (let [id (shop/normalizar-item (str item-id))
            meta (shop/item id)
            gate (resolve-status-gate battle :x rng)]
        (if (:impedido? gate)
          (let [b (wild-response (:battle gate) false rng)]
            (-> b (update :turn-index inc)
                (assoc :last-action {:action :item :item-id id :impedido? true})))
          (let [b (cond
                    (:cura-hp meta) (apply-item-heal (:battle gate) id)
                    (contains? shop/status-cura id) (apply-item-status-cure (:battle gate) id)
                    :else (throw (err/error :item_use_unsupported
                                            {:item-id id
                                             :allowed ["pocao" "pocao-maxima" "status-cura"]})))
                last-item (:last-item b)
                b (dissoc b :last-item)
                fim (aplicar-fim-de-turno b :x)
                b (wild-response (:battle fim) false rng)]
            (-> b
                (update :turn-index inc)
                (assoc :last-action (merge {:action :item :item-id id} last-item))))))

      :attack
      (let [golpes (vec (or (get-in battle [:pokemons :x :golpes]) []))
            idx (long (or move-index 0))]
        (when-not (< -1 idx (count golpes))
          (throw (err/error :invalid_move_index {:idx idx :count (count golpes)})))
        (let [pre (mechanics/preparar-acao
                   battle :x
                   (move-effects/enriquecer (golpe-normalizado (nth golpes idx)))
                   rng)]
          (if (:pular? pre)
            (let [alt (if (seq (:alteracoes-carga pre))
                        (aplicar-alteracoes-resultado (:battle pre) :x (:alteracoes-carga pre))
                        {:battle (:battle pre) :aplicadas []})
                  fim (aplicar-fim-de-turno (:battle alt) :x)
                  b (wild-response (:battle fim) defending-before? rng)
                  nome (or (get-in battle [:pokemons :x :nome]) "Pokémon")
                  la (anexar-linhas
                      {:action :attack :move-index idx
                       :mensagem (:mensagem pre)
                       :golpe-nome (move-names/apresentar (:golpe pre))}
                      (concat (move-effects/linhas-estagios (:aplicadas alt) nome nome :proprio)
                              (:mensagens fim)))
                  rival (:last-rival-action b)]
              (-> b (update :turn-index inc)
                  (assoc :last-action
                         (cond-> la
                           (:mensagem rival)
                           (assoc :mensagem-rival (:mensagem rival)
                                  :dano-rival (:dano rival))))))
            (let [golpe (:golpe pre)
                  gate (resolve-status-gate (:battle pre) :x rng)]
          (if (:impedido? gate)
            (let [b (wild-response (:battle gate) false rng)
                  nome (or (get-in battle [:pokemons :x :nome]) "Pokémon")
                  msg (str "⚠️ *" nome "* " (or (:texto gate) "não conseguiu agir") "!")]
              (-> b (update :turn-index inc)
                  (assoc :last-action (cond-> {:action :attack :impedido? true
                                               :move-index idx :mensagem msg}
                                        (:mensagem (:last-rival-action b))
                                        (assoc :mensagem-rival (:mensagem (:last-rival-action b)))))))
            (let [classe (:classe golpe)
                  b0 (:battle gate)
                  atk (get-in b0 [:pokemons :x])
                  def (get-in b0 [:pokemons :o])
                  hp-o-antes (get-in b0 [:hp :o])
                  hp-x-antes (get-in b0 [:hp :x])
                  status-hit (when-not (contains? #{:fisico :especial} classe)
                               (executar-golpe-status b0 golpe :x :o rng))
                  hit (if (contains? #{:fisico :especial} classe)
                        (apply-offensive-hit b0 :x :o golpe false rng)
                        {:battle (:battle status-hit)
                         :resolved {:dano 0 :acertou? (not (:errou? status-hit))
                                    :multiplicador 1 :acertos 1
                                    :bloqueado? (:bloqueado? status-hit)
                                    :errou? (:errou? status-hit)}
                         :linhas (:linhas status-hit)
                         :troca-forcada? (:troca-forcada? status-hit)
                         :autodesmaio? false
                         :hp-antes hp-o-antes
                         :hp-depois hp-o-antes
                         :hp-atk-depois (get-in (:battle status-hit) [:hp :x])
                         :aplicadas (:aplicadas status-hit)
                         :status-aplicado (:status-aplicado status-hit)
                         :cura (:cura status-hit)
                         :alvo-alteracao (:alvo status-hit)})
                  nome-atk (or (:nome atk) "Pokémon")
                  nome-def (or (:nome def) "adversário")
                  player-la (anexar-efeitos
                             (if (contains? #{:fisico :especial} classe)
                               (dmg/last-action-ataque golpe atk def (:resolved hit) false
                                                       :move-index idx
                                                       :hp-antes (:hp-antes hit hp-o-antes)
                                                       :hp-depois (:hp-depois hit)
                                                       :hp-atk-antes hp-x-antes
                                                       :hp-atk-depois (:hp-atk-depois hit)
                                                       :autodesmaio? (:autodesmaio? hit))
                               {:action :attack :move-index idx
                                :dano 0
                                :acertou? (not (:errou? status-hit))
                                :errou? (:errou? status-hit)
                                :golpe-slug (:slug golpe)
                                :golpe-nome (move-names/apresentar golpe)
                                :tipo-golpe (:tipo golpe)
                                :classe "status"
                                :mensagem (cond
                                            (:bloqueado? status-hit)
                                            "🛡️ Proteção bloqueou o ataque!"
                                            (:errou? status-hit)
                                            (str "📊 *" (move-names/apresentar golpe)
                                                 "* de *" nome-atk "* errou!")
                                            :else
                                            (str "📊 *" (move-names/apresentar golpe)
                                                 "* de *" nome-atk "*!"))})
                             nome-atk nome-def
                             (or (:alvo-alteracao hit) (:alvo golpe))
                             (:aplicadas hit)
                             (:status-aplicado hit)
                             (:cura hit)
                             :linhas (:linhas hit))
                  b (:battle hit)
                  fim (aplicar-fim-de-turno b :x)
                  b (:battle fim)
                  b (cond
                      (zero? (get-in b [:hp :o]))
                      (let [b2 (resolve-faint b :o)]
                        ;; Ginásio: novo líder entra e age nesta mesma rodada.
                        (if (and (= :gym (:kind b2)) (:active? b2)
                                 (pos? (long (get-in b2 [:hp :o] 0)))
                                 (pos? (long (get-in b2 [:hp :x] 0))))
                          (wild-response b2 defending-before? rng)
                          b2))
                      (zero? (get-in b [:hp :x])) (resolve-faint b :x)
                      (and (:active? b) (not (:troca-forcada? hit)))
                      (wild-response b defending-before? rng)
                      :else b)
                  rival (:last-rival-action b)
                  la (cond-> (anexar-linhas player-la (:mensagens fim))
                       (:mensagem rival)
                       (assoc :mensagem-rival (:mensagem rival)
                              :dano-rival (:dano rival)))]
              (-> b
                  (update :turn-index inc)
                  (assoc :last-action la))))))))

      (throw (err/error :unknown_battle_action {:action action})))))

(defn- battle-start-hp
  "HP de abertura: nil → max; 0 legítimo permanece; nunca > max."
  [p]
  (let [mx (max 0 (long (or (:hp p) 0)))
        cur (:hp-atual p)]
    (cond
      (nil? cur) mx
      (neg? (long cur)) 0
      :else (min (long cur) (max mx 0)))))

(defn create-gym-battle
  "Combate 3×3 de ginásio. `team-x` = 3 mapas com :collection-index;
  `team-o` = 3 Pokémon (NPC ou ocupação já com motivação)."
  [{:keys [battle-id chat-id player-id save-id fence-generation
           gym-id gym-nome team-x team-o expected-revision lider-nome
           desafiante-nome]}]
  (when-not battle-id
    (throw (err/error :missing_battle_id {})))
  (when-not (and (= 3 (count team-x)) (= 3 (count team-o)))
    (throw (err/error :invalid_gym_team
                      {:x (count team-x) :o (count team-o)})))
  (let [normalize-x (fn [idx p]
                      {:collection-index (long (:collection-index p))
                       :pokemon (slim-pokemon-for-battle
                                 (dissoc p :collection-index)
                                 {:keep-images? true})})
        xs (mapv normalize-x (range) team-x)
        first-x (:pokemon (first xs))
        ;; Sprites nas reservas: evita caixa preta ao lançar o próximo.
        team-o (mapv (fn [idx p]
                       (slim-pokemon-for-battle p {:keep-images? true}))
                     (range) team-o)
        first-o (first team-o)
        idx0 (:collection-index (first xs))
        base {:battle-id battle-id
              :kind :gym
              :gym-id gym-id
              :gym-nome gym-nome
              :expected-revision (long (or expected-revision 0))
              :lider-nome lider-nome
              :desafiante-nome (or desafiante-nome player-id)
              :chat-id chat-id
              :player-id player-id
              :save-id save-id
              :fence-generation fence-generation
              :team-x-indices (mapv :collection-index xs)
              :pokemons {:x first-x :o first-o}
              :hp {:x (battle-start-hp first-x)
                   :o (battle-start-hp first-o)}
              :status {:x (kw-status (:status first-x)) :o (kw-status (:status first-o))}
              :estagios {:x {} :o {}}
              :defendendo {:x false :o false}
              :itens-usados {:x {} :o {}}
              :indices-ativos {:x idx0 :o nil}
              :participacao {:x {idx0 0} :o {}}
              :reservas {:x (vec (rest xs))
                         :o (vec (rest team-o))}
              :fainted-x #{}
              :turn-index 0
              :phase :active
              :outcome nil
              :active? true
              :aguardando-captura? false}
        x-int? (= "intimidate" (or (:habilidade first-x) (get first-x "habilidade")))
        o-int? (= "intimidate" (or (:habilidade first-o) (get first-o "habilidade")))
        reduzir (fn [b marca]
                  (update-in b [:pokemons marca :ataque]
                             #(Math/round (* (double (or % 0)) (/ 2.0 3.0)))))]
    (cond-> base
      (and x-int? o-int?) (-> (reduzir :x) (reduzir :o))
      (and x-int? (not o-int?)) (reduzir :o)
      (and o-int? (not x-int?)) (reduzir :x))))

(defn abandon
  "Desistência voluntária (!pk sair) — wild/gym (inclui aguardando captura).
   Sem recompensas."
  [battle]
  (when-not battle
    (throw (err/error :battle_not_found {})))
  (when-not (or (:active? battle) (:aguardando-captura? battle))
    (throw (err/error :battle_already_finished
                      {:phase (:phase battle) :outcome (:outcome battle)})))
  (assoc battle
         :phase :finished
         :outcome :abandoned
         :active? false
         :aguardando-captura? false
         :desafio-expira-em nil))

(defn reward-for-outcome
  "Derrota/fuga selvagem (RNG): +1 XP no Pokémon ativo (legado).
  Vitória selvagem espera captura.
  Ginásio: XP/recompensas via resolve-challenge (não aqui).
  Idle-timeout / abandoned (!pk sair): sem XP/moedas."
  [outcome]
  (case outcome
    (:lose :flee) {:pokemon-xp 1 :coins 0}
    :win {:pokemon-xp 0 :coins 0 :awaiting-capture? true}
    :draw {:pokemon-xp 0 :coins 0}
    (:idle-timeout :abandoned) {:pokemon-xp 0 :coins 0}
    {}))

(defn create-pvp-challenge
  "Abre desafio aguardando adversário no chat (fase :waiting)."
  [{:keys [battle-id chat-id player-id player-name save-id fence-generation
           pokemon-x hp-x status-x collection-index agora]}]
  (when-not battle-id
    (throw (err/error :missing_battle_id {})))
  (pvp/assert-healthy-active! pokemon-x hp-x)
  (let [agora (long (or agora (System/currentTimeMillis)))
        idx (long (or collection-index 0))
        faixa (pvp/faixa-niveis pokemon-x)]
    {:battle-id battle-id
     :kind :pvp
     :pvp? true
     :chat-id chat-id
     :player-id player-id
     :save-id save-id
     :fence-generation fence-generation
     :jogadores {:x player-id}
     :nomes {:x (or player-name player-id)}
     :saves {:x save-id}
     :fences {:x fence-generation}
     :pokemons {:x pokemon-x}
     :hp {:x (long (or hp-x (:hp-atual pokemon-x) (:hp pokemon-x) 0))}
     :status {:x (kw-status status-x)}
     :estagios {:x {}}
     :defendendo {:x false}
     :itens-usados {:x {}}
     :indices-ativos {:x idx}
     :participacao {:x {idx 0}}
     :usados {:x #{idx}}
     :eliminados {:x #{}}
     :collection-index idx
     :faixa-niveis faixa
     :desafio-expira-em (+ agora pvp/tempo-espera-ms)
     :turn-index 0
     :phase :waiting
     :outcome nil
     :active? true
     :aguardando-captura? false
     :aguardando-substituicao nil
     :vez nil}))

(defn accept-pvp-challenge
  "Segundo jogador entra; define :vez pela velocidade efetiva."
  [battle {:keys [player-id player-name save-id fence-generation
                  pokemon-o hp-o status-o collection-index agora first-turn-tie]}]
  (when-not (pvp/aguardando? battle)
    (throw (err/error :pvp_not_waiting {:phase (:phase battle)})))
  (when (pvp/expirado? battle (or agora (System/currentTimeMillis)))
    (throw (err/error :pvp_challenge_expired {})))
  (when (= player-id (pvp/jogador battle :x))
    (throw (err/error :pvp_self_accept {})))
  (pvp/assert-healthy-active! pokemon-o hp-o)
  (when-not (pvp/compativel? battle pokemon-o)
    (throw (err/error :pvp_level_incompatible
                      {:faixa (pvp/faixa-desafio battle)
                       :nivel (:nivel pokemon-o)})))
  (let [idx (long (or collection-index 0))
        b (-> battle
              (assoc-in [:jogadores :o] player-id)
              (assoc-in [:nomes :o] (or player-name player-id))
              (assoc-in [:saves :o] save-id)
              (assoc-in [:fences :o] fence-generation)
              (assoc-in [:pokemons :o] pokemon-o)
              (assoc-in [:hp :o] (long (or hp-o (:hp-atual pokemon-o) (:hp pokemon-o) 0)))
              (assoc-in [:status :o] (kw-status status-o))
              (assoc-in [:estagios :o] {})
              (assoc-in [:defendendo :o] false)
              (assoc-in [:itens-usados :o] {})
              (assoc-in [:indices-ativos :o] idx)
              (assoc-in [:participacao :o] {idx 0})
              (assoc-in [:usados :o] #{idx})
              (assoc-in [:eliminados :o] #{})
              (dissoc :desafio-expira-em)
              (assoc :phase :active :aguardando-captura? false
                     :aguardando-substituicao nil))
        ;; Intimidate ao iniciar (como wild/gym).
        x-int? (= "intimidate" (or (get-in b [:pokemons :x :habilidade])
                                   (get-in b [:pokemons :x "habilidade"])))
        o-int? (= "intimidate" (or (get-in b [:pokemons :o :habilidade])
                                   (get-in b [:pokemons :o "habilidade"])))
        reduzir (fn [bat marca]
                  (update-in bat [:pokemons marca :ataque]
                             #(Math/round (* (double (or % 0)) (/ 2.0 3.0)))))
        b (cond-> b
            (and x-int? o-int?) (-> (reduzir :x) (reduzir :o))
            (and x-int? (not o-int?)) (reduzir :o)
            (and o-int? (not x-int?)) (reduzir :x))
        vx (dmg/velocidade-efetiva (get-in b [:pokemons :x])
                                   (get-in b [:estagios :x] {})
                                   (get-in b [:status :x]))
        vo (dmg/velocidade-efetiva (get-in b [:pokemons :o])
                                   (get-in b [:estagios :o] {})
                                   (get-in b [:status :o]))
        vez (cond (> vx vo) :x
                  (> vo vx) :o
                  :else (or first-turn-tie :x))]
    (assoc b :vez vez)))

(defn- apply-item-heal-side
  [battle marca item-id]
  (let [id (shop/normalizar-item (str item-id))
        meta (shop/item id)
        fracao (:cura-hp meta)]
    (when-not fracao
      (throw (err/error :item_use_unsupported
                        {:item-id id :allowed ["pocao" "pocao-maxima"]})))
    (let [px (get-in battle [:pokemons marca])
          heal (shop/aplicar-pocao
                {:hp (:hp px)
                 :hp-atual (get-in battle [:hp marca])}
                fracao)]
      (-> battle
          (assoc-in [:hp marca] (long (:hp-depois heal)))
          (assoc :last-item {:item-id id
                             :hp-antes (:hp-antes heal)
                             :hp-depois (:hp-depois heal)
                             :hp-max (:hp-max heal)})))))

(defn- apply-item-status-cure-side
  [battle marca item-id]
  (let [id (shop/normalizar-item (str item-id))
        st (get-in battle [:status marca])
        esperado (shop/cura-status-item (when st (name st)))]
    (when-not st
      (throw (err/error :no_status_to_cure {})))
    (when-not (= id esperado)
      (throw (err/error :wrong_status_item
                        {:item-id id :status (name st) :expected esperado})))
    (-> battle
        (assoc-in [:status marca] nil)
        (mechanics/limpar-contador-veneno marca)
        (assoc :last-item {:item-id id :status-cleared (name st)}))))

(defn- clear-aguardando-substituicao
  [battle side]
  (let [w (pvp/aguardando-substituicao-de battle)]
    (cond
      (nil? w) battle
      (= w :both) (assoc battle :aguardando-substituicao
                         (if (= side :x) :o :x))
      (= w side) (assoc battle :aguardando-substituicao nil)
      :else battle)))

(defn- trocar-pokemon-pvp
  [battle side foe {:keys [collection-index pokemon agora]}]
  (let [forced? (pvp/aguardando-substituicao? battle side)
        {:keys [restantes disponivel-em]} (pvp/estado-troca battle side)
        idx (long collection-index)
        hp (:hp-atual pokemon)]
    (when (and (not forced?) (mechanics/preso? battle side))
      (throw (err/error :preso {})))
    (when-not forced?
      (when (zero? restantes)
        (throw (err/error :pvp_switch_limit {})))
      (when (< agora disponivel-em)
        (throw (err/error :pvp_switch_cooldown {:restante-ms (- disponivel-em agora)}))))
    (when (= idx (long (or (get-in battle [:indices-ativos side]) -1)))
      (throw (err/error :pvp_switch_same_pokemon {})))
    (when (contains? (pvp/eliminados battle side) idx)
      (throw (err/error :pvp_pokemon_eliminated {:collection-index idx})))
    (when-not (pvp/pode-adicionar-participante? battle side idx)
      (throw (err/error :pvp_participant_limit
                        {:max pvp/max-participantes
                         :usados (vec (pvp/usados battle side))})))
    (pvp/assert-healthy-active! pokemon hp)
    (when-not (pvp/compativel? battle pokemon)
      (throw (err/error :pvp_level_incompatible
                        {:faixa (pvp/faixa-desafio battle) :nivel (:nivel pokemon)})))
    (let [b (-> battle
                (assoc-in [:pokemons side] (slim-pokemon-for-battle pokemon))
                (assoc-in [:hp side] (long hp))
                (assoc-in [:status side] (kw-status (:status pokemon)))
                (assoc-in [:estagios side] {})
                (mechanics/limpar-volatil side)
                (assoc-in [:defendendo side] false)
                (assoc-in [:itens-usados side] {})
                (assoc-in [:indices-ativos side] idx)
                (update-in [:usados side] (fnil conj #{}) idx)
                (update-in [:participacao side idx] (fnil identity 0))
                (clear-aguardando-substituicao side)
                (assoc :last-action {:action :switch :side side
                                     :pokemon-nome (:nome pokemon)
                                     :obrigatoria? forced?})
                (update :turn-index inc))
        ent (mechanics/aplicar-entrada b side)
        b (update-in (:battle ent) [:last-action :mensagem]
                     (fn [msg]
                       (let [extra (str/join "\n" (:mensagens ent))]
                         (if (seq extra)
                           (str msg (when (and msg (seq msg)) "\n") extra)
                           msg))))
        b (if (zero? (long (get-in b [:hp side] 0)))
            (resolve-faint b side)
            b)]
      (if (pvp/aguardando-substituicao-de b)
        b
        (if forced?
          ;; Após troca obrigatória o lado que entrou age (sem consumir cota/CD).
          (assoc b :vez side)
          (-> b
              (assoc-in [:trocas side] {:usadas (inc (- pvp/max-trocas restantes))
                                       :disponivel-em (+ agora pvp/intervalo-trocas-ms)})
              (assoc :vez foe)))))))

(defn apply-pvp-turn
  "Turno humano no PvP. Sem wild-response; alterna :vez se a batalha segue."
  [battle actor-side {:keys [action move-index item-id] :as request} rng]
  (let [battle (pvp/normalize-battle-sides battle)
        actor-side (pvp/as-side actor-side)
        waiting (pvp/aguardando-substituicao-de battle)]
    (when-not (and (= :pvp (:kind battle)) (= :active (:phase battle)) (:active? battle))
      (throw (err/error :battle_not_active {:phase (:phase battle) :kind (:kind battle)})))
    (when (and waiting (not= action :switch))
      (throw (err/error :pvp_must_switch
                        {:side waiting :actor actor-side})))
    (when (and waiting
               (= action :switch)
               (not (pvp/aguardando-substituicao? battle actor-side))
               (not= waiting :both))
      (throw (err/error :pvp_waiting_replacement {:side waiting})))
    (when (and waiting (= waiting :both) (= action :switch)
               (not (#{:x :o} actor-side)))
      (throw (err/error :pvp_waiting_replacement {:side waiting})))
    (when-not (and actor-side
                   (or (pvp/aguardando-substituicao? battle actor-side)
                       (= actor-side (:vez battle))))
      (throw (err/error :pvp_out_of_turn
                        {:vez (:vez battle) :actor actor-side})))
    (let [rng (or rng {})
          foe (if (= actor-side :x) :o :x)
          defending-before? (boolean (get-in battle [:defendendo actor-side]))]
      (case action
        :switch (trocar-pokemon-pvp battle actor-side foe request)

        :defend
        (let [gate (resolve-status-gate battle actor-side rng :fecha-rodada? (= actor-side :o))]
          (if (:impedido? gate)
            (let [b (:battle gate)
                  b (cond-> b
                      (zero? (get-in b [:hp actor-side])) (resolve-faint b actor-side)
                      (and (:active? b) (pos? (get-in b [:hp actor-side] 0)))
                      (assoc :vez foe))]
              (-> b (update :turn-index inc)
                  (assoc :last-action {:action :defend :impedido? true :side actor-side})))
            (let [b (assoc-in (:battle gate) [:defendendo actor-side] true)
                  fim (aplicar-fim-de-turno b actor-side :fecha-rodada? (= actor-side :o))
                  b (:battle fim)
                  b (cond
                      (zero? (get-in b [:hp actor-side])) (resolve-faint b actor-side)
                      :else (assoc b :vez foe))]
              (-> b (update :turn-index inc)
                  (assoc :last-action {:action :defend :side actor-side})))))

        :item
        (let [id (shop/normalizar-item (str item-id))
              meta (shop/item id)
              gate (resolve-status-gate battle actor-side rng :fecha-rodada? (= actor-side :o))]
          (if (:impedido? gate)
            (let [b (:battle gate)
                  b (cond-> b
                      (zero? (get-in b [:hp actor-side])) (resolve-faint b actor-side)
                      (and (:active? b) (pos? (get-in b [:hp actor-side] 0)))
                      (assoc :vez foe))]
              (-> b (update :turn-index inc)
                  (assoc :last-action {:action :item :item-id id :impedido? true
                                       :side actor-side})))
            (let [b (cond
                      (:cura-hp meta) (apply-item-heal-side (:battle gate) actor-side id)
                      (contains? shop/status-cura id)
                      (apply-item-status-cure-side (:battle gate) actor-side id)
                      :else (throw (err/error :item_use_unsupported
                                              {:item-id id
                                               :allowed ["pocao" "pocao-maxima" "status-cura"]})))
                  last-item (:last-item b)
                  b (dissoc b :last-item)
                  fim (aplicar-fim-de-turno b actor-side :fecha-rodada? (= actor-side :o))
                  b (:battle fim)
                  b (cond
                      (zero? (get-in b [:hp actor-side])) (resolve-faint b actor-side)
                      :else (assoc b :vez foe))]
              (-> b (update :turn-index inc)
                  (assoc :last-action (merge {:action :item :item-id id :side actor-side}
                                             last-item))))))

        :attack
        (let [golpes (vec (or (get-in battle [:pokemons actor-side :golpes]) []))
              idx (long (or move-index 0))]
          (when-not (< -1 idx (count golpes))
            (throw (err/error :invalid_move_index {:idx idx :count (count golpes)})))
          (let [pre (mechanics/preparar-acao
                     battle actor-side
                     (move-effects/enriquecer (golpe-normalizado (nth golpes idx)))
                     rng)]
            (if (:pular? pre)
              (let [alt (if (seq (:alteracoes-carga pre))
                          (aplicar-alteracoes-resultado (:battle pre) actor-side (:alteracoes-carga pre))
                          {:battle (:battle pre) :aplicadas []})
                    nome (or (get-in battle [:pokemons actor-side :nome]) "Pokémon")
                    fim (aplicar-fim-de-turno (:battle alt) actor-side :fecha-rodada? (= actor-side :o))
                    b (:battle fim)
                    b (cond
                        (zero? (get-in b [:hp actor-side])) (resolve-faint b actor-side)
                        :else (assoc b :vez foe))
                    la (anexar-linhas
                        {:action :attack :move-index idx :side actor-side
                         :mensagem (:mensagem pre)
                         :golpe-nome (move-names/apresentar (:golpe pre))}
                        (concat (move-effects/linhas-estagios (:aplicadas alt) nome nome :proprio)
                                (:mensagens fim)))]
                (-> b (update :turn-index inc)
                    (assoc :last-action la)))
              (let [golpe (:golpe pre)
                    gate (resolve-status-gate (:battle pre) actor-side rng :fecha-rodada? (= actor-side :o))]
            (if (:impedido? gate)
              (let [b (:battle gate)
                    nome (or (get-in battle [:pokemons actor-side :nome]) "Pokémon")
                    msg (str "⚠️ *" nome "* " (or (:texto gate) "não conseguiu agir") "!")
                    b (cond-> b
                        (zero? (get-in b [:hp actor-side])) (resolve-faint b actor-side)
                        (and (:active? b) (pos? (get-in b [:hp actor-side] 0)))
                        (assoc :vez foe))]
                (-> b (update :turn-index inc)
                    (assoc :last-action {:action :attack :impedido? true
                                         :move-index idx :side actor-side
                                         :mensagem msg})))
              (let [classe (:classe golpe)
                    b0 (:battle gate)
                    atk (get-in b0 [:pokemons actor-side])
                    def (get-in b0 [:pokemons foe])
                    defending? (boolean (get-in b0 [:defendendo foe]))
                    status-hit (when-not (contains? #{:fisico :especial} classe)
                                 (executar-golpe-status b0 golpe actor-side foe rng))
                    hit (if (contains? #{:fisico :especial} classe)
                          (apply-offensive-hit b0 actor-side foe golpe defending? rng)
                          {:battle (:battle status-hit)
                           :aplicadas (:aplicadas status-hit)
                           :status-aplicado (:status-aplicado status-hit)
                           :cura (:cura status-hit)
                           :linhas (:linhas status-hit)
                           :troca-forcada? (:troca-forcada? status-hit)
                           :alvo-alteracao (:alvo status-hit)
                           :resolved {:dano 0 :acertou? (not (:errou? status-hit))
                                      :bloqueado? (:bloqueado? status-hit)
                                      :errou? (:errou? status-hit)
                                      :multiplicador 1 :acertos 1}})
                    nome-atk (or (:nome atk) "Pokémon")
                    nome-def (or (:nome def) "adversário")
                    la (anexar-efeitos
                        (if (contains? #{:fisico :especial} classe)
                          (dmg/last-action-ataque golpe atk def (:resolved hit) defending?
                                                  :move-index idx :side actor-side)
                          {:action :attack :move-index idx :side actor-side
                           :dano 0
                           :acertou? (not (:errou? status-hit))
                           :errou? (:errou? status-hit)
                           :golpe-slug (:slug golpe)
                           :golpe-nome (move-names/apresentar golpe)
                           :tipo-golpe (:tipo golpe)
                           :classe "status"
                           :mensagem (cond
                                       (:bloqueado? status-hit)
                                       "🛡️ Proteção bloqueou o ataque!"
                                       (:errou? status-hit)
                                       (str "📊 *" (move-names/apresentar golpe)
                                            "* de *" nome-atk "* errou!")
                                       :else
                                       (str "📊 *" (move-names/apresentar golpe)
                                            "* de *" nome-atk "*!"))})
                        nome-atk nome-def
                        (or (:alvo-alteracao hit) (:alvo golpe))
                        (:aplicadas hit)
                        (:status-aplicado hit)
                        (:cura hit)
                        :linhas (:linhas hit))
                    b (assoc-in (:battle hit) [:defendendo foe] false)
                    fim (aplicar-fim-de-turno b actor-side :fecha-rodada? (= actor-side :o))
                    b (:battle fim)
                    la (anexar-linhas la (:mensagens fim))
                    b (cond
                        (zero? (get-in b [:hp foe])) (resolve-faint b foe)
                        (zero? (get-in b [:hp actor-side])) (resolve-faint b actor-side)
                        (pvp/aguardando-substituicao-de b) b
                        :else (assoc b :vez foe))]
                (-> b
                    (update :turn-index inc)
                    (assoc :last-action la))))))))

        (throw (err/error :unknown_battle_action {:action action}))))))

(defn forfeit-pvp
  "Desistência: sem prêmio de vitória; outcome :forfeit-x|:forfeit-o."
  [battle leaver-side]
  (when-not (= :pvp (:kind battle))
    (throw (err/error :not_pvp_battle {:kind (:kind battle)})))
  (when-not (or (pvp/aguardando? battle)
                (and (= :active (:phase battle)) (:active? battle)))
    (throw (err/error :battle_not_active {:phase (:phase battle)})))
  (if (pvp/aguardando? battle)
    (assoc battle :phase :finished :outcome :cancelled :active? false
           :desafio-expira-em nil)
    (finish-pvp battle (if (= leaver-side :x) :forfeit-x :forfeit-o))))
