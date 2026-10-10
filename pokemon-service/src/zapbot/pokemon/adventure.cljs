(ns zapbot.pokemon.adventure
  "Fachada interna de domínio. Nenhuma rota HTTP, comando ou scheduler."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.adventure-store :as store]
            [zapbot.pokemon.ovos :as ovos]
            [zapbot.pokemon.adventure-catalog :as catalog]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.treinador :as treinador]))

(defonce ^:private aceitando? (atom true))
(defonce ^:private pronto? (atom true))
(defonce ^:private geracao (atom 0))
(defn disponivel? [] @pronto?)
(defn preparar-inicializacao! []
  (swap! geracao inc)
  (when config/adventure-enabled? (reset! pronto? false) (reset! aceitando? false)))
(defn parar! [] (swap! geracao inc) (reset! aceitando? false))

(defn- texto! [texto]
  (when-not (and (string? texto) (not (str/blank? texto)) (<= (count texto) 128))
    (ovos/erro! :invalid-id "Identificador interno inválido.")))

(defn- executar! [cid pid acao & [recuperacao?]]
  (texto! cid) (texto! pid)
  (when-not config/adventure-enabled? (ovos/erro! :disabled "Adventure Sync desabilitado."))
  (when-not (and @aceitando? @pronto?) (ovos/erro! :not-ready "Adventure Sync inicializando ou encerrando."))
  (armazenamento/exigir-escrita!)
  (core/enfileirar-operacao-interna! cid acao recuperacao?))

(defn- recuperar-estado! [estado agora]
  (p/let [estado (store/ler! (:account-id estado))]
    (-> (reduce
          (fn [anterior egg-id]
            (p/let [atual anterior ovo (ovos/ovo atual egg-id)]
              (if-not (contains? #{"ready_to_hatch" "delivery_pending"} (:state ovo)) atual
                (let [preparado (if (:resultado ovo) atual
                                   (let [nome (catalog/sortear (:pool ovo) (:seed ovo))
                                         resultado {:hatch-id (store/uuid) :pokemon-id (store/uuid) :species nome}
                                         registro (core/preparar-registro-nascimento
                                                    (catalog/especie nome) (:pokemon-id resultado))]
                                     (assoc-in (ovos/preparar-nascimento atual egg-id
                                                 (assoc resultado :registro registro)) [:version] (:version atual))))]
                  (p/let [fixado (store/alterar! (:account-id atual)
                                  #(if (:resultado (ovos/ovo % egg-id)) %
                                       (ovos/preparar-nascimento % egg-id (:resultado (ovos/ovo preparado egg-id)))) agora)
                          ovo (ovos/ovo fixado egg-id)
                          resultado (:resultado ovo)]
                    (if-not (or (treinador/recibo-nascimento (:chat-id fixado) (:player-id fixado) (:hatch-id resultado))
                                (core/pode-receber-nascimento? (:chat-id fixado) (:player-id fixado)))
                      fixado
                      (p/let [_ (treinador/receber-nascimento! (:chat-id fixado) (:player-id fixado)
                                  (:hatch-id resultado) (:registro resultado))]
                        (store/alterar! (:account-id fixado)
                          #(if (= "delivery_pending" (:state (ovos/ovo % egg-id)))
                             (ovos/concluir-nascimento % egg-id agora) %) agora))))))))
          (p/resolved estado)
          (map :egg-id (:eggs estado)))
        (p/then #(store/projetar! % agora)))))

(defn consultar! [cid pid]
  (executar! cid pid
    #(p/let [estado (store/resolver! cid pid false (.now js/Date))]
       (when estado (ovos/consulta estado)))))

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

(defn ativar! [cid pid] (criar-ovo! cid pid "welcome" 2))

(defn incubar! [cid pid event-id egg-id]
  (texto! event-id) (texto! egg-id)
  (executar! cid pid
    #(let [agora (.now js/Date)]
       (p/let [estado (store/resolver! cid pid false agora)
               _ (when-not estado (ovos/erro! :egg-not-found "Conta sem ovos."))
               estado (recuperar-estado! estado agora)
               resultado (store/operar! (:account-id estado) event-id
                           (store/fingerprint ["incubate" egg-id]) "incubate" (store/dia agora) agora
                           (fn [atual] [(ovos/incubar atual egg-id agora) {:egg-id egg-id}]))
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
       (when estado (p/then (recuperar-estado! estado (.now js/Date)) ovos/consulta))) true))

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
                              (when (or (seq (:history-pending estado))
                                        (some #(not (:projected? %)) (:receipts estado))
                                        (some #(contains? #{"ready_to_hatch" "delivery_pending"} (:state %)) (:eggs estado)))
                                (core/enfileirar-operacao-interna!
                                  (:chat-id estado)
                                  #(p/let [_ (armazenamento/recuperar-treinador! (:chat-id estado))]
                                     (recuperar-estado! estado (.now js/Date))) true))))
                          (p/resolved nil) states)]
                (when next-page (pagina! next-page))))]
      (p/let [_ (armazenamento/validar-adventure-schema!)
              _ (pagina! nil)]
        (verificar)
        (reset! pronto? true) (reset! aceitando? true) nil)))))
