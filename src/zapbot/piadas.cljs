(ns zapbot.piadas
  "Comando !piada - piadas curtas em português.")

(def ^:private piadas
  ["Por que o livro de matemática se sentiu triste? Porque tinha muitos problemas."
   "O que o zero disse para o oito? Belo cinto!"
   "Por que o computador foi ao médico? Porque estava com vírus."
   "O que é um TCP/IP quando está bêbado? Um SUCA/BOSTA/PORRA/IP."
   "Por que a galinha atravessou a rua? Para fugir do Bino."
   "O que o pato disse pra namorada? Vem quá!"
   "Por que o Batman não pesca? Porque o Robin (robim) sempre come as iscas."
   "O que é um cachorro sem pernas? Não importa, ele não vem."
   "Por que o esqueleto não brigou com ninguém? Porque não tinha estômago para isso."
   "O que a impressora disse para a outra impressora? Essa folha é sua ou é impressão minha?"
   "Por que os pássaros voam para o sul no inverno? Porque é longe demais para ir a pé."
   "O que um oceano disse para o outro? Nada, eles só acenaram."
   "Por que o programador confundiu Halloween com o Natal? Porque Oct 31 == Dec 25."
   "O que uma parede disse para a outra? Te encontro na esquina!"
   "Por que o café foi preso? Porque estava sendo espresso demais."
   "Por que o livro de geografia foi ao psicólogo? Porque tinha muitos problemas de identidade... territorial."
   "Como se chama um cachorro mágico? Labracadabrador."
   "Por que a bicicleta não conseguia ficar em pé sozinha? Porque estava exausta (two-tired)."
   "O que o jacaré disse quando viu a namorada de biquíni novo? Ihh vou te comer!"
   "Por que a abelha estava com o cabelo grudento? Porque usou mel de cabelo."
   "Qual é o contrário de volátil? Vem, Tântil!"
   "O que o tomate foi fazer no banco? Tirar extrato."
   "Por que o número 6 tem medo do número 7? Porque 7, 8 (comeu), 9!"
   "Qual o cúmulo da sorte? Cair de um prédio e capotar num colchão de ar."
   "Por que o fantasma entrou no bar? Para pedir um Boo-hemia."
   "O que o girassol disse pro sol? Nossa, você brilha muito, mas eu que dou as caras."
   "Por que a Terra terminou o namoro com o Sol? Porque ele precisava de espaço."])

(defn piada-aleatoria
  "Retorna uma piada aleatória da lista, com o cabeçalho de destaque."
  []
  (str "🤡 *Piada da vez:*\n\n" (rand-nth piadas)))
