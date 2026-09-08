(ns zapbot.status
  "Comando !status - consumo de CPU, memória, disco e uptime da VM/bot."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            ["os" :as os]
            ["fs" :as fs]))

(def ^:private versao-app
  (try
    (.-version (js/require "../package.json"))
    (catch :default _ "desconhecida")))

(def ^:private changelog-ultima-versao
  [(str "Escalação por vírgulas, como na Joy: " config/prefix "pokemon liga time 1,3,5")
   "Ligas Pokémon por nível: Iniciante, Bronze, Prata, Ouro e Diamante"
   (str "Escolha sua liga e salve um time de três com " config/prefix "pokemon liga")
   "PvP 3 × 3: próximo Pokémon entra após um nocaute; adversários da mesma liga e com níveis próximos"
   "Pokémon que ultrapassa a faixa sai da escalação e precisa ser substituído"])


(defn- formatar-changelog []
  (str/join "\n" (map #(str "• " %) changelog-ultima-versao)))

(defn- fmt-num [n] (.toFixed n 2))
(defn- fmt-pct [n] (str (.toFixed n 1) "%"))
(defn- fmt-gb [bytes] (str (.toFixed (/ bytes 1024 1024 1024) 2) " GB"))

(defn- fmt-uptime [segundos]
  (let [segundos (js/Math.floor segundos)
        dias     (quot segundos 86400)
        horas    (quot (mod segundos 86400) 3600)
        minutos  (quot (mod segundos 3600) 60)]
    (str/trim (str (when (pos? dias) (str dias "d ")) horas "h " minutos "m"))))

(defn- cpu-totais
  "Soma os ticks (user/nice/sys/idle/irq) de todos os núcleos - os.cpus()
  só dá acumulados desde o boot, não um valor instantâneo."
  []
  (reduce (fn [{:keys [total idle]} cpu]
            (let [t    (.-times cpu)
                  soma (+ (.-user t) (.-nice t) (.-sys t) (.-idle t) (.-irq t))]
              {:total (+ total soma) :idle (+ idle (.-idle t))}))
          {:total 0 :idle 0}
          (.cpus os)))

(defn- medir-uso-cpu
  "Amostra os ticks duas vezes com um intervalo curto pra calcular o % de
  uso 'agora' (a diferença entre as duas amostras)."
  []
  (p/create
    (fn [resolve _reject]
      (let [antes (cpu-totais)]
        (js/setTimeout
          (fn []
            (let [depois      (cpu-totais)
                  delta-total (- (:total depois) (:total antes))
                  delta-idle  (- (:idle depois) (:idle antes))]
              (resolve (if (pos? delta-total)
                         (* 100 (- 1 (/ delta-idle delta-total)))
                         0))))
          300)))))

(defn- disco []
  (try
    (let [stats (.statfsSync fs "/")
          bloco (.-bsize stats)
          total (* bloco (.-blocks stats))
          livre (* bloco (.-bavail stats))
          usado (- total livre)]
      (str (fmt-gb usado) " / " (fmt-gb total) " (" (fmt-pct (* 100 (/ usado total))) ")"))
    (catch :default _ "indisponível")))

(defn status-vm
  "Retorna uma promise com versão, changelog e status da VM/bot."
  []
  (-> (p/let [uso-cpu (medir-uso-cpu)]
        (let [mem-total (.totalmem os)
              mem-livre (.freemem os)
              mem-usada (- mem-total mem-livre)
              cpus      (.cpus os)
              load      (.loadavg os)]
          (str "📊 *Status da VM (tio " config/bot-name "):*\n\n"
               "🏷️ *Versão do app:* " versao-app "\n"
               "📋 *Novidades desta versão:*\n" (formatar-changelog) "\n\n"
               "🧠 *CPU:* " (count cpus) " núcleos, uso agora ~" (fmt-pct uso-cpu) "\n"
               "   load avg (1/5/15 min): " (fmt-num (aget load 0)) " / "
               (fmt-num (aget load 1)) " / " (fmt-num (aget load 2)) "\n"
               "💾 *Memória:* " (fmt-gb mem-usada) " / " (fmt-gb mem-total)
               " (" (fmt-pct (* 100 (/ mem-usada mem-total))) ")\n"
               "💿 *Disco:* " (disco) "\n"
               "⏱️ *Uptime da VM:* " (fmt-uptime (.uptime os)) "\n"
               "🤖 *Uptime do bot:* " (fmt-uptime (.uptime js/process)))))
      (p/catch (fn [err]
                 (js/console.error "Erro ao buscar status da VM:" err)
                 "❌ Não consegui obter o status da VM agora."))))
