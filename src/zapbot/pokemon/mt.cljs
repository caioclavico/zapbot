(ns zapbot.pokemon.mt
  "Comandos de MT via JVM e persistência da confirmação pendente."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]))

(def ^:private jvm-present (js/require "../scripts/lib/pokemon-jvm-present.cjs"))
(def ^:private pokeapi (js/require "../scripts/lib/pokemon-pokeapi.cjs"))

;; MT: confirmação explícita — estado durável (sobrevive a restart do Node).
(def ^:private chave-mts-pendentes "pokemon-mt-pendentes")
(defonce ^:private mts-pendentes (atom (or (armazenamento/obter chave-mts-pendentes) {})))
(armazenamento/registrar! chave-mts-pendentes mts-pendentes)
(def ^:private segundos-mt-pendente 600)

(defn- qty-item-inventario
  "Quantidade de um item no DTO de inventário JVM (camelCase)."
  [inv item-id]
  (let [itens (vec (or (some-> inv .-itens) (some-> inv .-items) #js []))
        id (str item-id)]
    (long (or (some (fn [it]
                      (when (= id (str (or (.-itemId it) (aget it "item-id") "")))
                        (or (.-qty it) (aget it "qty") 0)))
                    itens)
              0))))

(defn- mt-chave [chat-id player-id]
  (str chat-id "|" player-id))

(defn- limpar-mt-pendente!
  [chave]
  (let [k (if (vector? chave) (apply mt-chave chave) (str chave))]
    (swap! mts-pendentes dissoc k)
    (armazenamento/salvar! chave-mts-pendentes @mts-pendentes)))

(defn- gravar-mt-pendente!
  [chave pend]
  (let [k (if (vector? chave) (apply mt-chave chave) (str chave))
        limpo (dissoc pend :timer)]
    (swap! mts-pendentes assoc k limpo)
    (armazenamento/salvar! chave-mts-pendentes @mts-pendentes)
    limpo))

(defn- ler-mt-pendente
  [chave]
  (let [k (if (vector? chave) (apply mt-chave chave) (str chave))
        pend (get @mts-pendentes k)]
    (when pend
      (let [exp (long (or (:expires-at pend) 0))
            agora (.now js/Date)]
        (if (and (pos? exp) (> agora exp))
          (do (limpar-mt-pendente! k) nil)
          pend)))))

(defn- golpe-para-jvm
  "Normaliza golpe do catálogo/JS para o payload learn-move (kebab-case)."
  [g]
  (let [m (cond
            (nil? g) {}
            (map? g) g
            :else (js->clj g :keywordize-keys true))]
    (cond-> {:slug (str (or (:slug m) ""))
             :nome-exibicao (str (or (:nome-exibicao m) (:nomeExibicao m)
                                     (:nome m) (:slug m) ""))
             :tipo (str (or (:tipo m) "normal"))
             :poder (long (or (:poder m) 0))
             :classe (str (or (:classe m) "fisico"))}
      (true? (or (:autodesmaio m) (:autodesmaio? m))) (assoc :autodesmaio true)
      (some? (:recuo m)) (assoc :recuo (:recuo m))
      (some? (:dreno m)) (assoc :dreno (:dreno m)))))

(defn- mt-data-do-recibo
  "Monta DTO de presenter a partir do summary do recibo (recuperação)."
  [receipt]
  (let [s (or (.-summary receipt) (aget receipt "summary") #js {})
        s (cond
            (string? s) (try (js/JSON.parse s) (catch :default _ #js {}))
            (map? s) (clj->js s)
            :else (or s #js {}))
        registro (or (.-registro s) (aget s "registro") (.-pokemon s)
                     (.-pokemon receipt))
        ant (or (.-golpeAnterior s) (aget s "golpe-anterior")
                (aget s "golpeAnterior") (.-golpeAnterior receipt))
        novo (or (.-golpeNovo s) (aget s "golpe-novo")
                 (aget s "golpeNovo") (.-golpeNovo receipt))]
    #js {:operationId (or (.-operationId receipt) (.-operation-id receipt))
         :duplicate true
         :reconciled true
         :pokemon registro
         :golpeAnterior ant
         :golpeNovo novo
         :itemQtyAfter (or (.-itemQtyAfter s) (aget s "item-qty-after")
                           (aget s "itemQtyAfter") 0)
         :consumeItemId (or (.-consumeItemId s) (aget s "consume-item-id")
                            (aget s "consumeItemId") "mt")
         :idPokemon (or (.-idPokemon s) (aget s "id-pokemon")
                        (aget s "idPokemon"))}))

(defn- normalizar-resultado-mt
  "Se mutateOnce reconciliou só o recibo, promove summary → DTO do presenter."
  [data]
  (if (and data
           (or (true? (.-reconciled data)) (true? (.-duplicate data)))
           (nil? (.-golpeAnterior data))
           (nil? (.-golpeNovo data))
           (some? (or (.-summary data) (aget data "summary"))))
    (mt-data-do-recibo data)
    data))

(defn- confirmar!
  [{:keys [jvm chat-id player-id prefix responder]} chave]
  (let [pend (ler-mt-pendente chave)]
    (cond
      (nil? pend)
      (responder (str "❓ Nenhuma MT pendente. Use " prefix
                         " mt usar <n> <slot>."))
      (or (nil? (:golpe pend)) (str/blank? (str (:id-pokemon pend)))
          (str/blank? (str (:operation-id pend))))
      (do (limpar-mt-pendente! chave)
          (responder "❓ Pendência de MT inválida. Monte de novo com mt usar."))
      :else
      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              save-ok? (and (= (str (.-activeSaveId ativo))
                               (str (:save-id pend)))
                            (= (long (.-fenceGeneration ativo))
                               (long (:fence-generation pend))))]
        (if-not save-ok?
          (do (limpar-mt-pendente! chave)
              (responder (str "⚠️ Save ativo mudou desde a pendência. "
                                 "Use " prefix " mt usar de novo.")))
          (-> (p/let [body (clj->js
                            {:chatId chat-id
                             :playerId player-id
                             :saveId (.-activeSaveId ativo)
                             :fenceGeneration (.-fenceGeneration ativo)
                             :operationId (:operation-id pend)
                             :collectionIndex (:idx pend)
                             :idPokemon (:id-pokemon pend)
                             :slot (:slot pend)
                             :expectedSlotSlug (:expected-slot-slug pend)
                             :catalogVersion (:catalog-version pend)
                             :golpe (:golpe pend)
                             :consumeItemId "mt"})
                      raw (.learnMove jvm body)
                      data (normalizar-resultado-mt raw)]
                (limpar-mt-pendente! chave)
                (responder (.formatMtUsed jvm-present data prefix)))
              (p/catch
               (fn [err]
                 (let [code (str (or (.-code err) ""))]
                   (cond
                     ;; Recibo já commitado — recupera sem novo sorteio/consumo.
                     (or (= "TIMEOUT" code) (= "CONNECT_TIMEOUT" code)
                         (= "OPERATION_PENDING" code))
                     (-> (p/let [rec (.getOperation jvm
                                          #js {:operationId (:operation-id pend)
                                               :chatId chat-id
                                               :playerId player-id})
                                 data (mt-data-do-recibo rec)]
                           (limpar-mt-pendente! chave)
                           (responder (.formatMtUsed jvm-present data prefix)))
                         (p/catch
                          (fn [_]
                            (responder (str "⏳ Confirmação de MT *incerta*. "
                                               "Não tente outra MT ainda.\n"
                                               "Recupere com `" prefix " recibo "
                                               (:operation-id pend) "` "
                                               "ou repita `" prefix " mt confirmar`.")))))

                     (contains? #{"POKEMON_IDENTITY_MISMATCH" "MT_SLOT_CHANGED"
                                  "MT_MOVE_NOT_ELIGIBLE" "MT_MOVE_NOT_IN_CATALOG"
                                  "MT_LEARNSET_UNAVAILABLE"
                                  "MT_CATALOG_VERSION_MISMATCH"}
                                code)
                     (do (limpar-mt-pendente! chave)
                         (throw err))

                     :else (throw err)))))))))))

(defn- preparar!
  [{:keys [jvm chat-id player-id prefix responder parse-indice-1based]} chave args-mt]
  (p/let [inv (.getInventory jvm #js {:chatId chat-id :playerId player-id})
          mt-qty (qty-item-inventario inv "mt")
          col (.getCollection jvm #js {:chatId chat-id :playerId player-id})
          items (vec (or (.-collection col) #js []))
          idx (parse-indice-1based (first args-mt) (count items))
          poke (when (some? idx) (nth items idx nil))
          slot (parse-indice-1based (second args-mt)
                                    (count (vec (or (some-> poke .-golpes)
                                                    #js []))))]
    (cond
      (zero? mt-qty)
      (responder (str "💿 Você não tem MT. Compre com `!loja comprar mt` "
                         "ou veja " prefix " mt."))

      (nil? poke)
      (responder (str "❓ Use " prefix " mt usar <número da coleção> [slot].\n"
                         "Consulte " prefix " time."))

      (str/blank? (str (or (.-idPokemon poke) (.-id poke)
                           (aget poke "id-pokemon"))))
      (responder "🚫 Este Pokémon não tem ID canônico. Contate um admin.")

      (ler-mt-pendente chave)
      (responder (str "⏳ Já há MT pendente. "
                         prefix " mt confirmar ou " prefix " mt cancelar."))

      (nil? (second args-mt))
      (try
        (let [tipos (vec (or (.-tipos poke) #js []))
              golpes (vec (or (.-golpes poke) #js []))
              cands (.listarCandidatosMt pokeapi
                       (or (.-species poke) (.-nome poke))
                       #js {:nivel (or (.-nivel poke) 1)
                            :tipos (clj->js tipos)
                            :golpesAtuais (clj->js golpes)})]
          (responder (.formatMtPreview jvm-present poke
                                          #js {:mtQty mt-qty
                                               :candidatos (alength cands)
                                               :prefix prefix
                                               :idx idx})))
        (catch :default e
          (if (= "MT_LEARNSET_UNAVAILABLE" (.-code e))
            (responder (str "🚫 Learnset de MT ainda não disponível para *"
                               (.-nome poke) "*. Espécie fora do catálogo "
                               (.mtCatalogVersion pokeapi) "."))
            (throw e))))

      (nil? slot)
      (responder (str "❓ Slot inválido. Use " prefix " mt usar "
                         (inc idx) " <1-"
                         (count (vec (or (.-golpes poke) #js []))) ">."))

      :else
      (p/let [ativo (.getActiveSave jvm
                          #js {:chatId chat-id :playerId player-id})]
        (try
          (let [tipos (vec (or (.-tipos poke) #js []))
                golpes (vec (or (.-golpes poke) #js []))
                cands (.listarCandidatosMt pokeapi
                         (or (.-species poke) (.-nome poke))
                         #js {:nivel (or (.-nivel poke) 1)
                              :tipos (clj->js tipos)
                              :golpesAtuais (clj->js golpes)
                              :slot slot})
                id-poke (str (or (.-idPokemon poke) (.-id poke)
                                 (aget poke "id-pokemon")))
                token (str (js/Date.now) "-" (rand-int 1e9))
                op-id (str "mt-confirm:" chat-id ":" player-id ":" token)
                golpe-raw (.escolherGolpeMtAleatorio pokeapi cands
                            (str chat-id "|" player-id "|" id-poke
                                 "|slot:" slot "|" token))
                golpe (when golpe-raw (golpe-para-jvm golpe-raw))
                slot-golpe (nth golpes slot nil)
                expected-slug (str (or (some-> slot-golpe .-slug)
                                       (some-> slot-golpe (aget "slug"))
                                       ""))]
            (cond
              (zero? (alength cands))
              (responder (str "🚫 Nenhum golpe compatível novo para *"
                                 (.-nome poke) "* com a MT genérica."))

              (nil? golpe)
              (responder "🚫 Falha ao sortear golpe compatível. Tente de novo.")

              :else
              (do (gravar-mt-pendente!
                   chave
                   {:token token
                    :operation-id op-id
                    :idx idx
                    :slot slot
                    :id-pokemon id-poke
                    :expected-slot-slug expected-slug
                    :catalog-version (.mtCatalogVersion pokeapi)
                    :nome-pokemon (.-nome poke)
                    :save-id (.-activeSaveId ativo)
                    :fence-generation (.-fenceGeneration ativo)
                    :golpe golpe
                    :candidatos (alength cands)
                    :expires-at (+ (.now js/Date)
                                   (* 1000 segundos-mt-pendente))})
                  (responder (.formatMtPending jvm-present poke slot
                                                  #js {:mtQty mt-qty
                                                       :prefix prefix
                                                       :candidatos
                                                       (alength cands)})))))
          (catch :default e
            (if (= "MT_LEARNSET_UNAVAILABLE" (.-code e))
              (responder (str "🚫 Learnset de MT ainda não disponível para *"
                                 (.-nome poke) "*."))
              (throw e))))))))

(defn executar
  [{:keys [jvm chat-id player-id prefix responder] :as contexto} args]
  (let [chave (mt-chave chat-id player-id)
        sub (str/lower-case (str (or (first args) "")))]
    (cond
      (or (str/blank? sub) (= "ajuda" sub) (= "help" sub) (= "?" sub))
      (p/let [inv (.getInventory jvm #js {:chatId chat-id :playerId player-id})]
        (responder (.formatMtHelp jvm-present prefix
                                     (qty-item-inventario inv "mt"))))

      (= "cancelar" sub)
      (if-let [pend (ler-mt-pendente chave)]
        (do (limpar-mt-pendente! chave)
            (responder (str "✅ MT cancelada. *"
                               (or (:nome-pokemon pend) "Pokémon")
                               "* mantém os golpes. Nenhuma MT consumida.")))
        (responder (str "❓ Nenhuma MT pendente. Use " prefix " mt usar <n> <slot>.")))

      (= "confirmar" sub) (confirmar! contexto chave)
      (= "usar" sub) (preparar! contexto chave (vec (rest args)))

      :else
      (responder (str "❓ Use " prefix " mt, " prefix " mt usar <n> [slot], "
                         prefix " mt confirmar ou " prefix " mt cancelar.")))))
