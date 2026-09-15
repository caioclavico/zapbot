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
  [(str "15/09 — Ranking de líderes por defesas e permanência: " config/prefix "pokemon ginasio ranking [nome]")
   (str "15/09 — Histórico de defesas: " config/prefix "pokemon ginasio historico [nome]")
   (str "15/09 — Coleção shiny histórica: " config/prefix "pokemon shiny; filtre as fotos com " config/prefix "pokemon time shiny")
   (str "15/09 — Missões semanais com moedas: " config/prefix "pokemon missoes semanais [resgatar]")
   (str "15/09 — Raid cooperativa contra Snorlax, de 2 a 6 jogadores por liga: " config/prefix "pokemon ajuda raid")
   "15/09 — Derrotas no ginásio mostram apenas o resultado e as recompensas, sem os golpes do adversário"
   (str "15/09 — O time reservado no ginásio aparece com fotos, como no " config/prefix "pokemon time. Confira: " config/prefix "pokemon ginasio <nome>")
   "14/09 — Pokémon shiny: chance de 1 em 512 por encontro, visual especial e brilho mantido ao evoluir, sem alterar os atributos"
   "14/09 — Vença um ginásio para se tornar líder! Seus três Pokémon ficam reservados até outro treinador vencer você e depois voltam à coleção"
   "14/09 — Líderes que permanecem mais de 6 horas recebem 50 moedas ao perder o ginásio"
   "14/09 — PE (Pontos de experiência) do treinador nos ginásios: 6 na primeira vitória, 3 na revanche premiada e 1 na derrota"])

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
