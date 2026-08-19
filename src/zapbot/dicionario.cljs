(ns zapbot.dicionario
  "Comando !defina/!definir - significado de uma palavra via Dicionário
  Aberto (api.dicionario-aberto.net, grátis, sem chave; texto de domínio
  público, então tem grafias/entradas mais antigas ou europeias às vezes,
  mas cobre bem o vocabulário comum)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]))

(defn- limpar-tags [texto]
  (str/replace texto #"<[^>]+>" ""))

(defn- extrair-defs [xml]
  (->> (re-seq #"<def>([\s\S]*?)</def>" xml)
       (map (fn [[_ conteudo]] (limpar-tags conteudo)))))

(defn- extrair-classe [xml]
  (when-let [[_ classe] (re-find #"<gramGrp>([\s\S]*?)</gramGrp>" xml)]
    (str/trim (limpar-tags classe))))

(defn- linhas-do-def [texto]
  (->> (str/split texto #"\r?\n")
       (map str/trim)
       (remove str/blank?)))

;; só mostra o 1º bloco de definição (sentido principal) da 1ª entrada (no
;; caso de homônimos, tipo "manga" = fruta/manga de camisa/pastagem) e no
;; máximo esse tanto de linhas dele - mantém a resposta curta pro chat em
;; vez de despejar o verbete inteiro do dicionário
(def ^:private max-linhas 5)

(defn- formatar-entrada [palavra xml]
  (let [classe    (extrair-classe xml)
        linhas    (linhas-do-def (or (first (extrair-defs xml)) ""))
        parte     (take max-linhas linhas)
        truncado? (> (count linhas) max-linhas)]
    (str "📚 *" palavra "*" (when classe (str " (" classe ")")) "\n\n"
         (if (seq parte)
           (str (str/join "\n" (map-indexed #(str (inc %1) ". " %2) parte))
                (when truncado? "\n_(mais significados omitidos)_"))
           "(sem definição detalhada disponível)"))))

(defn buscar-definicao
  "!defina/!definir <palavra> - mostra o significado de uma palavra. Só
  considera a 1ª palavra do texto (ignora o resto, se vier uma frase)."
  [entrada]
  (let [palavra (str/lower-case (str/trim (or (first (str/split (or entrada "") #"\s+")) "")))]
    (if (str/blank? palavra)
      (p/resolved (str "❓ Use " config/prefix "defina <palavra> pra ver o significado."))
      (-> (js/fetch (str "https://api.dicionario-aberto.net/word/" (js/encodeURIComponent palavra)))
          (p/then (fn [res] (.json res)))
          (p/then (fn [dados]
                    (let [lista (js->clj dados :keywordize-keys true)]
                      (if-let [primeira (first lista)]
                        (formatar-entrada palavra (:xml primeira))
                        (str "❓ Não encontrei \"" palavra "\" no dicionário. Verifique a grafia ou tente outra palavra.")))))
          (p/catch (fn [err]
                     (js/console.error "Erro ao buscar definição:" err)
                     "❌ Não consegui buscar a definição agora. Tente novamente mais tarde."))))))
