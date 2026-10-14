(ns zapbot.pokemon.recuperacao
  "Consulta privada de saves legados. Não restaura, vincula ou grava contas."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]))

(defn- objeto-json [valor]
  (when-not (and (string? valor) (<= (count valor) (* 8 1024 1024)))
    (throw (js/Error. "Save inválido.")))
  (let [dados (js->clj (js/JSON.parse valor))]
    (when-not (map? dados) (throw (js/Error. "Save inválido.")))
    dados))

(defn- conta-valida? [conta]
  (and (map? conta)
       (some #(contains? conta %) ["equipe" "pc" "enfermaria" "pokedex"])
       (every? (fn [chave]
                 (or (not (contains? conta chave))
                     (and (vector? (get conta chave)) (every? map? (get conta chave)))))
               ["equipe" "pc" "enfermaria"])
       (every? (fn [chave] (or (not (contains? conta chave)) (map? (get conta chave))))
               ["pokedex" "ginasios"])))

(defn localizar [dados cid pid]
  (try
    (let [partes (map #(get (objeto-json %) pid) (:particionado dados))
          antigos (map #(get-in (objeto-json %) [cid pid]) (:antigo dados))
          encontrados (vec (distinct (remove nil? (concat partes antigos))))]
      (cond
        (and (:grupo dados) (not= cid (:grupo dados))) {:status :ausente}
        (empty? encontrados) {:status :ausente}
        (> (count encontrados) 1)
        {:status :ambiguo
         :versoes (mapv (fn [conta]
                          {:conta conta
                           :origens (vec
                                     (concat
                                      (when (some #(= conta %) partes)
                                        ["Particionado"])
                                      (when (some #(= conta %) antigos)
                                        ["Estado completo"])))})
                        encontrados)}
        (not (conta-valida? (first encontrados))) {:status :invalido}
        :else {:status :encontrado :conta (first encontrados)}))
    (catch :default _ {:status :invalido})))

(defn resumo [conta]
  ;; Count stored Pokémon, not capture-history or inferred trainer levels.
  (str "• Pokémon guardados: " (+ (count (get conta "equipe" []))
                                   (count (get conta "pc" []))
                                   (count (get conta "enfermaria" [])))
       (when (contains? conta "ginasios")
         (str "\n• Ginásios registrados: " (count (get conta "ginasios"))))))

(defn resumo-versoes [versoes]
  (str/join
   "\n\n"
   (map-indexed
    (fn [indice {:keys [conta origens]}]
      (str "📦 VERSÃO " (inc indice)
           " — " (str/join " / " origens)
           "\n"
           (if (conta-valida? conta)
             (resumo conta)
             "⚠️ Formato inválido; versão indisponível.")))
    versoes)))

(defn identidade-exata [{:keys [chat-id player-id]}]
  ;; Future verified bindings belong here; command arguments are never identity.
  (when (and (string? chat-id) (re-matches #"[0-9-]+@g\.us" chat-id)
             (string? player-id) (re-matches #"[0-9]+@(lid|c\.us)" player-id))
    {:chat-id chat-id :player-id player-id}))

(defn consultar! [{:keys [chat-id player-id]} argumentos]
  (cond
    (seq argumentos)
    (p/resolved "🔒 A restauração ainda não está disponível. Use !pk recuperar somente para consultar.")
    (nil? (identidade-exata {:chat-id chat-id :player-id player-id}))
    (p/resolved "🔎 Não foi possível validar a identidade desta consulta.")
    :else
    (let [pedido (armazenamento/consultar-treinador-legado! chat-id)
          atual (get-in (armazenamento/obter "treinador") [chat-id player-id])]
    (-> (p/let [dados pedido]
          (let [{:keys [status conta versoes]} (localizar dados chat-id player-id)]
            (str "🧬 ODISSEU — RECUPERAÇÃO DE POKÉMON\n\n"
                 (case status
                   :encontrado (str "📦 SAVE ANTIGO\n" (resumo conta)
                                    "\n\n⚡ SAVE ATUAL\n"
                                    (if (conta-valida? atual) (resumo atual) "Nenhum progresso atual disponível."))
                   :ambiguo (str "Encontramos versões diferentes do seu save antigo.\n\n"
                                 (resumo-versoes versoes)
                                 "\n\nNenhuma versão foi selecionada automaticamente.")
                   :invalido "O formato do save antigo não pôde ser validado com segurança."
                   "Não encontramos um save antigo associado com segurança à sua conta.")
                 "\n\n🔒 A restauração ainda não está disponível. Nenhum progresso foi alterado.")))
        (p/catch (fn [_]
                   (js/console.error "[Recuperação] Consulta Cassandra indisponível; dados preservados.")
                   "Não foi possível consultar os saves antigos agora. Nenhum progresso foi alterado."))))))
