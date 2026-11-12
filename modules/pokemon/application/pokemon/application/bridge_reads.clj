(ns pokemon.application.bridge-reads
  "Leituras expostas pela bridge HTTP (M4.1/M4.3/M4.5).
  Sem escritas; sem operações administrativas; DTOs neutros (sem WhatsApp)."
  (:require [clojure.string :as str]
            [pokemon.application.adventure :as adv]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.hunt :as hunt-app]
            [pokemon.application.joy :as joy-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.shop :as shop-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.egg-activity :as egg-activity]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.joy :as joy]
            [pokemon.domain.mundo :as mundo]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.shop :as shop]
            [pokemon.domain.xp :as xp]
            [pokemon.domain.level-evolution :as level-evo])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(defn- require-player!
  [store chat-id player-id]
  (or (ports/get-player store chat-id player-id)
      (throw (err/error :player_not_found
                        {:chat-id chat-id :player-id player-id}))))

(defn- resolve-save-id!
  [store chat-id player-id save-id]
  (let [slot (or save-id (ports/active-save-id store chat-id player-id))]
    (when-not slot
      (throw (err/error :no_active_save
                        {:chat-id chat-id :player-id player-id})))
    (when-not (ports/get-save store chat-id player-id slot)
      (throw (err/error :save_not_found
                        {:chat-id chat-id :player-id player-id :save-id slot})))
    slot))

(defn- pokemon-dto
  "DTO de leitura. Campos de XP/evolução são aditivos e derivados das
  regras canônicas (`xp/progresso-pokemon`, `level-evo/proxima`) — sem
  inventar XP ausente e sem mutar o registro."
  [p]
  (let [base (select-keys p [:collection-index :id-pokemon :species :nome :nickname
                             :shiny :nivel :hp :hp-atual :status :in-party :tipos
                             :raridade :xp-desde-nivel :ataque :defesa :atq-esp :def-esp
                             :veloc :amizade :imagem :imagem-shiny :altura :golpes
                             :habilidade :ofertas-golpes :golpes-oferecidos
                             :historico-golpes :golpes-removidos :item])
        prog (xp/progresso-pokemon p)
        evo (level-evo/proxima p)
        pct (when (and (:conhecido? prog) (number? (:progresso-percentual prog)))
              (-> (* 100.0 (:progresso-percentual prog))
                  (* 10.0) Math/round (/ 10.0)))]
    (cond-> (assoc base
                   :xp-conhecido? (boolean (:conhecido? prog))
                   :nivel-maximo? (boolean (:nivel-maximo? prog))
                   :xp-nivel-atual (:xp-nivel-atual prog)
                   :xp-proximo-nivel (:xp-proximo-nivel prog)
                   :xp-necessario (:xp-proximo-nivel prog)
                   :progresso-xp-percentual pct)
      evo (assoc :proxima-evolucao (:nome evo)
                 :proxima-evolucao-species (:species evo)
                 :nivel-proxima-evolucao (:nivel-min evo)))))

(defn- save-profile
  [save]
  (when save
    (let [base (select-keys save [:slot-id :display-name :coins :revision :trainer-xp :trainer-level
                                  :xp-atual :xp-necessario :ativo-index
                                  :vitorias-treinador :pe-ginasios :pe-raids
                                  :xp-insignias :xp-missoes :sequencia-capturas
                                  :maior-sequencia-capturas])
          por-componentes (xp/perfil-treinador save)
          xp-stored (long (:trainer-xp save 0))
          xp (max (long (:xp por-componentes 0)) xp-stored)
          prog (assoc por-componentes :xp xp)]
      (merge base
             {:nivel (:nivel prog (:trainer-level save 1))
              :xp xp
              :xp-atual (:xp-atual prog (:xp-atual save 0))
              :xp-necessario (:xp-necessario prog (:xp-necessario save 5))}))))

(defn- storage-dto
  [save colecao]
  (when save
    (capacity/resumo-armazenamento-pokemon (count (or colecao [])) save)))

(defn- party-battle-resumo
  "Equipe de combate ≠ coleção.

  No V1 unificado (`equipe` + `pc` fundidos) todos ficam in-party=true — não
  há sinal seguro de escalação. Só confiamos quando in-party distingue um
  subconjunto real (parte dentro, parte fora). Não inventa os 6 primeiros."
  [colecao]
  (let [items (vec (or colecao []))
        n (count items)
        in (count (filter :in-party items))
        out (- n in)]
    (cond
      (zero? n) {:party-defined true :party-count 0}
      (and (pos? in) (pos? out)) {:party-defined true :party-count (long in)}
      :else {:party-defined false :party-count nil})))

(defn- save-slot-resumo
  "Resumo leve de um slot (contagens). Usa coleção já carregada quando fornecida."
  [store chat-id player-id save colecao-opt]
  (let [slot (:slot-id save)
        colecao (or colecao-opt
                    (ports/list-collection store chat-id player-id slot))
        party (party-battle-resumo colecao)]
    (merge {:save-id slot
            :display-name (:display-name save)
            :origin (:origin save)
            :legacy-version (:legacy-version save)
            :collection-count (count colecao)
            :trainer-level (long (:trainer-level save 1))}
           party)))

(defn- joy-resumo
  "Somente leitura: quantos na enfermaria e próximo retorno (ms)."
  [beds agora]
  (let [ativos (filterv #(> (long (:pronto-em % 0)) (long agora)) (or beds []))
        proximos (sort (map #(long (:pronto-em % 0)) ativos))
        proximo (first proximos)]
    {:em-tratamento (count ativos)
     :prontos (count (filter #(<= (long (:pronto-em % 0)) (long agora)) (or beds [])))
     :proximo-pronto-em proximo
     :proximo-restante-ms (when proximo (max 0 (- proximo (long agora))))}))

(defn- hunt-cooldown-resumo
  "Cooldown de caça a partir de ultima-cacada-at — sem RNG / sem plano de encontro."
  [save agora]
  (let [ultima (long (:ultima-cacada-at save 0))
        restante (hunt/cooldown-restante-ms ultima agora)]
    {:cooldown-ok (hunt/pode-cacar? ultima agora)
     :cooldown-restante-ms restante
     :cooldown-restante-s (long (Math/ceil (/ (double restante) 1000.0)))}))

(defn trainer
  "Perfil consolidado do treinador (somente leitura) para `!pk treinador`.
   Inclui progressão, armazenamento, saves A6, economia confiável, mochila,
   Joy e cooldown de caça — sem efeitos colaterais e sem RNG de caça."
  ([store chat-id player-id]
   (trainer store chat-id player-id nil))
  ([store chat-id player-id {:keys [agora-ms]}]
   (let [player (require-player! store chat-id player-id)
         slot (:active-save-id player)
         agora (long (or agora-ms (System/currentTimeMillis)))
         save (when slot (ports/get-save store chat-id player-id slot))
         colecao (when slot (ports/list-collection store chat-id player-id slot))
         inv (when slot (ports/list-inventory store chat-id player-id slot))
         beds (when slot (ports/list-enfermaria store chat-id player-id slot))
         saves (ports/list-saves store chat-id player-id)
         ativo-idx (long (:ativo-index save 0))
         ativo (when (and colecao (seq colecao) (< ativo-idx (count colecao)))
                 (nth colecao ativo-idx))
         storage (storage-dto save colecao)
         economia-ok? (boolean (and save (shop/economia-confiavel? save)))
         spendable (long (or (:coins-spendable save)
                             (:coins save 0)))
         bag-cap (when save (shop/capacidade-mochila-save save))
         bag-occ (shop/ocupacao-mochila (or inv []))
         party (party-battle-resumo colecao)
         shiny-n (count (filter :shiny (or colecao [])))
         saves-resumo
         (mapv (fn [s]
                 (let [sid (:slot-id s)
                       coll (if (= sid slot) colecao nil)
                       base (save-slot-resumo store chat-id player-id s coll)]
                   (assoc base :active (= sid slot))))
               saves)
         perfil* (save-profile save)
         ;; Saldo comprovado apenas; unknown-pending sem operacional → sem coins no perfil.
         perfil (cond-> perfil*
                  (and perfil* (pos? spendable)) (assoc :coins spendable)
                  (and perfil* (not economia-ok?) (zero? spendable)) (dissoc :coins))
         insignias (when save (xp/insignias-treinador save))
         titulo (when save (xp/titulo-label (:title save)))
         ginasios-map (or (:ginasios save) {})
         ginasios-nomes (if (seq ginasios-map)
                          (->> (keys ginasios-map)
                               (map #(or (:nome (gym/obter-ginasio %)) (str %)))
                               (str/join ", "))
                          "nenhuma")
         body (merge {:chat-id chat-id
                      :player-id player-id
                      :active-save-id slot
                      :active-save-name (:display-name save)
                      :fence-generation (:fence-generation player)
                      :active-battle-id (:active-battle-id player)
                      :gym-defense-count (:gym-defense-count player 0)
                      :profile perfil
                      :titulo titulo
                      :insignias (or insignias [])
                      :ginasios-nomes ginasios-nomes
                      :ativo-index ativo-idx
                      :ativo (when ativo (pokemon-dto ativo))
                      :collection-count (count colecao)
                      :shiny-count shiny-n
                      :saves-count (count saves)
                      :saves saves-resumo
                      :economia-confiavel economia-ok?
                      :capacidade-mochila bag-cap
                      :ocupacao-mochila bag-occ
                      :joy (joy-resumo beds agora)
                      :hunt (when save (hunt-cooldown-resumo save agora))}
                     party)]
     (cond-> body
       storage (merge {:capacidade-pokemon (:capacidade storage)
                       :ocupacao-pokemon (:ocupacao storage)
                       :livres-pokemon (:livres storage)
                       :excesso-pokemon (:excesso storage)
                       :colecao-cheia (:cheio? storage)
                       :expansoes-pokemon-50 (:expansoes-pokemon-50 storage)})
       save (assoc :coins spendable
                   :coins-spendable spendable
                   :coins-operational (long (or (:coins-operational save) 0))
                   :economy-status (or (:economy-status save)
                                       (if economia-ok? "trusted" "unknown-pending")))
       (and save (not economia-ok?)) (assoc :coins-status "unknown-pending")))))
(defn- favorito-dto
  [save items]
  (when-let [fid (:favorito-id-pokemon save)]
    (when-let [p (some #(when (= fid (:id-pokemon %)) %) items)]
      {:id fid
       :nome (:nome p)
       :collection-index (:collection-index p)})))

(defn collection
  "Coleção de um save (ativo se save-id omitido).
   Índices estáveis = collection-index (mesmos de escolher/evoluir/golpes)."
  [store chat-id player-id save-id]
  (require-player! store chat-id player-id)
  (let [slot (resolve-save-id! store chat-id player-id save-id)
        save (ports/get-save store chat-id player-id slot)
        items (ports/list-collection store chat-id player-id slot)
        fav (favorito-dto save items)
        storage (storage-dto save items)]
    (cond-> {:chat-id chat-id
             :player-id player-id
             :save-id slot
             :fence-generation (:fence-generation (require-player! store chat-id player-id))
             :ativo-index (long (:ativo-index save 0))
             :trainer-level (:trainer-level save 1)
             :favorito-id-pokemon (:favorito-id-pokemon save)
             :favorito fav
             :count (count items)
             :collection (mapv pokemon-dto items)}
      storage (merge {:capacidade-pokemon (:capacidade storage)
                      :ocupacao-pokemon (:ocupacao storage)
                      :livres-pokemon (:livres storage)
                      :excesso-pokemon (:excesso storage)
                      :colecao-cheia (:cheio? storage)
                      :expansoes-pokemon-50 (:expansoes-pokemon-50 storage)}))))
(def ^:private csv-columns
  ["pokemon_id" "numero_pokedex" "nome" "especie" "nivel"
   "xp_total" "xp_nivel_atual" "xp_proximo_nivel" "progresso_xp_percentual"
   "proxima_evolucao" "nivel_proxima_evolucao" "tipos"
   "situacao" "raridade" "hp_atual" "hp_maximo" "status" "item"
   "golpes" "collection_index"])

(defn- csv-neutralize
  "Previne CSV/formula injection em planilhas (Excel/Sheets/Calc)."
  [^String s]
  (if (and (pos? (.length s))
           (#{\= \+ \- \@ \tab \return} (.charAt s 0)))
    (str "'" s)
    s))

(defn- csv-field [value]
  (let [s (csv-neutralize (str (or value "")))]
    (if (re-find #"[;,\"\n\r]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn- csv-line [values]
  (str/join "," (map csv-field values)))

(defn- csv-num [v]
  (cond
    (nil? v) ""
    (number? v) (str v)
    :else (str v)))

(defn- golpes-csv [p]
  (->> (or (:golpes p) [])
       (map (fn [g]
              (or (:nome-exibicao g) (:nome g) (:slug g) "")))
       (remove str/blank?)
       (str/join "|")))

(defn- team-csv-row
  [situation p number-or-blank]
  (let [dto (pokemon-dto p)
        prog (xp/progresso-pokemon p)
        ;; id público já usado na coleção (não JID/telefone)
        public-id (or (:id-pokemon p) "")
        xp-atual (when (:conhecido? prog) (:xp-nivel-atual prog))
        xp-prox (when (:conhecido? prog) (:xp-proximo-nivel prog))
        pct (when (and (:conhecido? prog) (number? (:progresso-percentual prog))
                       (not (:nivel-maximo? prog)))
              (format "%.1f" (double (* 100.0 (:progresso-percentual prog)))))]
    (csv-line [public-id
               "" ;; numero_pokedex — não fabricar se ausente no registro
               (:nome dto)
               (or (:species dto) "")
               (:nivel dto 1)
               "" ;; xp_total acumulado não é persistido canonicamente
               (csv-num xp-atual)
               (csv-num xp-prox)
               (or pct "")
               (or (:proxima-evolucao dto) "")
               (csv-num (:nivel-proxima-evolucao dto))
               (str/join "/" (:tipos dto))
               situation
               (or (:raridade dto) "comum")
               (:hp-atual dto)
               (:hp dto)
               (or (:status dto) "")
               (or (:item dto) "")
               (golpes-csv p)
               (or number-or-blank
                   (when (some? (:collection-index p))
                     (inc (long (:collection-index p)))))])))

(defn team-csv
  "Exportação somente leitura do time autorizado (`!pk tm csv` / `time csv`).
   UTF-8 com BOM para Excel; RFC 4180 (vírgula); sem dados de terceiros."
  [store chat-id player-id save-id]
  (require-player! store chat-id player-id)
  (let [slot (resolve-save-id! store chat-id player-id save-id)
        items (ports/list-collection store chat-id player-id slot)
        beds (ports/list-enfermaria store chat-id player-id slot)
        content (str "\uFEFF"
                     (str/join "\n"
                               (cons (csv-line csv-columns)
                                     (concat
                                      (map-indexed
                                       (fn [i p] (team-csv-row "No time" p (inc i)))
                                       items)
                                      (map (fn [bed]
                                             (team-csv-row "Enfermaria" (:pokemon bed) ""))
                                           beds))))
                     "\n")
        encoded (.encodeToString (Base64/getEncoder) (.getBytes content StandardCharsets/UTF_8))]
    {:chat-id chat-id :player-id player-id :save-id slot
     :filename "odisseu-time-pokemon.csv" :mime-type "text/csv"
     :text (str "🎒 Seu time: " (+ (count items) (count beds))
                " Pokémon na planilha em anexo (odisseu-time-pokemon.csv).")
     :base64 encoded}))

(defn- species-key
  [nome]
  (-> (str nome)
      str/lower-case
      (str/replace #"\s+" "-")))

(defn- merge-pokedex-from-collection
  "Espelha sincronizar-pokedex-equipe: inclui espécies da coleção sem apagar
   contadores já persistidos na Pokédex."
  [entries items]
  (reduce
   (fn [acc p]
     (let [sp (or (:species p) (:nome p))
           k (species-key sp)
           prev (get acc k)
           niveis (conj (or (:indices prev) [])
                        {:numero (inc (long (:collection-index p 0)))
                         :nivel (long (:nivel p 1))})
           base (or prev {:species sp
                          :nome (or (:nome p) sp)
                          :seen true
                          :caught true
                          :seen-count 0
                          :caught-count 0
                          :max-level 0
                          :shiny-caught 0
                          :raridade (or (:raridade p) "comum")
                          :tipos (vec (:tipos p))})]
       (assoc acc k
              (-> base
                  (assoc :seen true :caught true
                         :indices niveis
                         :nome (or (:nome base) (:nome p) sp)
                         :tipos (if (seq (:tipos base)) (:tipos base) (vec (:tipos p)))
                         :raridade (or (:raridade base) (:raridade p) "comum")
                         :max-level (max (long (:max-level base 0)) (long (:nivel p 1)))
                         :caught-count (max (long (:caught-count base 0)) 1)
                         :shiny-caught (max (long (:shiny-caught base 0))
                                            (if (:shiny p) 1 0)))))))
   (into {}
         (map (fn [e]
                [(species-key (:species e))
                 (-> e
                     (assoc :nome (or (:nome e) (str/capitalize (str (:species e))))
                            :indices []))]))
         entries)
   items))

(defn pokedex
  "Pokédex pessoal do save ativo (ou save-id): espécies vistas/capturadas +
   índices da coleção alinhados a !pk time."
  [store chat-id player-id save-id]
  (require-player! store chat-id player-id)
  (let [slot (resolve-save-id! store chat-id player-id save-id)
        save (ports/get-save store chat-id player-id slot)
        items (ports/list-collection store chat-id player-id slot)
        entries (ports/list-pokedex-entries store chat-id player-id slot)
        merged (merge-pokedex-from-collection entries items)
        especies (->> (vals merged)
                      (sort-by #(str/lower-case (str (:nome %))))
                      vec)
        total hunt/total-pokemons
        vistos (count (filter :seen (vals merged)))
        capturados (count (filter :caught (vals merged)))
        avistamentos (reduce + 0 (map #(long (:seen-count % 0)) (vals merged)))
        capturas (reduce + 0 (map #(long (:caught-count % 0)) (vals merged)))
        shiny (reduce + 0 (map #(long (:shiny-caught % 0)) (vals merged)))
        ativo-idx (long (:ativo-index save 0))
        ativo (when (and (seq items) (< ativo-idx (count items)))
                (nth items ativo-idx))]
    {:chat-id chat-id
     :player-id player-id
     :save-id slot
     :fence-generation (:fence-generation (require-player! store chat-id player-id))
     :total-pokemons total
     :vistos vistos
     :avistamentos (max avistamentos vistos)
     :capturados capturados
     :capturas (max capturas capturados)
     :shiny shiny
     :desconhecidos (max 0 (- total vistos))
     :sequencia-capturas (long (:sequencia-capturas save 0))
     :ativo-index ativo-idx
     :ativo (when ativo (pokemon-dto ativo))
     :favorito (favorito-dto save items)
     :entries (mapv (fn [e]
                      (-> e
                          (select-keys [:species :nome :seen :caught :seen-count
                                        :caught-count :max-level :shiny-caught
                                        :raridade :tipos :indices])
                          (assoc :capturas (long (:caught-count e 0))
                                 :maior-nivel (long (:max-level e 0))
                                 :shiny-capturados (long (:shiny-caught e 0)))))
                    especies)}))

(defn pokemon-ficha
  "Ficha do Pokémon no índice estável da coleção (1-based no Node → 0-based aqui)."
  [store chat-id player-id save-id idx]
  (require-player! store chat-id player-id)
  (let [slot (resolve-save-id! store chat-id player-id save-id)
        save (ports/get-save store chat-id player-id slot)
        items (ports/list-collection store chat-id player-id slot)
        total (count items)
        idx* (long idx)]
    (when (or (neg? idx*) (>= idx* total))
      (throw (err/error :invalid_collection_index
                        {:idx idx* :total total})))
    (let [p (nth items idx*)
          fav (favorito-dto save items)
          ativo-idx (long (:ativo-index save 0))]
      {:chat-id chat-id
       :player-id player-id
       :save-id slot
       :fence-generation (:fence-generation (require-player! store chat-id player-id))
       :idx idx*
       :is-ativo (= idx* ativo-idx)
       :is-favorito (boolean (and fav (= (:id-pokemon p) (:id fav))))
       :total total
       :pokemon (pokemon-dto p)
       :favorito fav
       :ativo-index ativo-idx})))

(defn active-save
  "Save ativo + party + inventário (snapshot de leitura)."
  [store chat-id player-id]
  (let [snap (trainer/active-save store chat-id player-id)
        save (:save snap)
        player (:player snap)]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player)
     :save (save-profile save)
     :party (mapv pokemon-dto (:party snap))
     :inventory (:inventory snap)}))

(defn- encounter-pokemon-dto
  "DTO de apresentação (HP atual/máx + golpes). `hp-atual` vem do mapa de batalha.
   Raridade: canônica do motor (`assegurar-raridade`) — nunca inventa `?`/`comum` silencioso.
   Golpes re-normalizados (string-keys / slug) para UI não mostrar `? — Poder 0`."
  ([p] (encounter-pokemon-dto p nil))
  ([p hp-atual]
   (when p
     (let [p (capture/assegurar-raridade p)
           resolved (capture/resolver-raridade p)
           golpes (->> (or (:golpes p) (get p "golpes") [])
                       (mapv battle/golpe-normalizado)
                       (filterv map?))]
       (cond-> (select-keys p [:nome :species :nickname :shiny :shiny? :nivel :hp
                               :raridade :tipos :ataque :defesa :atq-esp :def-esp :veloc
                               :imagem :imagem-shiny :altura :status :habilidade
                               :motivacao-ginasio :collection-index])
         true (assoc :hp-atual (long (or hp-atual (:hp-atual p) (:hp p) 0))
                     :raridade (:raridade resolved)
                     :raridade-fonte (name (:fonte resolved))
                     :golpes golpes)
         (:shiny? p) (assoc :shiny true))))))

(defn- area-visual
  "Cores/emoji do bioma para composição de arena no Node (sem Cassandra)."
  [area-id]
  (when-let [a (mundo/obter-area (str area-id))]
    (hunt/area-dto a)))

(defn hunt-plan
  "Plano de caça + elegibilidade (RNG tipos/surto no servidor)."
  [store chat-id player-id opts]
  (require-player! store chat-id player-id)
  (let [plano (hunt-app/plan store chat-id player-id opts)]
    ;; API sem `?` nas chaves (encoder camelCase).
    (-> plano
        (dissoc :surto? :roll-tipos :roll-surto)
        (assoc :surto (boolean (:surto? plano))
               :roll-tipos (:roll-tipos plano)
               :roll-surto (:roll-surto plano)))))

(defn active-encounter
  "Encontro ativo (batalha em curso ou aguardando captura)."
  [store chat-id player-id]
  (require-player! store chat-id player-id)
  (let [player (ports/get-player store chat-id player-id)
        battle (ports/active-battle store chat-id player-id)
        inv (when-let [slot (:active-save-id player)]
              (ports/list-inventory store chat-id player-id slot))]
    (if-not battle
      {:chat-id chat-id
       :player-id player-id
       :active-save-id (:active-save-id player)
       :fence-generation (:fence-generation player)
       :encounter nil}
      (let [seq-cap (long (or (when-let [slot (:active-save-id player)]
                                (:sequencia-capturas
                                 (ports/get-save store chat-id player-id slot)))
                              0))
            poke-o (when-let [p (get-in battle [:pokemons :o])]
                     (capture/assegurar-raridade p))
            chance-base (or (:chance-base-captura battle)
                            (when poke-o
                              (capture/chance-base-pos-batalha
                               poke-o (get-in battle [:status :o]) seq-cap)))
            aguardando? (boolean (:aguardando-captura? battle))
            frambo-prep? (boolean (:frambo-preparada battle))
            frambo-qty (long (or (some #(when (= capture/frambo-item-id
                                                 (or (:item-id %) (get % "item-id")))
                                          (or (:qty %) (get % "qty")))
                                       inv)
                                 0))
            chances (when (and aguardando? chance-base)
                      (capture/chances-painel chance-base frambo-prep?))
            resolved (when poke-o (capture/resolver-raridade poke-o))]
        {:chat-id chat-id
         :player-id player-id
         :active-save-id (:active-save-id player)
         :fence-generation (:fence-generation player)
         :encounter
         (cond-> {:battle-id (:battle-id battle)
                  :save-id (:save-id battle)
                  :kind (name (:kind battle :wild))
                  :gym-id (:gym-id battle)
                  :gym-nome (:gym-nome battle)
                  :lider-nome (:lider-nome battle)
                  :active (boolean (:active? battle))
                  :aguardando-captura aguardando?
                  :phase (:phase battle)
                  :outcome (:outcome battle)
                  :turn-index (:turn-index battle 0)
                  :tentativas-captura (long (:tentativas-captura battle 0))
                  :chance-base-captura chance-base
                  :frambo frambo-qty
                  :frambo-preparada frambo-prep?
                  :chances chances
                  :hp (:hp battle)
                  :status (:status battle)
                  :bioma (:bioma battle)
                  :clima (let [c (:clima battle)]
                           (cond
                             (map? c) (hunt/clima-dto c)
                             c (some-> (mundo/obter-clima (str c)) hunt/clima-dto)
                             :else nil))
                  :area (area-visual (:bioma battle))
                  :somente-batalha (boolean (:somente-batalha? battle))
                  :last-switch (:last-switch battle)
                  :reservas-x (count (get-in battle [:reservas :x] []))
                  :reservas-o (count (get-in battle [:reservas :o] []))
                  :pokemon-x (encounter-pokemon-dto (get-in battle [:pokemons :x])
                                                    (get-in battle [:hp :x]))
                  :pokemon-o (encounter-pokemon-dto poke-o
                                                    (get-in battle [:hp :o]))
                  :inventory (or inv [])}
           resolved (assoc :raridade (:raridade resolved)
                           :raridade-fonte (name (:fonte resolved))))}))))

(defn- format-duracao-curta
  [ms]
  (let [ms (max 0 (long (or ms 0)))
        h (quot ms (* 60 60 1000))
        m (quot (rem ms (* 60 60 1000)) (* 60 1000))]
    (cond
      (and (pos? h) (pos? m)) (str h "h" m "m")
      (pos? h) (str h "h")
      (pos? m) (str m "min")
      :else "<1min")))

(defn- occupation-dto
  [ocup agora]
  (when ocup
    (let [mots (gym/motivacoes ocup agora)
          time (gym/time-defensor ocup agora)
          dur (gym/duracao-ms ocup agora)
          bruta (gym/permanencia-bruta-moedas dur)
          enabled? (gym/permanence-rewards-enabled?)]
      (cond-> {:gym-id (:gym-id ocup)
               :player-id (:pid ocup)
               :save-id (:save-id ocup)
               :nome (:nome ocup)
               :desde (:desde ocup)
               :revision (:revision ocup 0)
               :motivacao mots
               :motivacao-em (:motivacao-em ocup)
               :tempo-defesa-ms dur
               :tempo-defesa (format-duracao-curta dur)
               :time (mapv #(-> (encounter-pokemon-dto %)
                                (assoc :motivacao-ginasio (:motivacao-ginasio %)))
                           time)}
        enabled? (assoc :recompensa-estimada bruta
                        :economia-permanencia-ativa true)))))

(defn- gym-catalog-row
  [g insignias ocup agora]
  (let [id (:id g)
        unlocked? (gym/desbloqueado? insignias id)]
    {:id id
     :nome (:nome g)
     :lider-npc (:lider g)
     :nivel (:nivel g)
     :item (:item g)
     :time-npc (:time g)
     :pool-npc (gym/pool-npc g)
     :desbloqueado unlocked?
     :insignia (get insignias id)
     :ocupacao (when ocup (occupation-dto ocup agora))}))

(defn gyms-overview
  "Menu de ginásios: catálogo + unlock + ocupações do chat."
  [store chat-id player-id {:keys [agora-ms]}]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        save (when slot (ports/get-save store chat-id player-id slot))
        insignias (or (:ginasios save) {})
        agora (long (or agora-ms (System/currentTimeMillis)))
        rows (mapv (fn [g]
                     (let [ocup (gym-app/get-occupation store chat-id (:id g))]
                       (gym-catalog-row g insignias ocup agora)))
                   gym/ginasios)]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id slot
     :fence-generation (:fence-generation player 0)
     :gym-defense-count (:gym-defense-count player 0)
     :insignias insignias
     :desafio-habilitado (gym/challenges-enabled?)
     :desafio-bloqueio (when-not (gym/challenges-enabled?)
                         "desafios temporariamente suspensos (manutenção)")
     :gyms rows}))

(defn gym-detail
  "Detalhe de um ginásio + time defensor com motivação."
  [store chat-id player-id gym-id {:keys [agora-ms]}]
  (let [g (or (gym/obter-ginasio (str gym-id))
              (throw (err/error :unknown_gym {:gym-id gym-id})))
        overview (gyms-overview store chat-id player-id {:agora-ms agora-ms})
        row (some #(when (= (:id g) (:id %)) %) (:gyms overview))]
    (merge (select-keys overview [:chat-id :player-id :active-save-id
                                  :fence-generation :gym-defense-count
                                  :insignias :desafio-habilitado :desafio-bloqueio])
           {:gym row})))

(defn gym-npc-roll
  "Sorteio determinístico de 3 espécies NPC (puro). Seed tipicamente = operationId.
   Não altera ocupação de jogadores — só catálogo do ginásio do jogo."
  [gym-id seed]
  (let [g (or (gym/obter-ginasio (str gym-id))
              (throw (err/error :unknown_gym {:gym-id gym-id})))
        rng (gym/seed->npc-rng (or seed (:id g)))
        species (gym/sortear-especies-npc g rng)]
    {:gym-id (:id g)
     :seed (str (or seed ""))
     :species species
     :pool (gym/pool-npc g)}))

(defn gym-team
  "Escalação de ginásio do save ativo (índices + Pokémon)."
  [store chat-id player-id]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        save (when slot (ports/get-save store chat-id player-id slot))
        idxs (vec (or (:time-ginasio save) []))
        colecao (when slot (ports/list-collection store chat-id player-id slot))
        membros (mapv (fn [i]
                        (when (and colecao (<= 0 i (dec (count colecao))))
                          (assoc (pokemon-dto (nth colecao i))
                                 :collection-index i)))
                      idxs)]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id slot
     :fence-generation (:fence-generation player 0)
     :indices idxs
     :membros (vec (remove nil? membros))
     :completo? (and (= 3 (count idxs))
                     (= 3 (count (set idxs)))
                     (= 3 (count (remove nil? membros))))}))

(defn gym-ranking
  "Ranking persistido no mesmo formato semântico do legado."
  [store chat-id player-id gym-id {:keys [agora-ms]}]
  (require-player! store chat-id player-id)
  (when-not (gym/obter-ginasio (str gym-id))
    (throw (err/error :unknown_gym {:gym-id gym-id})))
  (let [stats (or (ports/get-gym-statistics store chat-id (str gym-id))
                  {:leaders {} :history []})
        ocup (gym-app/get-occupation store chat-id (str gym-id))
        agora (long (or agora-ms (System/currentTimeMillis)))
        leaders (cond-> (:leaders stats)
                  ocup (update (:pid ocup) (fn [old]
                                             (let [old (or old {})
                                                   elapsed (max 0 (- agora (long (:desde ocup agora))))]
                                               (assoc old :nome (or (:nome old) (:nome ocup) (:pid ocup))
                                                          :defesas (long (:defesas old 0))
                                                          :tempo-ms (+ (long (:tempo-ms old 0)) elapsed))))))
        rows (mapv (fn [[pid row]] (merge {:player-id pid} row)) leaders)
        by-defesas (->> rows (sort-by #(long (:defesas % 0)) >) (take 10) vec)
        by-tempo (->> rows (sort-by #(long (:tempo-ms % 0)) >) (take 10) vec)]
    {:chat-id chat-id :player-id player-id :gym-id (str gym-id)
     :ranking by-defesas :ranking-defesas by-defesas :ranking-tempo by-tempo
     :generated-at agora}))

(defn gym-history
  [store chat-id player-id gym-id]
  (require-player! store chat-id player-id)
  (when-not (gym/obter-ginasio (str gym-id))
    (throw (err/error :unknown_gym {:gym-id gym-id})))
  (let [stats (or (ports/get-gym-statistics store chat-id (str gym-id))
                  {:history []})]
    {:chat-id chat-id :player-id player-id :gym-id (str gym-id)
     :history (->> (:history stats) reverse (take 10) vec)}))

(defn- raid-participant-dto
  [pid p viewer-id]
  (cond-> {:player-id pid
           :nome (:nome p)
           :hp (:hp p)
           :dano (:dano p 0)
           :collection-index (:collection-index p)
           :pokemon (encounter-pokemon-dto (:pokemon p))
           ;; Sem `?` no keyword: JSON camelCase → `voce` (não `voce?`).
           :voce (= pid viewer-id)}
    (= pid viewer-id)
    (assoc :save-id (:save-id p)
           :fence-generation (:fence-generation p))))

(defn- raid-dto
  [raid player-id agora]
  (when raid
    (let [fase (str (:fase raid))
          ;; Vitória só conta como "ativa" se este jogador ainda tem captura pendente.
          ativa (or (raid/ativa? raid agora)
                    (boolean (and player-id (raid/captura-pendente? raid player-id agora))))
          parts (:participantes raid {})
          viewer (get parts player-id)
          cap (get-in raid [:capturas player-id])
          vez (:vez raid)
          ;; Identidade canônica: igualdade literal do player-id autenticado
          ;; com a chave persistida em :vez/:participantes (sem strip de sufixo).
          sua-vez? (boolean (and player-id vez (= player-id vez)))
          vez-participante (when vez (get parts vez))
          proxima (when (and (= "combate" fase) vez)
                    (let [ordem (:ordem raid [])
                          vivos (filterv #(pos? (get-in parts [% :hp] 0)) ordem)
                          depois (concat (rest (drop-while #(not= vez %) vivos))
                                         (take-while #(not= vez %) vivos))]
                      (first depois)))]
      {:raid-id (:raid-id raid)
       :chat-id (:chat-id raid)
       :ginasio (:ginasio raid)
       :nome-ginasio (:nome-ginasio raid)
       :nivel-ginasio (:nivel-ginasio raid)
       :liga (:liga raid)
       :fase fase
       :ativa ativa
       :expira (:expira raid)
       :captura-expira (:captura-expira raid)
       :hp-chefe (:hp-chefe raid)
       :hp-max (:hp-max raid)
       :vez vez
       :vez-nome (or (:nome vez-participante) nil)
       :vez-pokemon (when-let [pk (:pokemon vez-participante)]
                      (encounter-pokemon-dto pk))
       :vez-hp (:hp vez-participante)
       :vez-valida (boolean (and vez (contains? parts vez)
                                 (pos? (long (:hp vez-participante 0)))))
       ;; Keywords sem `?` → `suaVez` / `inscrito` no JSON (contrato Node).
       :sua-vez sua-vez?
       :turn-index (:turn-index raid 0)
       :ordem (:ordem raid [])
       :n-participantes (count (:ordem raid []))
       :inscrito (boolean viewer)
       :proxima-vez proxima
       :proxima-vez-nome (when proxima (get-in parts [proxima :nome]))
       :chefe (encounter-pokemon-dto (:chefe raid))
       :participantes
       (mapv (fn [pid]
               (raid-participant-dto pid (get parts pid) player-id))
             (:ordem raid []))
       :captura-pendente
       (boolean (and player-id (raid/captura-pendente? raid player-id agora)))
       :captura (when (or cap (= "vitoria" fase))
                  (let [chefe (:chefe raid)
                        raridade (or (:raridade chefe) (get chefe "raridade") "raro")
                        base (raid/chance-captura raridade)
                        frambo? (boolean (:frambo-preparada cap))
                        chances
                        (into {}
                              (for [bola (keys capture/bolas)]
                                [bola (capture/chance-com-bola-frambo base bola frambo?)]))]
                    {:tentativas (long (:tentativas cap 0))
                     :max-tentativas raid/max-tentativas-captura
                     :capturou (boolean (:capturou cap))
                     :frambo-preparada frambo?
                     :chance-base base
                     :chances chances
                     :captura-expira (:captura-expira raid)}))
       :rewards-paid (boolean (:rewards-paid? raid))})))

(defn- inv-qty
  "Soma qty canônica + aliases legados (mesma regra do débito em store)."
  [inv item-id]
  (let [ids (capture/bola-alias-ids item-id)]
    (->> (or inv [])
         (filter #(contains? ids (str (:item-id %))))
         (map #(long (:qty % 0)))
         (reduce + 0))))

(defn active-raid
  "Raid ativa (inscrições/combate) ou captura pendente *deste* jogador.
   Vitória já resolvida para o viewer não ocupa mais o menu `!pk raid`."
  [store chat-id player-id {:keys [agora-ms]}]
  (let [player (require-player! store chat-id player-id)
        agora (long (or agora-ms (System/currentTimeMillis)))
        ;; Eager expire materializa cancelada/combate sem timer em memória.
        _ (raid-app/expire-lobby! store chat-id agora)
        raid0 (raid-app/active-raid store chat-id)
        raid (when (raid/visivel-para? raid0 agora player-id) raid0)
        inv (when-let [slot (:active-save-id player)]
              (ports/list-inventory store chat-id player-id slot))
        dto (raid-dto raid player-id agora)
        captura-ui (when (and dto (:captura-pendente dto))
                     {:bolas (into {}
                                   (for [b (keys capture/bolas)]
                                     [b (inv-qty inv b)]))
                      :frambo (inv-qty inv capture/frambo-item-id)
                      :frambo-preparada (boolean (get-in dto [:captura :frambo-preparada]))
                      :chances (get-in dto [:captura :chances])
                      ;; Base kebab-free number — Node fallback se camelCase perder chaves.
                      :chance-base (or (get-in dto [:captura :chance-base])
                                       (get-in dto [:captura :chanceBase]))
                      :tentativas (get-in dto [:captura :tentativas] 0)
                      :max-tentativas raid/max-tentativas-captura
                      :chefe (get-in dto [:chefe :nome])})]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player 0)
     :agora agora
     :raid dto
     :captura-ui captura-ui
     :inventory (or inv [])}))

(defn raid-eligible-pokemon
  "Índices da coleção aptos para a liga da raid ativa (texto !pk raide time)."
  ([store chat-id player-id]
   (raid-eligible-pokemon store chat-id player-id {}))
  ([store chat-id player-id {:keys [agora-ms]}]
   (let [player (require-player! store chat-id player-id)
         slot (:active-save-id player)
         agora (long (or agora-ms (System/currentTimeMillis)))
         _ (raid-app/expire-lobby! store chat-id agora)
         raid0 (raid-app/active-raid store chat-id)
         raid (when (raid/inscricoes-abertas? raid0 agora) raid0)
         _ (when-not raid
             (throw (err/error :raid_not_open {:fase (:fase raid0)})))
         colecao (when slot (ports/list-collection store chat-id player-id slot))
         aptos (filterv
                (fn [p]
                  (and (pos? (long (:hp-atual p 0)))
                       (some (fn [g]
                               (let [c (or (:classe g) (get g "classe"))
                                     poder (or (:poder g) (get g "poder") 0)]
                                 (and (contains? #{"fisico" "especial" :fisico :especial} c)
                                      (pos? (long poder)))))
                             (or (:golpes p) []))))
                (or colecao []))]
     {:chat-id chat-id
      :player-id player-id
      :liga (:liga raid)
      :raid-id (:raid-id raid)
      :aptos (mapv #(assoc (pokemon-dto %)
                           :collection-index (:collection-index %))
                   aptos)})))

(defn- bed-dto
  [bed agora]
  (let [pronto? (<= (long (:pronto-em bed 0)) (long agora))
        poke (:pokemon bed)]
    {:bed-id (:bed-id bed)
     :pronto-em (:pronto-em bed)
     :pronto pronto?
     :restante-ms (max 0 (- (long (:pronto-em bed 0)) (long agora)))
     :pokemon (pokemon-dto poke)}))

(defn joy-status
  "Enfermaria do save ativo + feridos na coleção (1-based na apresentação Node)."
  [store chat-id player-id {:keys [agora-ms]}]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        agora (long (or agora-ms (System/currentTimeMillis)))
        beds (when slot (joy-app/list-beds store chat-id player-id slot))
        colecao (when slot (ports/list-collection store chat-id player-id slot))
        feridos (filterv #(joy/ferido? %) (or colecao []))]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id slot
     :fence-generation (:fence-generation player 0)
     :agora agora
     :tempo-tratamento-segundos (joy/tempo-tratamento-segundos)
     :tempo-tratamento-minutos (long (Math/ceil (/ (double (joy/tempo-tratamento-segundos)) 60.0)))
     :enfermaria (mapv #(bed-dto % agora) (or beds []))
     :prontos (count (filter #(<= (long (:pronto-em % 0)) agora) (or beds [])))
     :em-tratamento (count (filter #(> (long (:pronto-em % 0)) agora) (or beds [])))
     :feridos (mapv #(assoc (pokemon-dto %)
                            :collection-index (:collection-index %))
                    feridos)}))

(defn- egg-dto
  [o agora]
  (let [cat (name (or (:category o) :comum))
        state (name (or (:state o) :inventory))
        due (:due-at o)
        restante (when (and due (= "incubating" state))
                   (max 0 (- (long due) (long agora))))
        pts (long (:points o 0))
        goal (long (:points-goal o 0))
        tms (long (:time-ms o 0))
        tgoal (long (:time-goal-ms o 0))
        pct-pts (when (pos? goal) (min 100 (long (/ (* pts 100) goal))))
        pct-time (when (pos? tgoal) (min 100 (long (/ (* tms 100) tgoal))))
        pct (max (or pct-pts 0) (or pct-time 0))]
    {:egg-id (:egg-id o)
     :category cat
     :state state
     ;; Espécie permanece no domínio; DTO público omite até hatch (presenter).
     :species-hidden true
     :points pts
     :points-goal goal
     :points-restante (max 0 (- goal pts))
     :time-ms tms
     :time-goal-ms tgoal
     :progress-pct pct
     :due-at due
     :restante-ms restante
     :incubator-id (:incubator-id o)
     :save-id (:save-id o)
     :fence-generation (:fence-generation o)
     :pronto (eggs/pronto? o)}))

(defn- incubator-dto
  [i]
  {:incubator-id (:incubator-id i)
   :kind (name (or (:kind i) :permanente))
   :uses (:uses i)
   :bonus-points (:bonus-points i 0)
   :active-egg-id (:active-egg-id i)
   :livre (nil? (:active-egg-id i))})

(defn- pending-incubator-dto
  [p]
  {:reward-id (:reward-id p)
   :kind (name (or (:kind p) :extra))
   :label (:label p)
   :uses (:uses p)
   :bonus-points (:bonus-points p 0)
   :source (when-let [s (:source p)] (name s))})

(defn- incubator-reward-catalog-dto
  []
  (mapv (fn [[id meta]]
          {:reward-id id
           :kind (name (:kind meta))
           :source (name (:source meta))
           :threshold (:threshold meta)
           :uses (:uses meta)
           :bonus-points (:bonus-points meta)
           :label (:label meta)})
        (sort-by key egg-activity/incubator-reward-catalog)))

(defn adventure-status
  "Conta Adventure Sync (ovos/incubadoras). Tempo avançado só no DTO (lazy)."
  [store chat-id player-id {:keys [agora-ms]}]
  (let [player (require-player! store chat-id player-id)
        agora (long (or agora-ms (System/currentTimeMillis)))
        _ (adv/ensure-account! store chat-id player-id)
        acc (or (adv/get-account store chat-id player-id) (eggs/estado-inicial))
        estado (eggs/avancar-tempo acc agora)
        eggs-l (:eggs estado [])
        pi-day (:pi-daily estado)
        pi-used (long (:points pi-day 0))
        pi-cap (egg-activity/daily-cap)
        pending (vec (:pending-egg-grants estado []))
        pending-inc (vec (:pending-incubator-grants estado []))
        receipts (set (:receipts estado #{}))
        unlocked (filterv #(contains? receipts (:reward-id %))
                          (incubator-reward-catalog-dto))]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player 0)
     :agora agora
     :limite-inventario eggs/limite-inventario
     :inventario-count (eggs/inventariados-count estado)
     :eggs (mapv #(egg-dto % agora) eggs-l)
     :incubators (mapv incubator-dto (:incubators estado []))
     :prontos (count (filter #(contains? #{:ready-to-hatch :delivery-pending}
                                         (:state %))
                             eggs-l))
     :pending-egg-grants (count pending)
     :pending-incubator-grants (mapv pending-incubator-dto pending-inc)
     :pending-incubator-count (count pending-inc)
     :raid-wins (long (:raid-wins estado 0))
     :temporary-slots-free (eggs/free-temporary-slots estado)
     :incubator-rewards-unlocked unlocked
     :incubator-reward-catalog (incubator-reward-catalog-dto)
     :pi-daily {:day (:day pi-day)
                :points pi-used
                :cap pi-cap
                :restante (max 0 (- pi-cap pi-used))}
     :categorias (into {}
                       (map (fn [[k v]]
                              [(name k) {:tempo-ms (:tempo-ms v)
                                         :pontos (:pontos v)}])
                            eggs/categorias))
     :atividades
     [{:id "caca" :label "Caça concluída" :pi 5}
      {:id "vitoria-selvagem" :label "Vitória selvagem" :pi 8}
      {:id "ginasio-vitoria" :label "Vitória no ginásio" :pi 15}
      {:id "pvp-vitoria" :label "Vitória PvP" :pi 12}
      {:id "raid" :label "Raid vencida" :pi "20–45"}
      {:id "missao" :label "Missão diária" :pi 20}]}))

(defn shop-catalog
  "Catálogo à venda (preços canônicos) + saldo do save ativo."
  [store chat-id player-id]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        save (when slot (ports/get-save store chat-id player-id slot))
        inv (when slot (ports/list-inventory store chat-id player-id slot))
        collection (when slot (ports/list-collection store chat-id player-id slot))
        cap (when save (shop/capacidade-mochila-save save))
        storage (storage-dto save collection)
        occ (shop/ocupacao-mochila (or inv []))]
    (cond-> {:chat-id chat-id
             :player-id player-id
             :active-save-id slot
             :fence-generation (:fence-generation player 0)
             :coins (long (:coins save 0))
             :capacidade-mochila cap
             :ocupacao-mochila occ
             :livres (when cap (max 0 (- (long cap) occ)))
             :itens (mapv (fn [it]
                            {:item-id (:item-id it)
                             :nome (:nome it)
                             :emoji (:emoji it)
                             :preco (:preco it)
                             :descricao (:descricao it)
                             :expansao (boolean (:expansao? it))})
                          (shop-app/catalog))}
      storage (merge {:capacidade-pokemon (:capacidade storage)
                      :ocupacao-pokemon (:ocupacao storage)
                      :livres-pokemon (:livres storage)
                      :excesso-pokemon (:excesso storage)
                      :colecao-cheia (:cheio? storage)
                      :expansoes-pokemon-50 (:expansoes-pokemon-50 storage)
                      :economia-confiavel (shop/economia-confiavel? save)}))))

(defn inventory-bag
  "Inventário do save ativo + capacidade da mochila."
  [store chat-id player-id]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        save (when slot (ports/get-save store chat-id player-id slot))
        inv (when slot (ports/list-inventory store chat-id player-id slot))
        cap (when save (shop/capacidade-mochila-save save))
        occ (shop/ocupacao-mochila (or inv []))
        linhas (mapv (fn [{:keys [item-id qty]}]
                       (let [meta (or (shop/item item-id) {})]
                         {:item-id item-id
                          :qty (long qty)
                          :nome (:nome meta item-id)
                          :emoji (:emoji meta "📦")}))
                     (filter #(pos? (long (:qty % 0))) (or inv [])))]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id slot
     :fence-generation (:fence-generation player 0)
     :coins (long (:coins save 0))
     :capacidade-mochila cap
     :ocupacao-mochila occ
     :livres (when cap (max 0 (- (long cap) occ)))
     :expansoes-mochila-50 (long (:expansoes-mochila-50 save 0))
     :itens linhas}))
