(ns uml-viewer.web.model
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [uml-viewer.application.document :as document]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.domain.config :as config]
            [uml-viewer.domain.hierarchy :as hierarchy]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.web.paths :as paths])
  (:import [java.time Instant]
           [java.util UUID]))

(def metric-keys [:crap :coverage :cc :killed :survived :uncovered :sites :invalid :errors :incomplete])

(defn wire [value]
  (cond
    (keyword? value) (subs (str value) 1)
    (map? value) (into {} (map (fn [[k v]] [(wire k) (wire v)]) value))
    (or (sequential? value) (set? value)) (mapv wire value)
    :else value))

(defn- revised [state]
  (assoc state :revision (str (UUID/randomUUID)) :loaded-at (str (Instant/now))))

(defn open-document [root diagram]
  (let [root (paths/root! root)
        file (paths/file! root diagram 8388608)
        raw (try
              (let [data (edn/read-string (slurp (str file)))]
                (when-not (map? data)
                  (paths/error! 422 "invalid-diagram" "Diagram must contain an EDN map."))
                (ir/load-document (str file)))
              (catch clojure.lang.ExceptionInfo e
                (if (:status (ex-data e)) (throw e)
                    (paths/error! 422 "invalid-diagram" (.getMessage e))))
              (catch RuntimeException e
                (paths/error! 422 "invalid-diagram" (.getMessage e))))]
    (revised {:root root :diagram file :raw raw :focus []})))

(defn scene
  ([state]
   (document/compile-view (:raw state) (str (:root state)) (:focus state)))
  ([state metrics]
   (document/compile-view (:raw state) (str (:root state)) (:focus state)
                          {:metrics metrics})))

(defn- valid-focus? [state segments]
  (and (vector? segments) (every? string? segments)
       (if-not (:hierarchical (:raw state))
         (empty? segments)
         (loop [path [] remaining segments]
           (if (empty? remaining) true
               (let [part (first remaining)
                     nodes (hierarchy/nodes-at (:classes (:raw state)) path)
                     accepted (some #(when (= part (last (str/split (name %) #"\.")))
                                       (keyword part)) nodes)]
                 (and accepted (recur (conj path accepted) (rest remaining)))))))))

(defn focus-document [state segments]
  (when-not (valid-focus? state segments)
    (paths/error! 400 "invalid-focus" "That component is not reachable in this diagram."))
  (revised (assoc state :focus (mapv keyword segments))))

(defn reload-document [state]
  (let [fresh (open-document (str (:root state)) (str (:diagram state)))
        focus (mapv name (:focus state))]
    (if (valid-focus? fresh focus) (assoc fresh :focus (:focus state)) fresh)))

(defn executed-mutation-score [{:keys [killed survived]}]
  (when (and (some? killed) (some? survived) (pos? (+ killed survived)))
    (/ (double killed) (+ killed survived))))

(defn- metrics [m]
  (merge (zipmap metric-keys (repeat nil)) m))

(defn- measured-hierarchy-class [painted c]
  (let [id (wire (:id c))
        members (filter #(let [candidate (wire (:id %))]
                           (or (= id candidate)
                               (str/starts-with? candidate (str id "."))))
                        (:classes painted))
        measured-crap? (some #(some? (get-in % [:crap :mu])) members)
        measured-mutation? (some #(some (fn [k] (some? (get % k)))
                                       [:killed :survived :uncovered :sites :invalid :errors :incomplete])
                                 (concat members (mapcat :ops members)))]
    ;; Core hierarchy rollups penalize absent measurements; the browser labels them unknown.
    (cond-> c
      (not measured-crap?) (assoc :crap nil)
      (not measured-mutation?) (assoc :killed nil :survived nil :uncovered nil :sites nil
                                      :invalid nil :errors nil :incomplete false))))

(defn- class-dto [c]
  (let [c (reduce (fn [c key]
                    (if (some? (get c key)) c
                        (let [values (keep key (:ops c))]
                          (if (seq values) (assoc c key (reduce + values)) c))))
                  c [:killed :survived :uncovered :sites :invalid :errors])
        incomplete? (boolean (or (:incomplete c) (some :incomplete (:ops c))))
        risk (config/crap-risk (:crap c))
        score (when-not incomplete? (executed-mutation-score c))]
    (-> (metrics c)
        (assoc :package (:package c) :ns (:ns c) :file (:file c) :lang (:lang c)
               :shape (:shape c) :drill? (boolean (:drill? c))
               :incomplete incomplete?
               :fields (mapv metrics (:fields c)) :ops (mapv metrics (:ops c))
               :lines (vec (:lines c))
               :in-ports (vec (:in-ports c)) :out-ports (vec (:out-ports c))
               :crapBand (when risk (config/crap-band risk))
               :mutationBand (when score (config/mutation-band score))))))

(defn snapshot [state]
  (let [metrics (overlay/load-metrics (str (:root state)))
        compiled (scene state metrics)
        painted (when (:hierarchical (:raw state))
                  (overlay/apply-metrics (:raw state) metrics))]
    (wire {:project {:root (str (:root state)) :diagram (str (:diagram state))
                     :title (:title (:raw state))}
           :revision (:revision state) :loadedAt (:loaded-at state)
           :focus (mapv name (:focus state))
           :hierarchical (boolean (:hierarchical (:raw state)))
           :scene {:classes (mapv #(class-dto (if painted (measured-hierarchy-class painted %) %))
                                  (:classes compiled))
                   :packages (vec (:packages compiled))
                   :edges (mapv #(assoc % :label (:label %) :violating (boolean (:violating %))
                                       :strokes (vec (:strokes %))
                                       :points (vec (:points %))
                                       :tip (:tip %) :behind (:behind %)) (:edges compiled))
                   :sections (mapv #(select-keys % [:title :title-y]) (:sections compiled))
                   :size (:size compiled)}})))
