(ns zapbot.armazenamento
  "Persistência simples em arquivo JSON (não é um banco de dados de
  verdade, só um arquivo local) pra estado que hoje vive só em memória
  (defonce atom) sobreviver a reinícios do bot/deploys. Lido uma vez na
  inicialização e regravado (arquivo inteiro) a cada chamada de salvar!.

  Convenção: os dados salvos usam só strings como chave (nunca keywords),
  tanto no nível de fora quanto dentro - assim clj->js/js->clj não precisam
  de nenhuma opção especial e o round-trip é sempre exato. Cada namespace
  que usa isso é responsável por converter de/para sua própria estrutura
  interna (ex.: sets viram vetor pra salvar, e voltam a ser set ao carregar)."
  (:require ["fs" :as fs]))

(def ^:private diretorio (str js/__dirname "/../data"))
(def ^:private caminho (str diretorio "/estado.json"))

(defn- garantir-diretorio! []
  (when-not (.existsSync fs diretorio)
    (.mkdirSync fs diretorio #js {:recursive true})))

(defn- carregar []
  (try
    (if (.existsSync fs caminho)
      (js->clj (js/JSON.parse (.readFileSync fs caminho "utf8")))
      {})
    (catch :default err
      (js/console.error "Erro ao ler estado persistido, começando do zero:" err)
      {})))

(defonce ^:private estado (atom (carregar)))

(defn- salvar-em-disco! [dados]
  (try
    (garantir-diretorio!)
    (.writeFileSync fs caminho (js/JSON.stringify (clj->js dados)))
    (catch :default err
      (js/console.error "Erro ao salvar estado em disco:" err))))

(defn obter
  "Lê o valor salvo para a chave (string) do namespace chamador, ou nil se
  não houver nada salvo ainda."
  [chave]
  (get @estado chave))

(defn salvar!
  "Atualiza e persiste (em disco, na hora) o valor salvo para a chave."
  [chave valor]
  (swap! estado assoc chave valor)
  (salvar-em-disco! @estado))
