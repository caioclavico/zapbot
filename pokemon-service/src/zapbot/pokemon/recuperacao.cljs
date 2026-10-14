(ns zapbot.pokemon.recuperacao
  "Consulta privada de saves legados. Não restaura, vincula ou grava contas."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [clojure.set :as set]
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

(defn- registros [conta]
  (vec
   (mapcat (fn [[local chave]]
             (map-indexed
              (fn [indice entrada]
                {:local local :indice (inc indice)
                 :pokemon (if (= chave "enfermaria") (get entrada "pokemon") entrada)})
              (get conta chave [])))
           [["Equipe" "equipe"] ["PC" "pc"] ["Enfermaria" "enfermaria"]])))

(defn- id-pokemon [registro]
  (let [id (get registro "id-pokemon")]
    (when (and (string? id) (not (str/blank? id))) id)))

(defn- descricao [{:keys [local indice pokemon]}]
  (if (map? pokemon)
    (str "• " (get pokemon "nome" "Sem nome")
         " — nível " (get pokemon "nivel" "?")
         " (" local " #" indice ")"
         (if-let [id (id-pokemon pokemon)] (str " — ID: " id) " — sem UUID"))
    (str "• Registro inválido (" local " #" indice ")")))

(defn- lista-limitada [itens]
  (let [limite 30]
    (str (if (seq itens)
           (str/join "\n" (map descricao (take limite itens)))
           "Nenhum registro.")
         (when (> (count itens) limite)
           (str "\n… mais " (- (count itens) limite) " registros não exibidos.")))))

(def ^:private tamanho-pagina 15)

(defn- pagina-valida? [texto]
  (and (string? texto)
       (boolean (re-matches #"[1-9][0-9]{0,5}" texto))))

(defn- argumentos-validos? [args]
  (or (contains? #{[] ["1"] ["2"] ["comparar"]} args)
      (and (= 2 (count args))
           (contains? #{"1" "2"} (first args))
           (pagina-valida? (second args)))))

(defn- listar-versao [versoes indice pagina]
  (if-let [{:keys [conta origens]} (get versoes indice)]
    (if (conta-valida? conta)
      (let [itens (registros conta)
            total (count itens)
            paginas (max 1 (js/Math.ceil (/ total tamanho-pagina)))
            versao (inc indice)]
        (if (> pagina paginas)
          (str "Página inexistente para a versão " versao
               ". Escolha uma página de 1 a " paginas ".")
          (let [inicio (* (dec pagina) tamanho-pagina)
                fim (min total (+ inicio tamanho-pagina))
                exibidos (subvec itens inicio fim)]
            (str "📦 VERSÃO " versao " — " (str/join " / " origens)
                 "\n" (resumo conta)
                 "\nPágina " pagina " de " paginas
                 " • Total: " total " Pokémon"
                 (when (pos? total) (str " • Exibindo " (inc inicio) "–" fim))
                 "\n\n"
                 (if (seq exibidos)
                   (str/join "\n" (map descricao exibidos))
                   "Nenhum registro.")
                 (when (> pagina 1)
                   (str "\n\n⬅️ !pk recuperar " versao " " (dec pagina)))
                 (when (< pagina paginas)
                   (str "\n➡️ !pk recuperar " versao " " (inc pagina)))))))
      "⚠️ Esta versão tem formato inválido e não pode ser listada.")
    "Versão inexistente."))

(defn- comparar-versoes [versoes]
  (if (not= 2 (count versoes))
    "A comparação requer exatamente duas versões."
    (let [[a b] (map :conta versoes)]
      (if-not (and (conta-valida? a) (conta-valida? b))
        "⚠️ Não é possível comparar versões com formato inválido."
        (let [ra (registros a)
              rb (registros b)
              agrupar (fn [rs]
                        (group-by #(id-pokemon (:pokemon %))
                                  (filter #(id-pokemon (:pokemon %)) rs)))
              ia (agrupar ra)
              ib (agrupar rb)
              duplicados (set/union
                          (set (for [[id entradas] ia :when (> (count entradas) 1)] id))
                          (set (for [[id entradas] ib :when (> (count entradas) 1)] id)))
              sa (set/difference (set (keys ia)) duplicados)
              sb (set/difference (set (keys ib)) duplicados)
              comuns (set/intersection sa sb)
              somente-a (set/difference sa sb)
              somente-b (set/difference sb sa)
              exclusivos-a (filter #(contains? somente-a (id-pokemon (:pokemon %))) ra)
              exclusivos-b (filter #(contains? somente-b (id-pokemon (:pokemon %))) rb)
              alterados (filter (fn [id]
                                  (not= (:pokemon (first (get ia id)))
                                        (:pokemon (first (get ib id)))))
                                comuns)
              sem-a (filter #(nil? (id-pokemon (:pokemon %))) ra)
              sem-b (filter #(nil? (id-pokemon (:pokemon %))) rb)
              dupl-a (filter #(contains? duplicados (id-pokemon (:pokemon %))) ra)
              dupl-b (filter #(contains? duplicados (id-pokemon (:pokemon %))) rb)]
          (str "🔍 COMPARAÇÃO DOS SAVES\n"
               "• UUIDs presentes nas duas: " (count comuns) "\n"
               "• Exclusivos da versão 1: " (count exclusivos-a) "\n"
               "• Exclusivos da versão 2: " (count exclusivos-b) "\n"
               "• UUIDs com registros diferentes: " (count alterados) "\n"
               "• Sem UUID na versão 1: " (count sem-a) "\n"
               "• Sem UUID na versão 2: " (count sem-b) "\n"
               "• UUIDs duplicados/ambíguos: " (count duplicados) "\n\n"
               "📦 SOMENTE NA VERSÃO 1\n" (lista-limitada exclusivos-a)
               "\n\n📦 SOMENTE NA VERSÃO 2\n" (lista-limitada exclusivos-b)
               "\n\n⚠️ IDENTIDADE NÃO CONFIRMADA\n"
               "• Registros com identidade ambígua na versão 1: " (count dupl-a) "\n"
               "• Registros com identidade ambígua na versão 2: " (count dupl-b) "\n"
               "Registros sem UUID ou com UUID duplicado não foram classificados como exclusivos."
               "\nUUIDs iguais com registros diferentes podem indicar evolução, alteração de atributos ou mudança de local."))))))

(defn identidade-exata [{:keys [chat-id player-id]}]
  ;; Future verified bindings belong here; command arguments are never identity.
  (when (and (string? chat-id) (re-matches #"[0-9-]+@g\.us" chat-id)
             (string? player-id) (re-matches #"[0-9]+@(lid|c\.us)" player-id))
    {:chat-id chat-id :player-id player-id}))

(defn consultar! [{:keys [chat-id player-id]} argumentos]
  (cond
    (not (argumentos-validos? (vec argumentos)))
    (p/resolved "🔒 Comando inválido. Use !pk recuperar, !pk recuperar 1 [página], !pk recuperar 2 [página] ou !pk recuperar comparar. A restauração não está disponível.")
    (nil? (identidade-exata {:chat-id chat-id :player-id player-id}))
    (p/resolved "🔎 Não foi possível validar a identidade desta consulta.")
    :else
    (let [pedido (armazenamento/consultar-treinador-legado! chat-id)
          atual (get-in (armazenamento/obter "treinador") [chat-id player-id])]
      (-> (p/let [dados pedido]
            (let [{:keys [status conta versoes]} (localizar dados chat-id player-id)
                  versoes (if (= status :encontrado)
                            [{:conta conta :origens ["Save antigo"]}]
                            versoes)
                  acao (first argumentos)
                    pagina (if (second argumentos) (js/parseInt (second argumentos) 10) 1)]
              (str "🧬 ODISSEU — RECUPERAÇÃO DE POKÉMON\n\n"
                   (case status
                     :encontrado
                     (case acao
                       "1" (listar-versao versoes 0 pagina)
                       "2" "Não existe uma segunda versão deste save."
                       "comparar" "Não existem duas versões diferentes para comparar."
                       (str "📦 SAVE ANTIGO\n" (resumo conta)
                            "\n\n⚡ SAVE ATUAL\n"
                            (if (conta-valida? atual) (resumo atual) "Nenhum progresso atual disponível.")))
                     :ambiguo
                     (case acao
                       "1" (listar-versao versoes 0 pagina)
                       "2" (listar-versao versoes 1 pagina)
                       "comparar" (comparar-versoes versoes)
                       (str "Encontramos versões diferentes do seu save antigo.\n\n"
                            (resumo-versoes versoes)
                            "\n\nNenhuma versão foi selecionada automaticamente."))
                     :invalido "O formato do save antigo não pôde ser validado com segurança."
                     "Não encontramos um save antigo associado com segurança à sua conta.")
                   "\n\n🔒 A restauração ainda não está disponível. Nenhum progresso foi alterado.")))
          (p/catch (fn [_]
                     (js/console.error "[Recuperação] Consulta Cassandra indisponível; dados preservados.")
                     "Não foi possível consultar os saves antigos agora. Nenhum progresso foi alterado."))))))
