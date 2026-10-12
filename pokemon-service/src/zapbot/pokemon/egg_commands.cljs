(ns zapbot.pokemon.egg-commands
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.pokemon.adventure :as adventure]))

(def ajuda
  "🥚 Ovos e incubadoras\n!pokemon ovos — inventário (até 9)\n!pokemon ovo — progresso\n!pokemon incubadoras — vagas e usos\n!pokemon incubar 1 — usa uma incubadora livre\n!pokemon incubar 1 permanente|extra|especial — escolha explícita\nComum: 2h ou 100 pontos. Raro: 6h ou 250. Épico: 12h ou 500.\nO número corresponde à lista de ovos disponíveis. Até três ovos simultâneos. A especial dá +25% nos pontos.")

(defn disponiveis [estado] (filterv #(= :inventory (:state %)) (:eggs estado)))
(defn inventario [estado]
  (let [ovos (disponiveis estado)
        total (count (remove #(= :hatched (:state %)) (:eggs estado)))]
    {:card-type "eggs"
     :card-data estado
     :texto (str "🥚 Inventário: " total "/9 ovos\n"
                 (if (empty? ovos) "Nenhum ovo disponível."
                     (str/join "\n" (map-indexed (fn [i o] (str (inc i) ". Ovo " (name (:category o)))) ovos)))
                 "\nUse !pokemon incubar 1 ou !pokemon ovo para ver o progresso.")}))

(defn incubadoras [estado]
  (let [texto (str "🧪 Incubadoras\n"
                  (str/join "\n"
                            (map (fn [i]
                                   (str (name (:kind i)) " — "
                                        (if (:active-egg-id i) "ocupada" "disponível")
                                        " — " (if (nil? (:uses i)) "usos ilimitados" (str (:uses i) " usos restantes"))
                                        (when (pos? (:bonus-points i)) " — +25% pontos")))
                                 (:incubators estado))))]
    {:card-type "incubators" :card-data estado :texto texto}))

(defn progresso [estado]
  (let [ativos (filter #(contains? #{:incubating :ready-to-hatch} (:state %)) (:eggs estado))]
    (if (empty? ativos) "🥚 Nenhum ovo em incubação. Use !pokemon ovos."
      (str "🔥 Incubações\n"
           (str/join "\n" (for [o ativos]
                             (str "Ovo " (name (:category o)) " — " (:points o) "/" (:points-goal o) " pontos"
                                  " — " (max 0 (js/Math.ceil (/ (- (:due-at o) (.now js/Date)) 60000))) " min restantes"
                                  (when (= :ready-to-hatch (:state o)) " — nascimento pendente; confira espaço na coleção e batalhas ativas"))))))))

(defn comando! [ctx cmd args]
  (if-not config/adventure-enabled?
    (p/resolved "🥚 O sistema de ovos está desativado.")
    (if (contains? #{"ajuda" "help" "?"} (first args)) (p/resolved ajuda)
      (-> (p/let [cid (:chat-id ctx) pid (:player-id ctx)
                  estado (adventure/atividade-ativar! cid pid "welcome")]
            (case cmd
              "ovos" (inventario estado)
              "ovo" (progresso estado)
              "incubadoras" (incubadoras estado)
              "incubar"
              (let [[numero tipo & extras] args
                    n (when (and numero (re-matches #"[1-9]" numero)) (js/parseInt numero))
                    ovo (when n (get (disponiveis estado) (dec n)))
                    i (when tipo (some #(when (= tipo (name (:kind %))) %) (:incubators estado)))]
                (cond
                  (or (nil? n) (seq extras)) ajuda
                  (nil? ovo) "🥚 Ovo indisponível. Consulte !pokemon ovos."
                  (and tipo (nil? i)) "🧪 Você não possui essa incubadora. Consulte !pokemon incubadoras."
                  (:active-egg-id i) "🧪 Essa incubadora está ocupada."
                  (not-any? #(nil? (:active-egg-id %)) (:incubators estado)) "🧪 Todas as incubadoras estão ocupadas."
                  :else
                  (p/then (adventure/atividade-incubar! cid pid
                             (str "whatsapp:" (:request-id ctx) ":incubar") (:egg-id ovo) (:incubator-id i))
                          (fn [novo]
                            (let [inc-id (:incubator-id (some #(when (= (:egg-id ovo) (:egg-id %)) %) (:eggs novo)))
                                  inc (some #(when (= inc-id (:incubator-id %)) %) (:incubators novo))]
                              (str "🥚 Ovo " (name (:category ovo)) " incubado na " (name (:kind inc)) "."))))))))
          (p/catch (fn [erro]
                     (if (:code (ex-data erro))
                       (do (js/console.error "[Ovos] Comando não concluído:" (name (:code (ex-data erro))))
                           (str "⚠️ " (ex-message erro) " Consulte o estado antes de repetir."))
                       (throw erro))))))))
