(ns zapbot.apuracao
  "Apuração oficial das eleições gerais de 2026 no TSE: presidente no Brasil
  e, por UF, presidente, governador, senadores e deputados."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]))

(def ^:private ufs
  {"ac" "Acre" "al" "Alagoas" "ap" "Amapá" "am" "Amazonas" "ba" "Bahia"
   "ce" "Ceará" "df" "Distrito Federal" "es" "Espírito Santo" "go" "Goiás"
   "ma" "Maranhão" "mt" "Mato Grosso" "ms" "Mato Grosso do Sul"
   "mg" "Minas Gerais" "pa" "Pará" "pb" "Paraíba" "pr" "Paraná"
   "pe" "Pernambuco" "pi" "Piauí" "rj" "Rio de Janeiro"
   "rn" "Rio Grande do Norte" "rs" "Rio Grande do Sul" "ro" "Rondônia"
   "rr" "Roraima" "sc" "Santa Catarina" "sp" "São Paulo" "se" "Sergipe"
   "to" "Tocantins"})

;; O TSE publica o presidente e os cargos estaduais em eleições distintas.
(def ^:private eleicao-presidente "6257")
(def ^:private eleicao-estadual "6259")

(defn- url-resultados [eleicao uf cargo]
  (str "https://resultados.tse.jus.br/oficial/ele2026/" eleicao "/dados/" uf "/"
       uf "-c" cargo "-e00" eleicao "-u.jws"))

(defn- cargos-do-estado [uf]
  [{:nome "Presidente" :eleicao eleicao-presidente :cargo "0001" :top 5}
   {:nome "Governador" :eleicao eleicao-estadual :cargo "0003" :top 5}
   {:nome "Senador" :eleicao eleicao-estadual :cargo "0005" :top 4}
   {:nome "Deputado Federal" :eleicao eleicao-estadual :cargo "0006" :top 5}
   (if (= "df" uf)
     {:nome "Deputado Distrital" :eleicao eleicao-estadual :cargo "0008" :top 5}
     {:nome "Deputado Estadual" :eleicao eleicao-estadual :cargo "0007" :top 5})])

(defn- decodificar-jws [jws]
  (let [[_ payload assinatura] (str/split (str/trim jws) #"\.")]
    (when-not (and payload assinatura)
      (throw (js/Error. "Resposta de apuração inválida.")))
    (js/JSON.parse (.toString (js/Buffer.from payload "base64url") "utf8"))))

(defn- formatar-numero [valor]
  (.toLocaleString (js/Number (or valor 0)) "pt-BR"))

(defn- votos [candidato]
  (js/parseInt (or (:vap candidato) "0") 10))

(defn- segundo-turno? [{:keys [st]}]
  (boolean (re-find #"(?i)^(2º|2o|segundo)\s*turno$" (or st ""))))

;; No TSE, `e` = "s" também vale para quem vai ao 2º turno; `st` desempata.
(defn- eleito? [{:keys [e st] :as candidato}]
  (and (not (segundo-turno? candidato))
       (or (= "s" (str/lower-case (or e "")))
           (boolean (re-find #"(?i)^eleito" (or st ""))))))

(defn- candidatos [cargo]
  (->> (for [agr (:agr cargo) par (:par agr) cand (:cand par)]
         (assoc cand :sg (:sg par)))
       (sort-by (fn [c] [(if (eleito? c) 0 1) (- (votos c))]))))

(defn- formatar-candidato [i candidato]
  (let [{:keys [n nmu nm sg pvap dvt]} candidato]
    (str (inc i) ". *" (or nmu nm) "* (" (when sg (str sg " ")) n "): "
         (formatar-numero (:vap candidato)) " votos — " (or pvap "0,00") "%"
         (when (eleito? candidato) " ✅")
         (when (segundo-turno? candidato) " ➡️ 2º turno")
         (when (and dvt (not= "Válido" dvt)) " (sub judice)"))))

(defn formatar-dados
  "Presidente no Brasil: todos os candidatos e o andamento da totalização."
  [dados]
  (if-let [cargo (first (filter #(= "1" (:cd %)) (:carg dados)))]
    (let [totalizadas (get-in dados [:s :st])
          total-secoes (get-in dados [:s :ts])]
      (str "🗳️ *Apuração presidencial 2026 — 1º turno*\n\n"
           "*Seções totalizadas:* " (formatar-numero totalizadas) "/"
           (formatar-numero total-secoes) " (" (get-in dados [:s :pst] "0,00") "%)\n"
           "*Atualização:* " (:ht dados) " de " (:dt dados) "\n\n"
           (str/join "\n" (map-indexed formatar-candidato (candidatos cargo)))))
    "🗳️ O TSE ainda não disponibilizou a apuração presidencial."))

(defn formatar-cargo
  "Um cargo no estado: título com vagas e andamento, depois os `top` primeiros."
  [dados nome top]
  (if-let [cargo (first (:carg dados))]
    (let [vagas (js/parseInt (or (:nv cargo) "0") 10)
          titulo (str "*" nome "*" (when (> vagas 1) (str " (" vagas " vagas)"))
                      " — " (get-in dados [:s :pst] "0,00") "% das seções · " (:ht dados))]
      (str/join "\n" (cons titulo (map-indexed formatar-candidato
                                               (take top (candidatos cargo))))))
    (str "*" nome "* — sem dados.")))

(defn- buscar-dados [url]
  (p/let [resposta (js/fetch url #js {:signal (.timeout js/AbortSignal 10000)})
          _ (when-not (.-ok resposta)
              (throw (js/Error. (str "TSE HTTP " (.-status resposta)))))
          texto (.text resposta)]
    (js->clj (decodificar-jws texto) :keywordize-keys true)))

(defn- secao-do-estado [uf {:keys [nome eleicao cargo top]}]
  (-> (buscar-dados (url-resultados eleicao uf cargo))
      (p/then #(formatar-cargo % nome top))
      (p/catch (fn [erro]
                 (js/console.error (str "Erro ao buscar " nome " (" uf ") no TSE:") erro)
                 nil))))

(defn- buscar-estado [uf]
  (let [cargos (cargos-do-estado uf)]
    (p/let [secoes (p/all (map #(secao-do-estado uf %) cargos))]
      (if (every? nil? secoes)
        (str "❌ Não consegui buscar a apuração de " (get ufs uf)
             " agora. Tente novamente mais tarde.")
        (str "🗳️ *Apuração 2026 — " (get ufs uf) " (" (str/upper-case uf) ")*\n\n"
             (str/join "\n\n"
                       (map (fn [{:keys [nome]} texto]
                              (or texto (str "*" nome "* — indisponível no momento.")))
                            cargos secoes)))))))

(defn- buscar-brasil []
  (-> (p/let [dados (buscar-dados (url-resultados eleicao-presidente "br" "0001"))]
        (formatar-dados dados))
      (p/catch (fn [erro]
                 (js/console.error "Erro ao buscar apuração do TSE:" erro)
                 "❌ Não consegui buscar a apuração presidencial agora. Tente novamente mais tarde."))))

(defn buscar-apuracao
  "Sem UF (nil) ou BR: presidente no Brasil. Com UF: presidente, governador,
  senadores e deputados do estado."
  [uf-informada]
  (let [uf (str/lower-case (str/trim (or uf-informada "br")))]
    (cond
      (= "br" uf) (buscar-brasil)
      (contains? ufs uf) (buscar-estado uf)
      :else (p/resolved (str "❓ UF inválida. Use a sigla do estado, ex.: " config/prefix
                             "apuracao SP (sem UF, mostra o Brasil).")))))
