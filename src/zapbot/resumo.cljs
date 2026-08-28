(ns zapbot.resumo
  "Comando !resuma - resume as últimas mensagens do chat usando a API Gemini."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.historico :as historico]
            [zapbot.gemini :as gemini]))

(defn- comando? [texto]
  (str/starts-with? (str/trim (or texto "")) config/prefix))

(defn- montar-transcricao [mensagens]
  (->> mensagens
       (remove (fn [{:keys [corpo]}] (or (str/blank? corpo) (comando? corpo))))
       (map (fn [{:keys [autor corpo]}] (str autor ": " corpo)))
       (str/join "\n")))

(defn- inicio-do-dia []
  (let [agora (js/Date.)]
    (.setHours agora 0 0 0 0)
    (.getTime agora)))

(defn- inicio-periodo [periodo]
  (let [p (str/lower-case (str/trim (or periodo "")))
        agora (.now js/Date)]
    (cond
      (or (str/blank? p) (= p "tudo")) nil
      (contains? #{"hoje" "dia"} p) (inicio-do-dia)
      (= p "ontem") (- (inicio-do-dia) (* 24 60 60 1000))
      :else (when-let [[_ numero unidade] (re-matches #"(\d+)\s*(m|min|mins|minuto|minutos|h|hora|horas|d|dia|dias)" p)]
              (let [multiplicador (if (contains? #{"m" "min" "mins" "minuto" "minutos"} unidade)
                                    (* 60 1000)
                                    (if (contains? #{"h" "hora" "horas"} unidade)
                                      (* 60 60 1000)
                                      (* 24 60 60 1000)))]
                (- agora (* (js/parseInt numero 10) multiplicador)))))))

(defn- filtrar-periodo [mensagens periodo]
  (let [inicio (inicio-periodo periodo)]
    (cond
      (nil? inicio) mensagens
      (= "ontem" (str/lower-case (str/trim periodo)))
      (filter #(and (>= (or (:em %) 0) inicio)
                    (< (or (:em %) 0) (inicio-do-dia))) mensagens)
      :else (filter #(>= (or (:em %) 0) inicio) mensagens))))

(defn- gerar-resumo [transcricao]
  (-> (gemini/gerar-texto
       (str "Resuma em português, de forma objetiva e em poucos parágrafos, "
            "a conversa de WhatsApp abaixo:\n\n" transcricao))
      (p/then (fn [resumo]
                (if resumo
                  (str "📝 *Resumão do tio " config/bot-name "*\n\n" resumo)
                  "❌ Não consegui gerar o resumo agora.")))
      (p/catch (fn [err]
                 (js/console.error "Erro ao gerar resumo:" err)
                 "❌ Não consegui gerar o resumo agora. Tente novamente mais tarde."))))

(defn resumir-chat
  "Resume o histórico disponível. Períodos aceitos: 30m, 8h, 2d, hoje e ontem."
  [message periodo]
  (if (str/blank? config/gemini-api-key)
    (p/resolved
     (str "⚠️ Comando indisponível: configure GEMINI_API_KEY no .env "
          "(chave gratuita em https://aistudio.google.com/apikey)."))
    (let [periodo-valido? (or (str/blank? (or periodo "")) (= "tudo" (str/lower-case (str/trim periodo))) (inicio-periodo periodo))
          transcricao (montar-transcricao (filtrar-periodo (historico/obter message) periodo))]
      (if-not periodo-valido?
        (p/resolved "❓ Período inválido. Use, por exemplo: !resuma 30m, !resuma 8h, !resuma hoje ou !resuma ontem.")
      (if (str/blank? transcricao)
        (p/resolved
         (str "❓ Ainda não tenho mensagens suficientes desse chat para resumir "
              "(só vejo o que foi enviado depois que eu liguei)."))
        (gerar-resumo transcricao))))))
