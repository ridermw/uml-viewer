(ns uml-viewer.application.overlay
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.domain.config :as config]
            [uml-viewer.domain.ir :as ir]))

(defn- read-edn [f]
  (when (and f (.exists (io/file f)))
    (edn/read-string (slurp f))))

(defn load-crap
  ([root] (load-crap root ".metrics/crap.edn"))
  ([root rel]
   (let [data (read-edn (io/file root rel))]
     (group-by :namespace (or (:entries data) [])))))

(defn- mutate-files [root]
  (let [dir (io/file root ".metrics" "mutate")]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".edn"))))))

(defn- ns-from-source [src]
  (when src
    (-> src
        (str/replace #"\\" "/")
        (str/replace #"^src/" "")
        (str/replace #"\.[^.]+$" "")
        (str/replace #"/" ".")
        (str/replace #"_" "-"))))

(defn load-mutate
  "One snapshot per namespace. When a dotted file and a path-under-src
  file both name that namespace, the newer file wins."
  [root]
  (reduce (fn [acc f]
            (if-let [data (read-edn f)]
              (let [ns-name (or (:namespace data) (ns-from-source (:source data)))]
                (cond-> acc ns-name (assoc ns-name data)))
              acc))
          {}
          (sort-by #(.lastModified %) (or (mutate-files root) []))))

(defn metrics-root
  "Directory that contains `.metrics`, walking up from `path` (file or dir).
  Falls back to user.dir when none is found."
  [path]
  (loop [dir (let [f (io/file path)]
               (cond
                 (nil? path) (io/file (System/getProperty "user.dir"))
                 (.isFile f) (.getParentFile (.getCanonicalFile f))
                 :else (.getCanonicalFile f)))]
    (cond
      (nil? dir) (System/getProperty "user.dir")
      (.isDirectory (io/file dir ".metrics")) (.getPath dir)
      :else (recur (.getParentFile dir)))))

(defn load-metrics
  ([] (load-metrics (System/getProperty "user.dir")))
  ([root]
   {:crap (or (load-crap root) {})
    :mutate (or (load-mutate root) {})}))

(defn metrics-stamp
  "Fingerprint of `.metrics` snapshot files (CRAP and mutation)."
  [root]
  (let [crap (io/file root ".metrics" "crap.edn")
        files (cond-> []
                (.isFile crap) (conj crap)
                true (into (or (mutate-files root) [])))]
    (->> files
         (map (fn [f] [(.getPath f) (.lastModified f) (.length f)]))
         sort
         vec)))

(defn- form-name [id]
  (when id
    (cond
      (str/starts-with? id "defn-/") {:name (subs id 6) :private true}
      (str/starts-with? id "defn/") {:name (subs id 5) :private false}
      :else nil)))

(defn- counted-sites
  "Site total from the snapshot. Older files omit `:sites` and record
  only killed, survived, and uncovered."
  [form]
  (or (:sites form)
      (+ (or (:killed form) 0)
         (or (:survived form) 0)
         (or (:uncovered form) 0))))

(defn- dedupe-forms [forms]
  (let [seen (atom #{})]
    (filterv (fn [form]
               (let [k [(:file form) (:id form) (:line form) (:hash form)]]
                 (if (contains? @seen k)
                   false
                   (do (swap! seen conj k) true))))
             forms)))

(defn- mutate-by-fn [snapshot]
  (let [forms (dedupe-forms (or (:forms snapshot) []))
        snap-incomplete? (boolean (:incomplete snapshot))]
    (reduce (fn [acc form]
              (if-let [n (form-name (:id form))]
                (let [nm (:name n)
                      prev (get acc nm)
                      killed (+ (or (:killed prev) 0) (or (:killed form) 0))
                      survived (+ (or (:survived prev) 0) (or (:survived form) 0))
                      uncovered (+ (or (:uncovered prev) 0) (or (:uncovered form) 0))
                      sites (+ (or (:sites prev) 0) (counted-sites form))
                      invalid (+ (or (:invalid prev) 0) (or (:invalid form) 0))
                      errors (+ (or (:errors prev) 0) (or (:errors form) 0))
                      incomplete (boolean (or (:incomplete prev) (:incomplete form) snap-incomplete?))
                      priv (boolean (or (:private prev) (:private n)))]
                  (assoc acc nm
                         (cond-> {:name nm :private priv
                                  :killed killed :survived survived
                                  :uncovered uncovered :sites sites}
                           (pos? invalid) (assoc :invalid invalid)
                           (pos? errors) (assoc :errors errors)
                           incomplete (assoc :incomplete true))))
                acc))
            {}
            forms)))

(defn class-namespace
  "Snapshot namespace for a class: authored or generated `:ns`."
  [c]
  (when-let [ns-name (:ns c)]
    (str ns-name)))

(defn- normalize-ns
  "Crapper separates Rust with `::` and every other language with `.`."
  [s]
  (when s
    (str/replace (str s) #"::" ".")))

(defn- prefix-name [prefix]
  (cond
    (nil? prefix) nil
    (keyword? prefix) (name prefix)
    :else (not-empty (str prefix))))

(defn- sole-rust-root
  "The one Rust crate class, when the tree has a single undotted Rust id."
  [classes]
  (let [roots (filterv (fn [c]
                         (and (= :rust (:lang c))
                              (not (:foreign c))
                              (when-let [id (some-> (:id c) name)]
                                (not (str/includes? id ".")))))
                       classes)]
    (when (= 1 (count roots))
      (first roots))))

(defn- claim-rank
  "Sort key when `c` should display snapshot `snap`, or nil.
  Exact `:ns` wins, then longest canonical `:ns` ancestor (`Demo.Board` owns
  `Demo.Board.Inner`), then the class id (`bookwriter.model` owns `model`),
  then a dotted child of `:id` (`pdf` owns `pdf.Layout`). The policy prefix,
  which is the Cargo package name, belongs to the Rust crate root."
  [c prefix rust-root snap]
  (let [ns-name (class-namespace c)
        id-name (some-> (:id c) name)]
    (cond
      (= ns-name snap) [0 0]
      (and ns-name (str/starts-with? snap (str ns-name ".")))
      [1 (- (count ns-name))]
      (= id-name snap) [2 0]
      (and id-name (str/starts-with? snap (str id-name ".")))
      [3 (- (count id-name))]
      (and rust-root (= c rust-root) prefix (= snap prefix)) [4 0]
      :else nil)))

(defn- snapshot-owner
  "Project class that displays snapshot namespace `ns-name`."
  [classes prefix ns-name]
  (let [snap (normalize-ns ns-name)
        prefix (prefix-name prefix)
        rust-root (sole-rust-root classes)]
    (->> classes
         (remove :foreign)
         (keep (fn [c]
                 (when-let [rank (claim-rank c prefix rust-root snap)]
                   [rank c])))
         (sort-by first)
         first
         second)))

(defn- owners-by-ns
  "Class `:ns` to the snapshot namespaces that class displays."
  [classes prefix snapshot-nss]
  (reduce (fn [acc snap]
            (if-let [c (snapshot-owner classes prefix snap)]
              (let [k (class-namespace c)]
                (if k
                  (update acc k (fnil conj []) snap)
                  acc))
              acc))
          {}
          snapshot-nss))

(defn- merge-mutate [snapshots]
  (let [snaps (vec (keep identity snapshots))]
    (when (seq snaps)
      (cond-> {:forms (vec (mapcat #(or (:forms %) []) snaps))}
        (some :incomplete snaps) (assoc :incomplete true)))))

(defn- pct->ratio [cov]
  (when (number? cov)
    (/ (double cov) 100.0)))

(defn- class-crap [scores]
  (config/summarize-scores scores))

(defn- aggregate-crap-fns [fns]
  (when (seq fns)
    (if (= 1 (count fns))
      (first fns)
      (let [craps (keep :crap fns)
            ccs (keep :complexity fns)
            covs (keep :coverage fns)]
        (cond-> (first fns)
          (seq ccs) (assoc :complexity (apply max ccs))
          (seq craps) (assoc :crap (apply max craps))
          (seq covs) (assoc :coverage (/ (reduce + covs) (count covs))))))))

(defn- overlay-op [op crap-fn mut-fn]
  (cond-> (assoc op :text (or (:text op) (:name op)))
    crap-fn (assoc :cc (:complexity crap-fn)
                   :crap (:crap crap-fn)
                   :coverage (pct->ratio (:coverage crap-fn)))
    mut-fn (assoc :killed (or (:killed mut-fn) 0)
                  :survived (or (:survived mut-fn) 0)
                  :uncovered (or (:uncovered mut-fn) 0)
                  :sites (counted-sites mut-fn))
    (and mut-fn (pos? (or (:invalid mut-fn) 0))) (assoc :invalid (:invalid mut-fn))
    (and mut-fn (pos? (or (:errors mut-fn) 0))) (assoc :errors (:errors mut-fn))
    (and mut-fn (:incomplete mut-fn)) (assoc :incomplete true)
    (or (:private op) (:private mut-fn)) (assoc :private true)))

(defn- ops-for-class [c crap-fns mut-fns]
  (let [by-name (into {} (map (fn [[nm fs]] [nm (aggregate-crap-fns fs)])
                              (group-by :name crap-fns)))
        names (distinct (concat (map :name (:ops c))
                                (map :name crap-fns)
                                (keys mut-fns)))]
    (mapv (fn [nm]
            (let [existing (first (filter #(= nm (:name %)) (:ops c)))
                  base (or existing {:name nm :text nm})]
              (overlay-op base (get by-name nm) (get mut-fns nm))))
          names)))

(defn overlay-class
  ([c crap-by-ns mutate-by-ns]
   (overlay-class c crap-by-ns mutate-by-ns nil))
  ([c crap-by-ns mutate-by-ns owned]
   (let [nss (if (nil? owned) [(class-namespace c)] owned)
         crap-fns (vec (mapcat #(or (get crap-by-ns %) []) nss))
         merged-mut (merge-mutate (map #(get mutate-by-ns %) nss))
         mut-fns (mutate-by-fn merged-mut)
         scores (keep :crap crap-fns)
         coverages (keep :coverage crap-fns)
         ops (ops-for-class c crap-fns mut-fns)
         killed (apply + 0 (keep :killed (vals mut-fns)))
         survived (apply + 0 (keep :survived (vals mut-fns)))
         uncovered (apply + 0 (keep :uncovered (vals mut-fns)))
         sites (apply + 0 (map counted-sites (vals mut-fns)))
         invalid (apply + 0 (keep :invalid (vals mut-fns)))
         errors (apply + 0 (keep :errors (vals mut-fns)))
         incomplete (boolean (or (:incomplete merged-mut) (some :incomplete (vals mut-fns))))]
    (cond-> c
      (seq scores) (assoc :crap (class-crap scores)
                          :cc (apply + (map :complexity crap-fns)))
      (seq coverages) (assoc :coverage (pct->ratio
                                         (/ (reduce + coverages) (count coverages))))
      (seq mut-fns) (assoc :killed killed :survived survived :uncovered uncovered
                           :sites sites)
      (pos? invalid) (assoc :invalid invalid)
      (pos? errors) (assoc :errors errors)
      incomplete (assoc :incomplete true)
      (seq ops) (assoc :ops ops)))))

(defn- snapshot-names [metrics]
  (distinct (concat (keys (:crap metrics)) (keys (:mutate metrics)))))

(defn- paint-class [c metrics owners]
  (overlay-class c (:crap metrics) (:mutate metrics)
                 (get owners (class-namespace c) [])))

(defn- paint-packages [packages metrics owners]
  (mapv (fn [p]
          (update p :classes
                  (fn [cs]
                    (mapv #(paint-class % metrics owners) cs))))
        packages))

(defn- paint-diagram [d metrics owners]
  (ir/normalize (update d :packages #(paint-packages % metrics owners))))

(defn- doc-classes [doc]
  (cond
    (:hierarchical doc) (:classes doc)
    (:diagrams doc) (mapcat (fn [d] (mapcat :classes (:packages d))) (:diagrams doc))
    :else (mapcat :classes (:packages doc))))

(defn apply-metrics
  [doc metrics]
  (if (and (empty? (:crap metrics)) (empty? (:mutate metrics)))
    doc
    (let [owners (owners-by-ns (doc-classes doc)
                               (:prefix doc)
                               (snapshot-names metrics))]
      (cond
        (:hierarchical doc)
        (update doc :classes #(mapv (fn [c] (paint-class c metrics owners)) %))
        (:diagrams doc)
        (update doc :diagrams #(mapv (fn [d] (paint-diagram d metrics owners)) %))
        (:packages doc)
        (paint-diagram doc metrics owners)
        :else doc))))
