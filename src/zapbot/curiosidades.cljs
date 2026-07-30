(ns zapbot.curiosidades
  "Comando !curiosidade - fatos curiosos em português."
  (:require [zapbot.config :as config]))

(def ^:private curiosidades
  ["O mel nunca estraga: arqueólogos encontraram potes de mel com mais de 3000 anos em tumbas egípcias ainda comestíveis."
   "Um dia em Vênus dura mais que um ano em Vênus: o planeta leva mais tempo para girar em torno do próprio eixo do que para orbitar o Sol."
   "Polvos têm três corações e sangue azul."
   "A Torre Eiffel pode ficar até 15 cm mais alta no verão por causa da dilatação do metal com o calor."
   "Bananas são tecnicamente frutas de baga (berries), mas morangos não são."
   "O coração de um camarão fica na cabeça."
   "Existem mais estrelas no universo observável do que grãos de areia em todas as praias da Terra."
   "Os cornos da girafa se chamam ossicones e já nascem com o filhote, apenas dobrados para facilitar o parto."
   "O som do trovão pode ser ouvido a até 25 km de distância, mas o relâmpago pode ser visto de muito mais longe."
   "A Grande Muralha da China não é visível a olho nu do espaço, ao contrário do mito popular."
   "Formigas não têm pulmões: elas respiram por pequenos orifícios espalhados pelo corpo chamados espiráculos."
   "O Monte Everest cresce cerca de 4 mm por ano devido ao movimento das placas tectônicas."
   "Um raio pode aquecer o ar ao seu redor a até 5 vezes a temperatura da superfície do Sol."
   "Golfinhos dão nomes uns aos outros por meio de assobios únicos (assinaturas sonoras)."
   "O Brasil tem mais fusos horários oficiais (4) do que estados na região Sul (3)."
   "A Antártida é tecnicamente o maior deserto do mundo, por ser a região com menor índice de precipitação."
   "As impressões digitais de um coala são tão parecidas com as humanas que já confundiram perícias criminais."
   "O primeiro e-mail da história foi enviado em 1971 por Ray Tomlinson, que também escolheu o símbolo @ para endereços."
   "Existe um tipo de água-viva (Turritopsis dohrnii) que pode reverter seu próprio envelhecimento e é considerada biologicamente imortal."
   "O Wi-Fi não significa nada: é apenas um nome de marca criado para soar bem, sem sigla por trás."])

(defn curiosidade-aleatoria
  "Retorna uma curiosidade aleatória, com o cabeçalho de destaque."
  []
  (str "🧠 *Curiosidade do tio " config/bot-name ":*\n\n" (rand-nth curiosidades)))
