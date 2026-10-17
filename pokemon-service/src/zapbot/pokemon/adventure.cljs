(ns zapbot.pokemon.adventure
  "Fachada de incubação e recuperação; processamento limitado de prazos duráveis."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.adventure-store :as store]
            [zapbot.pokemon.ovos :as ovos]
            [zapbot.pokemon.adventure-catalog :as catalog]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.egg-rewards :as egg-rewards]))

(defonce ^:private aceitando? (atom true))
(defonce ^:private pronto? (atom true))
(defonce ^:private geracao (atom 0))
(defonce ^:private vencimentos (atom {}))
(defonce ^:private emissor (atom nil))
(defonce ^:private relogio (atom nil))
(defonce ^:private processando? (atom false))
(defn- acompanhar! [estado]
  (let [pendente? (or (seq (:hatch-notifications estado))
                      (some #(= :ready-to-hatch (:state %)) (get-in estado [:activity-state :eggs])))
        prazo (when-not pendente?
                (first (sort (keep #(when (= :incubating (:state %)) (:due-at %))
                                   (get-in estado [:activity-state :eggs])))))
        id (:account-id estado)]
    (if (or pendente? prazo)
      (swap! vencimentos assoc id {:cid (:chat-id estado) :pid (:player-id estado)
                                  :due (if pendente? 0 prazo)})
      (swap! vencimentos dissoc id))
    estado))
(defn disponivel? [] @pronto?)
(defn preparar-inicializacao! []
  (swap! geracao inc)
  (reset! vencimentos {})
  (armazenamento/configurar-leitor-aventura-elegibilidade!
   (fn [cid pid]
     (if-not config/adventure-enabled?
       (p/resolved nil)
       (p/then (store/resolver! cid pid false (.now js/Date))
               (fn [estado]
                 (if-not estado
                   nil
                   (let [ovos-ativos (or (get-in estado [:activity-state :eggs]) [])
                         eggs (mapv (fn [o]
                                      {"egg-id" (or (:egg-id o) (:eggId o))
                                       "state" (name (or (:state o) ""))})
                                    ovos-ativos)
                         entregas (into []
                                        (keep (fn [o]
                                                (when (= "delivery_pending"
                                                         (name (or (:state o) "")))
                                                  (or (:egg-id o) (:eggId o))))
                                              ovos-ativos))]
                     (clj->js {:eggs eggs :pendingDeliveries entregas}))))))))
  (when config/adventure-enabled? (reset! pronto? false) (reset! aceitando? false)))

(defn parar! []
  (swap! geracao inc) (reset! aceitando? false) (reset! emissor nil)
  (when-let [timer @relogio] (js/clearInterval timer))
  (reset! relogio nil))

(defn- texto! [texto]
  (when-not (and (string? texto) (not (str/blank? texto)) (<= (count texto) 128))
    (ovos/erro! :invalid-id "Identificador interno inválido.")))

(defn- executar! [cid pid acao & [recuperacao?]]
  (texto! cid) (texto! pid)
  (when-not config/adventure-enabled? (ovos/erro! :disabled "Adventure Sync desabilitado."))
  (when-not (and @aceitando? @pronto?) (ovos/erro! :not-ready "Adventure Sync inicializando ou encerrando."))
  (armazenamento/exigir-escrita!)
  ;; Comando novo captura o fence do save atual (selo na incubação).
  ;; Recuperação/tick não recaptura; entrega reinstala o selo persistido.
  (core/enfileirar-operacao-interna! cid acao recuperacao? (not recuperacao?) pid))

(defn- codigo-erro [erro]
  (let [bruto (or (some-> erro .-code)
                  (:code (ex-data erro))
                  (some-> erro .-cause .-code)
                  (:code (ex-data (.-cause erro)))
                  (when (string? (some-> erro .-message))
                    (let [msg (.-message erro)]
                      (cond
                        (str/includes? msg "canonical_trainer_fence_conflict") "canonical_trainer_fence_conflict"
                        (str/includes? msg "save_bound_fence_conflict") "save_bound_fence_conflict"
                        (str/includes? msg "adventure_hatch_resume_save_mismatch") "adventure_hatch_resume_save_mismatch"
                        (str/includes? msg "canonical_trainer_transition_reserved") "canonical_trainer_transition_reserved"
                        :else nil))))]
    (when bruto
      (-> (if (keyword? bruto) (name bruto) (str bruto))
          (str/replace "-" "_")))))

(defn- fence-entrega-adiavel? [codigo]
  (contains? #{"canonical_trainer_fence_conflict" "save_bound_fence_conflict"
               "save_bound_fence_missing" "canonical_trainer_transition_reserved"
               "adventure_hatch_resume_save_mismatch" "adventure_hatch_resume_unavailable"
               :canonical_trainer_fence_conflict :save_bound_fence_conflict
               :save-bound-fence-missing :canonical-trainer-transition-reserved
               :adventure-hatch-resume-unavailable :adventure-hatch-origin-unproven}
             codigo))

(defn- entregar-via-treino! [cid pid hatch-id registro]
  (treinador/receber-nascimento! cid pid hatch-id registro))

(defn- marcar-entregue! [cid pid egg-id]
  (p/then (armazenamento/marcar-nascimento-entregue! cid pid egg-id)
          (fn [_] :entregue)))

(defn- retomar-se-origem-ativa! [cid pid egg-id hatch-id selo entregar-fn]
  (p/let [ativa? (armazenamento/origem-save-ativa? cid pid selo)]
    (if-not ativa?
      (do (js/console.warn "[Ovos] entrega adiada: save de origem inativo ou reservado.")
          :adiado)
      (-> (armazenamento/recuperar-nascimento! cid pid egg-id hatch-id entregar-fn)
          (p/then (fn [_] :entregue))
          (p/catch
           (fn [erro-retoma]
             (if (fence-entrega-adiavel? (codigo-erro erro-retoma))
               (do (js/console.warn "[Ovos] retomada adiada:" (codigo-erro erro-retoma))
                   :adiado)
               (throw erro-retoma))))))))

(defn- entregar-com-fence-incubacao! [cid pid egg-id hatch-id pokemon-id registro selo]
  (let [entregar-fn (fn [_] (entregar-via-treino! cid pid hatch-id registro))]
    (p/let [_ (armazenamento/registrar-nascimento-pendente!
               cid pid egg-id hatch-id pokemon-id selo)]
      (-> (armazenamento/executar-com-selo-save!
           cid pid selo #(entregar-via-treino! cid pid hatch-id registro))
          (p/then (fn [_] (marcar-entregue! cid pid egg-id)))
          (p/catch
           (fn [erro]
             (cond
               (treinador/recibo-nascimento cid pid hatch-id)
               (marcar-entregue! cid pid egg-id)

               (not (fence-entrega-adiavel? (codigo-erro erro)))
               (throw erro)

               :else
               (retomar-se-origem-ativa! cid pid egg-id hatch-id selo entregar-fn))))))))

(defn- entregar-nascimento-protegido!
  "Entrega com fence de incubação; se a geração mudou e o save de origem
  voltou a estar ativo, retoma via AdventureHatchResume sem reescrever o selo.
  Retorna :entregue | :adiado. Não entrega o Pokémon de A enquanto B está ativo."
  [cid pid egg-id ovo]
  (let [resultado (:resultado ovo)
        hatch-id (:hatch-id resultado)
        pokemon-id (:pokemon-id resultado)
        registro (:registro resultado)
        selo (or (:save-fence resultado) (:save-fence ovo))]
    (cond
      (treinador/recibo-nascimento cid pid hatch-id)
      (marcar-entregue! cid pid egg-id)

      (not (core/pode-receber-nascimento? cid pid))
      (p/resolved :adiado)

      (not config/canonical-trainer-enabled?)
      (p/then (entregar-via-treino! cid pid hatch-id registro) (fn [_] :entregue))

      (nil? selo)
      ;; Sem fence persistido não há prova de origem; bloqueia em vez de
      ;; recapturar a geração vigente.
      (do (js/console.warn "[Ovos] nascimento sem save-fence; entrega bloqueada no modo canônico.")
          (p/resolved :adiado))

      :else
      (entregar-com-fence-incubacao! cid pid egg-id hatch-id pokemon-id registro selo))))

(defn- recuperar-estado! [estado agora]
  (p/let [estado (store/ler! (:account-id estado))]
    (-> (reduce
          (fn [anterior egg-id]
            (p/let [atual anterior
                    ovo (ovos/ovo atual egg-id)]
              (if-not (contains? #{"ready_to_hatch" "delivery_pending"} (:state ovo))
                atual
                (let [preparado (if (:resultado ovo)
                                  atual
                                  (let [nome (catalog/sortear (:pool ovo) (:seed ovo))
                                        resultado {:hatch-id (store/uuid)
                                                   :pokemon-id (store/uuid)
                                                   :species nome
                                                   :save-fence (:save-fence ovo)}
                                        registro (core/preparar-registro-nascimento
                                                   (catalog/especie nome) (:pokemon-id resultado))]
                                    (assoc-in (ovos/preparar-nascimento atual egg-id
                                                (assoc resultado :registro registro))
                                              [:version] (:version atual))))]
                  (p/let [fixado (store/alterar!
                                  (:account-id atual)
                                  #(if (:resultado (ovos/ovo % egg-id))
                                     %
                                     (ovos/preparar-nascimento % egg-id
                                                               (:resultado (ovos/ovo preparado egg-id))))
                                  agora)
                          ovo (ovos/ovo fixado egg-id)
                          resultado (:resultado ovo)]
                    (if-not (or (treinador/recibo-nascimento (:chat-id fixado) (:player-id fixado)
                                                             (:hatch-id resultado))
                                (core/pode-receber-nascimento? (:chat-id fixado) (:player-id fixado)))
                      fixado
                      ;; Pokémon e recibo compartilham o CAS da partição treinador.
                      ;; Fence de incubação primeiro; retomada só se o save de origem
                      ;; voltar a ficar ativo. Recibo impede nova entrega.
                      (p/let [status (entregar-nascimento-protegido!
                                      (:chat-id fixado) (:player-id fixado) egg-id ovo)]
                        (if-not (= :entregue status)
                          fixado
                          (store/alterar!
                           (:account-id fixado)
                           #(if (= "delivery_pending" (:state (ovos/ovo % egg-id)))
                              (ovos/concluir-nascimento % egg-id agora)
                              %)
                           agora)))))))))
          (p/resolved estado)
          (map :egg-id (:eggs estado)))
        (p/then #(store/projetar! % agora)))))

(defn consultar! [cid pid]
  (executar! cid pid
    #(p/let [estado (store/resolver! cid pid false (.now js/Date))]
       (when estado (ovos/consulta estado)))))

;; Estado autoritativo da mecânica nova. O campo é aditivo no payload legado;
;; nenhum campo de distância é reescrito.
(defn- garantir-atividade! [estado agora]
  (if (:activity-state estado)
    (p/resolved estado)
    (store/alterar! (:account-id estado)
                     (fn [a] (if (:activity-state a) a
                       (assoc a :activity-state (ovos/estado-atividade-inicial)
                                :activity-schema-version ovos/atividade-schema-version)))
                     agora)))

(defn- recuperar-atividade!
  "Reconcilia prazos, fixa resultado por CAS, entrega com recibo da coleção e
   conclui o ovo. Todas as etapas podem ser repetidas após resultado incerto."
  [estado agora]
  (acompanhar! estado)
  (p/let [estado (store/alterar! (:account-id estado)
                    (fn [atual] (update atual :activity-state ovos/avancar-tempo-atividade agora))
                    agora)]
    (p/then
     (reduce
      (fn [anterior egg-id]
        (p/let [atual anterior
                ;; Antes de tocar a coleção, os IDs e o registro ficam duráveis.
                fixado (store/alterar!
                        (:account-id atual)
                        (fn [a]
                          (let [ovo (ovos/ovo (:activity-state a) egg-id)]
                            (if (or (:resultado ovo) (not= :ready-to-hatch (:state ovo)))
                              a
                              (let [nome (or (:species ovo)
                                             (ovos/erro! :species-unresolved
                                                         "Ovo sem espécie fixada; preservar para recuperação explícita."))
                                    id (store/uuid)
                                    resultado {:hatch-id (store/uuid)
                                               :pokemon-id id
                                               :species nome
                                               :save-fence (:save-fence ovo)
                                               :registro (core/preparar-registro-nascimento
                                                          (catalog/especie nome) id)}]
                                (update a :activity-state ovos/atualizar-ovo egg-id
                                        (fn [o] (assoc o :resultado resultado)))))))
                        agora)
                ovo (ovos/ovo (:activity-state fixado) egg-id)
                resultado (:resultado ovo)]
          (if (or (not= :ready-to-hatch (:state ovo)) (nil? resultado))
            fixado
            (if-not (or (treinador/recibo-nascimento (:chat-id fixado) (:player-id fixado)
                                                     (:hatch-id resultado))
                        (core/pode-receber-nascimento? (:chat-id fixado) (:player-id fixado)))
              fixado
              (p/let [status (entregar-nascimento-protegido!
                              (:chat-id fixado) (:player-id fixado) egg-id
                              (assoc ovo :resultado resultado))]
                (if-not (= :entregue status)
                  fixado
                  (store/alterar!
                   (:account-id fixado)
                   (fn [a]
                     (if (= :hatched (:state (ovos/ovo (:activity-state a) egg-id)))
                       a
                       (-> a
                           (update :activity-state ovos/concluir-atividade egg-id agora)
                           (update :history-pending conj
                                   (merge resultado {:egg-id egg-id
                                                     :category (:category ovo)
                                                     :seq (:next-hatch-seq a)
                                                     :hatched-at agora}))
                           ;; Mesmo CAS que confirma a conclusão, somente após
                           ;; entrega+recibo duráveis na coleção.
                           (update :hatch-notifications (fnil conj [])
                                   {:hatch-id (:hatch-id resultado)
                                    :species (:species resultado)
                                    :category (:category ovo)
                                    :at agora})
                           (update :next-hatch-seq inc))))
                   agora)))))))
      (p/resolved estado)
      (map :egg-id (get-in estado [:activity-state :eggs])))
     (fn [a] (p/then (store/projetar! a agora) acompanhar!)))))

(defn- enviar-notificacoes! [estado]
  (reduce
   (fn [anterior aviso]
     (p/let [atual anterior]
       (if-let [enviar @emissor]
         (p/let [_ (enviar (:chat-id atual)
                          {:event-id (str "egg:" (:hatch-id aviso))
                           :texto (str "🎉 SEU OVO CHOCOU!\n\n🥚 Ovo " (name (:category aviso))
                                       "\n👤 Treinador: @" (first (str/split (:player-id atual) #"@"))
                                       "\n✨ Nasceu " (:species aviso) "!\n⭐ Nível 1\n❤️ HP cheio"
                                       "\n\nO Pokémon foi adicionado à sua coleção!")
                           :mentions [(:player-id atual)]})]
           (store/alterar! (:account-id atual)
             #(update % :hatch-notifications
                      (fn [xs] (vec (remove (fn [x] (= (:hatch-id x) (:hatch-id aviso))) xs))))
             (.now js/Date)))
         atual)))
   (p/resolved estado) (:hatch-notifications estado)))

(defn processar-vencidos!
  "Até dez contas por ciclo; sem SELECT global. O índice é reconstruído no boot."
  []
  (when (and config/adventure-enabled? (not config/read-only?) @aceitando? @pronto?
             (compare-and-set! processando? false true))
    (let [ticket @geracao agora (.now js/Date)
          contas (take 10 (sort-by (comp :due val) (filter #(<= (:due (val %)) agora) @vencimentos)))]
      (-> (reduce
           (fn [anterior [id {:keys [cid pid]}]]
             (p/let [_ anterior]
               (when (= ticket @geracao)
                 (-> (core/enfileirar-operacao-interna! cid
                   (fn []
                     (p/let [_ (armazenamento/recuperar-treinador! cid)
                             estado (store/resolver! cid pid false (.now js/Date))
                             recuperado (recuperar-atividade! estado (.now js/Date))
                             enviado (enviar-notificacoes! recuperado)]
                       (acompanhar! enviado)
                       (when (get @vencimentos id)
                         (swap! vencimentos update-in [id :due] #(max % (+ (.now js/Date) 60000))))))
                   true false pid)
                     (p/catch (fn [_]
                                (when (get @vencimentos id)
                                  (swap! vencimentos assoc-in [id :due] (+ (.now js/Date) 60000)))
                                (js/console.error "[Ovos] Recuperação pendente; nova tentativa em próximo ciclo.")))))))
           (p/resolved nil) contas)
          (p/finally #(reset! processando? false))))))

(defn iniciar-notificacoes! [enviar]
  (when (and config/adventure-enabled? (not config/read-only?) (nil? @relogio))
    (reset! emissor enviar)
    (let [tick #(-> (p/resolved (processar-vencidos!))
                   (p/catch (fn [_] (js/console.error "[Ovos] Falha na recuperação/notificação; pendência preservada."))))
          timer (js/setInterval tick 60000)]
      (.unref timer)
      (reset! relogio timer)
      (tick))))

(defn- garantir-inicial! [estado agora]
  (p/let [r (store/operar! (:account-id estado) "activity-welcome-v1"
              (store/fingerprint ["activity-welcome-v1"]) "activity-welcome" (store/dia agora) agora
              (fn [a]
                (let [activity (:activity-state a)
                      legado? (some (fn [r] (= "activity-grant" (:kind r))) (:receipts a))]
                  (if (or (:welcome-granted? activity) legado?)
                    [(assoc-in a [:activity-state :welcome-granted?] true) {:already-granted true}]
                    (let [ovo (assoc (ovos/novo-ovo-atividade (store/uuid) :comum agora)
                                     :species (catalog/sortear (catalog/validar! catalog/distribuicao 2) (catalog/seed)))]
                      [(assoc a :activity-state (assoc (ovos/conceder-ovo-atividade activity ovo)
                                                       :welcome-granted? true))
                       {:egg-id (:egg-id ovo)}])))))]
    (:state r)))

(defn sorteios-evento [account-id event-id]
  (let [hash (store/fingerprint [account-id event-id])]
    (mapv (fn [inicio] (* 100 (/ (js/parseInt (subs hash inicio (+ inicio 8)) 16) 4294967296))) [0 8])))

(defn- aplicar-evento! [cid pid evento]
  (let [event-id (get evento "event-id") tipo (keyword (get evento "kind"))
        ocorrido-em (get evento "at") agora (.now js/Date)]
    (texto! event-id)
    (when-not (and (contains? egg-rewards/pontos tipo)
                   (js/Number.isSafeInteger ocorrido-em) (<= 0 ocorrido-em agora))
      (ovos/erro! :invalid-game-event "Evento do jogo inválido ou futuro."))
    (p/let [estado (store/resolver! cid pid true agora)
            estado (garantir-atividade! estado agora)
            estado (garantir-inicial! estado agora)
            categoria (egg-rewards/categoria tipo (sorteios-evento (:account-id estado) event-id)
                                            config/egg-probabilities)
            r (store/operar! (:account-id estado) event-id
                (store/fingerprint ["game-event" tipo ocorrido-em]) "game-event" (store/dia ocorrido-em) agora
                (fn [a]
                  (let [activity (ovos/aplicar-pontos-atividade (:activity-state a) event-id
                                    (get egg-rewards/pontos tipo) ocorrido-em)
                        cheio? (>= (count (remove (fn [o] (= :hatched (:state o))) (:eggs activity))) ovos/limite-inventario)
                        ovo (when (and categoria (not cheio?))
                              (let [km (get {:comum 2 :raro 5 :epico 10} categoria)]
                                (assoc (ovos/novo-ovo-atividade (store/uuid) categoria agora)
                                       :species (catalog/sortear (catalog/validar! catalog/distribuicao km)
                                                  (store/fingerprint [(:account-id a) event-id "species"])))))
                        novo (if ovo (ovos/conceder-ovo-atividade activity ovo) activity)]
                    [(assoc a :activity-state novo)
                     {:points (get egg-rewards/pontos tipo) :category categoria
                      :egg-id (:egg-id ovo) :inventory-full (boolean (and categoria cheio?))}])))]
      (recuperar-atividade! (:state r) agora))))

(defn drenar-eventos!
  "Chamado dentro da fila já adquirida pelo core; não enfileira recursivamente.
   O diário da fonte só é removido depois do CAS do activity-state e da entrega."
  [cid]
  (when config/adventure-enabled?
    (p/let [_ (armazenamento/aguardar-todas!)]
      (reduce
       (fn [anterior [modulo pid evento]]
         (p/let [_ anterior
                 _ (aplicar-evento! cid pid evento)]
           (if (= modulo :treinador)
             (treinador/confirmar-evento-atividade! cid pid (get evento "event-id"))
             (loja/confirmar-evento-atividade! cid pid (get evento "event-id")))))
       (p/resolved nil)
       (sort-by (fn [[modulo pid e]] [(get e "at") pid (get e "event-id") (name modulo)])
                (concat (map (fn [[pid e]] [:treinador pid e]) (treinador/eventos-atividade cid))
                        (map (fn [[pid e]] [:loja pid e]) (loja/eventos-atividade cid))))))))

(defn conceder-incubadora! [cid pid event-id tipo]
  (texto! event-id)
  (when-not (contains? #{:extra :especial} tipo)
    (ovos/erro! :invalid-incubator "Somente incubadoras extra e especial podem ser concedidas."))
  (executar! cid pid
    (fn []
      (p/let [agora (.now js/Date) estado (store/resolver! cid pid true agora)
              estado (garantir-atividade! estado agora)
              r (store/operar! (:account-id estado) event-id
                  (store/fingerprint ["incubator-grant" tipo]) "incubator-grant" (store/dia agora) agora
                  (fn [a] (let [id (store/uuid)]
                            [(update a :activity-state ovos/adicionar-incubadora-atividade
                                     id tipo 3 (if (= tipo :especial) 25 0))
                             {:incubator-id id}])))]
        (:activity-state (:state r))))))

(defn atividade-consultar! [cid pid]
  ;; Consulta reconcilia entregas adiadas; não recaptura o fence vigente —
  ;; selo de incubação / resume instalam o contexto correto.
  (executar! cid pid
    #(p/let [estado (store/resolver! cid pid false (.now js/Date))]
       (when estado
         (p/let [atual (garantir-atividade! estado (.now js/Date))
                 recuperado (recuperar-atividade! atual (.now js/Date))]
           (:activity-state recuperado))))
    true))

(defn atividade-ativar! [cid pid event-id]
  (texto! event-id)
  (executar! cid pid
    (fn []
      (p/let [agora (.now js/Date)
              estado (store/resolver! cid pid true agora)
              estado (garantir-atividade! estado agora)
              estado (garantir-inicial! estado agora)
              recuperado (recuperar-atividade! estado agora)]
        (:activity-state recuperado)))))

(defn atividade-incubar!
  ([cid pid event-id egg-id] (atividade-incubar! cid pid event-id egg-id nil))
  ([cid pid event-id egg-id incubator-id]
   (texto! event-id) (texto! egg-id)
   (executar! cid pid
     (fn []
       (p/let [agora (.now js/Date) estado (store/resolver! cid pid false agora)
               _ (when-not estado (ovos/erro! :account-not-found "Conta sem ovos."))
               estado (garantir-atividade! estado agora)
               estado (recuperar-atividade! estado agora)
               ;; Mesmo se a resposta do CAS de início se perder, esta conta
               ;; será relida pelo worker; o prazo persistido continua autoridade.
               _ (swap! vencimentos assoc (:account-id estado) {:cid cid :pid pid :due agora})
               selo (armazenamento/selo-save-atual pid)
               resultado (store/operar! (:account-id estado) event-id
                           (store/fingerprint ["activity-incubate" egg-id incubator-id]) "activity-incubate"
                           (store/dia agora) agora
                           (fn [atual] (let [a (:activity-state atual)
                                             id (or incubator-id
                                                    (:incubator-id (first (filter (fn [i] (nil? (:active-egg-id i))) (:incubators a)))))
                                             incubado (ovos/incubar-atividade a egg-id id agora)]
                                         [(assoc atual :activity-state
                                                 (if selo
                                                   (ovos/atualizar-ovo incubado egg-id (fn [ovo] (assoc ovo :save-fence selo)))
                                                   incubado))
                                          {:egg-id egg-id :incubator-id id}])))
               recuperado (recuperar-atividade! (:state resultado) agora)]
         (:activity-state recuperado))))))

(defn atividade-pontos! [cid pid event-id pontos]
  (texto! event-id)
  (executar! cid pid
    #(p/let [agora (.now js/Date) estado (store/resolver! cid pid false agora)
             _ (when-not estado (ovos/erro! :account-not-found "Conta sem ovos."))
             estado (garantir-atividade! estado agora)
             resultado (store/operar! (:account-id estado) event-id
                         (store/fingerprint ["activity-points" pontos]) "activity-points"
                         (store/dia agora) agora
                         (fn [atual] [(assoc atual :activity-state
                                                (ovos/aplicar-pontos-atividade (:activity-state atual) event-id pontos))
                                      {:points pontos}]))]
       (p/let [recuperado (recuperar-atividade! (:state resultado) agora)]
         (:activity-state recuperado)))))

(defn atividade-avancar-tempo! [cid pid agora]
  ;; Avanço de prazo é recuperação: entrega com fence persistido/resume, sem
  ;; herdar um writer context novo que conflite com o selo de incubação.
  (executar! cid pid
    #(p/let [estado (store/resolver! cid pid false agora)
             _ (when-not estado (ovos/erro! :account-not-found "Conta sem ovos."))
             estado (garantir-atividade! estado agora)
             novo (store/alterar! (:account-id estado)
                                  (fn [a] (update a :activity-state ovos/avancar-tempo-atividade agora))
                                  agora)]
       (p/let [recuperado (recuperar-atividade! novo agora)]
         (:activity-state recuperado)))
    true))

(defn criar-ovo!
  ([cid pid event-id categoria] (criar-ovo! cid pid event-id categoria catalog/distribuicao))
  ([cid pid event-id categoria distribuicao]
   (texto! event-id)
   (executar! cid pid
     #(let [agora (.now js/Date) pool (catalog/validar! distribuicao categoria)
            ovo (ovos/novo-ovo (store/uuid) categoria (:version distribuicao) pool (catalog/seed) agora)]
        (p/let [estado (store/resolver! cid pid true agora)
                estado (recuperar-estado! estado agora)
                resultado (store/operar! (:account-id estado) event-id
                            (store/fingerprint ["grant" categoria]) "grant" (store/dia agora) agora
                            (fn [atual] [(ovos/conceder atual ovo) {:egg-id (:egg-id ovo)}]))
                estado (store/projetar! (:state resultado) agora)]
         {:operation (get-in resultado [:receipt :result]) :state (ovos/consulta estado)})))))

(defn categoria-recompensa
  "Converte uma amostra inteira [0,99] em categoria legada do catálogo.
   Receber a amostra como argumento mantém a política determinística e fácil
   de testar; os hooks usam rand-int somente na borda."
  [amostra probabilidades]
  (let [n (max 0 (min 99 (int amostra)))]
    (cond
      (< n (:epico probabilidades 0)) 10
      (< n (+ (:epico probabilidades 0) (:raro probabilidades 0))) 5
      (< n (+ (:epico probabilidades 0) (:raro probabilidades 0)
              (:comum probabilidades 0))) 2
      :else nil)))

(defn- categoria-evento [probabilidades]
  (categoria-recompensa (rand-int 100) probabilidades))

(defn registrar-captura! [cid pid captura-id]
  (when config/adventure-enabled?
    (executar! cid pid
      (fn [] (aplicar-evento! cid pid (egg-rewards/evento (str "capture:" captura-id) :capture (.now js/Date)))))))

(defn registrar-vitoria! [cid pid partida-id]
  (when config/adventure-enabled?
    (executar! cid pid
      (fn [] (aplicar-evento! cid pid (egg-rewards/evento (str "pvp-win:" partida-id) :pvp-win (.now js/Date)))))))

(defn ativar! [cid pid] (criar-ovo! cid pid "welcome" 2))

(defn incubar! [cid pid event-id egg-id]
  (texto! event-id) (texto! egg-id)
  (executar! cid pid
    #(let [agora (.now js/Date)]
       (p/let [estado (store/resolver! cid pid false agora)
               _ (when-not estado (ovos/erro! :egg-not-found "Conta sem ovos."))
               estado (recuperar-estado! estado agora)
               selo (armazenamento/selo-save-atual pid)
               resultado (store/operar! (:account-id estado) event-id
                           (store/fingerprint ["incubate" egg-id]) "incubate" (store/dia agora) agora
                           (fn [atual] [(let [incubado (ovos/incubar atual egg-id agora)]
                                          (if selo
                                            (ovos/atualizar-ovo incubado egg-id (fn [ovo] (assoc ovo :save-fence selo)))
                                            incubado))
                                        {:egg-id egg-id}]))
               estado (store/projetar! (:state resultado) agora)]
         {:operation (get-in resultado [:receipt :result]) :state (ovos/consulta estado)}))))

(defn aplicar-distancia! [cid pid {:keys [event-id from-ms to-ms distance-mm] :as evento}]
  (texto! event-id)
  (doseq [valor [from-ms to-ms distance-mm]] (ovos/inteiro! valor))
  (executar! cid pid
    #(let [agora (.now js/Date)]
       (p/let [estado (store/resolver! cid pid false agora)
               _ (when-not estado (ovos/erro! :account-not-found "Conta ainda não ativada."))
               estado (recuperar-estado! estado agora)
               resultado (store/operar! (:account-id estado) event-id
                           (store/fingerprint ["distance" from-ms to-ms distance-mm]) "distance"
                           (store/dia from-ms) agora
                           (fn [atual] (ovos/aplicar-distancia atual evento agora)))
               estado (recuperar-estado! (:state resultado) agora)]
         {:operation (get-in resultado [:receipt :result]) :state (ovos/consulta estado)}))))

(defn recuperar! [cid pid]
  (executar! cid pid
    #(p/let [_ (armazenamento/recuperar-treinador! cid)
             estado (store/resolver! cid pid false (.now js/Date))]
       (when estado
         (if (:activity-state estado)
           (p/let [recuperado (recuperar-atividade! estado (.now js/Date))]
             (:activity-state recuperado))
           (p/then (recuperar-estado! estado (.now js/Date)) ovos/consulta)))) true))

(defn historico! [cid pid month & [cursor]]
  (when-not (and (string? month) (re-matches #"\d{4}-(0[1-9]|1[0-2])" month))
    (ovos/erro! :invalid-month "Mês deve usar YYYY-MM."))
  (when cursor (ovos/inteiro! cursor))
  (executar! cid pid
    #(p/let [estado (store/resolver! cid pid false (.now js/Date))]
       (if estado (store/historico! (:account-id estado) month (or cursor js/Number.MAX_SAFE_INTEGER)) []))))

(defn inicializar! []
  (let [ticket @geracao
        verificar #(when-not (= ticket @geracao)
                     (ovos/erro! :stopping "Inicialização cancelada pelo encerramento."))]
  (cond
    (not config/adventure-enabled?)
    (do (reset! pronto? true) (reset! aceitando? true) (p/resolved nil))
    config/read-only?
    (p/let [_ (armazenamento/validar-adventure-schema!)]
      (verificar)
      (reset! pronto? true) (reset! aceitando? false) nil)
    :else
    (letfn [(pagina! [cursor]
              (verificar)
              (p/let [{:keys [states next-page]} (store/pagina-estados! cursor)
                      _ (reduce
                          (fn [anterior estado]
                            (p/let [_ anterior]
                              (verificar)
                              (when (or (:activity-state estado) (seq (:history-pending estado))
                                        (some #(not (:projected? %)) (:receipts estado))
                                        (some #(contains? #{"ready_to_hatch" "delivery_pending"} (:state %)) (:eggs estado)))
                                (core/enfileirar-operacao-interna!
                                  (:chat-id estado)
                                  #(p/let [_ (armazenamento/recuperar-treinador! (:chat-id estado))]
                                     (p/let [legado (recuperar-estado! estado (.now js/Date))]
                                       (if (:activity-state legado)
                                         (recuperar-atividade! legado (.now js/Date)) legado))) true))))
                          (p/resolved nil) states)]
                (when next-page (pagina! next-page))))]
      (p/let [_ (armazenamento/validar-adventure-schema!)
              _ (pagina! nil)
              _ (reduce (fn [anterior cid]
                          (p/let [_ anterior]
                            (verificar)
                            (core/enfileirar-operacao-interna! cid
                              (fn [] (drenar-eventos! cid)) true true)))
                        (p/resolved nil)
                        (sort (set (concat (keys @treinador/contas) (keys @loja/contas)))))]
        (verificar)
        (reset! pronto? true) (reset! aceitando? true) nil)))))
