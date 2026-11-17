(ns zapbot.pokemon.resposta
  "Envelopes e mídia das respostas enviadas pelo adaptador Pokémon."
  (:require [promesa.core :as p]
            [zapbot.desempenho :as desempenho]))

(def ^:private jvm-present (js/require "../scripts/lib/pokemon-jvm-present.cjs"))

(defn erro-contrato [texto]
  (doto (js/Error. texto) (aset "code" "INVALID_RESPONSE")))

(defn envelope-jvm
  "Texto obrigatório; imagem e mentions opcionais. Efeitos vazios (não são TX Datomic)."
  ([pedido texto] (envelope-jvm pedido texto nil nil))
  ([pedido texto imagem] (envelope-jvm pedido texto imagem nil))
  ([pedido texto imagem mentions]
   (let [extras #js {:effects #js []}]
     (when imagem
       (aset extras "image" imagem)
       (aset extras "caption" texto))
     (when (seq mentions)
       (aset extras "mentions" (clj->js (vec mentions))))
     (.envelope jvm-present (:requestId pedido) texto extras))))

(defn append-trainer-level-ups [texto data]
  (.appendTrainerLevelUps jvm-present (str texto) data))

(defn render-seguro!
  "Render de mídia pós-TX. Qualquer falha → nil (nunca reexecuta gameplay)."
  [ctx metrica f]
  (-> (desempenho/medir! ctx metrica f)
      (p/catch (fn [err]
                 (js/console.error (str "mídia JVM (" metrica "):") err)
                 nil))))
