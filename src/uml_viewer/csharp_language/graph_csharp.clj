(ns uml-viewer.csharp-language.graph-csharp
  "Native lexical C# LanguageGraph adapter.

  Emits one class node per top-level type (class, struct, record, interface,
  enum), merging partial declarations while preserving per-operation source
  file and line provenance. Operations follow the body-bearing-method scope."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph])
  (:import [java.io File]))

(def ^:private generated-suffixes
  [".g.cs" ".g.i.cs" ".generated.cs" ".designer.cs"])

(def ^:private cs-build-dirs #{"bin" "obj" "TestResults"})

(def ^:private skip-dirs
  #{".git" ".hg" ".svn" ".idea" ".venv" "venv" "node_modules" "vendor"
    "target" "dist" "build" "out" "coverage" ".metrics" "testdata"
    "__pycache__" ".worktrees" "test" "tests" "spec" "specs" "__tests__"})

(def ^:private test-packages
  #{"microsoft.net.test.sdk" "nunit" "xunit" "xunit.v3" "mstest" "mstest.testframework"})

(defn- cs-test-folder-name? [nm]
  (let [parts (set (str/split (str nm) #"\."))]
    (or (str/ends-with? (str nm) "Tests")
        (some parts #{"Test" "Tests" "Specs"}))))

(defn- csproj-is-test? [^File csproj]
  (try
    (let [text (slurp csproj)
          ;; Strip XML comments before scanning tags
          clean (str/replace text #"(?s)<!--.*?-->" "")]
      (boolean
        (or (re-find #"(?i)<IsTestProject\s*>\s*true\s*</IsTestProject>" clean)
            (re-find #"(?i)Sdk\s*=\s*[\"'][^\"']*MSTest\.Sdk" clean)
            (re-find #"(?i)<Sdk\b[^>]*Name\s*=\s*[\"'][^\"']*MSTest\.Sdk" clean)
            (some (fn [[_ pkg]] (contains? test-packages (str/lower-case (str/trim pkg))))
                  (re-seq #"(?i)<PackageReference\b[^>]*Include\s*=\s*[\"']([^\"']+)[\"']" clean)))))
    (catch Exception _ false)))

(defn- nearest-csproj-dir [^File dir]
  (loop [^File cur dir]
    (when cur
      (let [projs (filter #(and (.isFile ^File %) (str/ends-with? (str/lower-case (.getName ^File %)) ".csproj"))
                          (or (.listFiles cur) []))]
        (if (seq projs)
          [cur projs]
          (recur (.getParentFile cur)))))))

(defn- cs-excluded? [^File file ^File root]
  (let [lname (str/lower-case (.getName file))]
    (cond
      (some #(str/ends-with? lname %) generated-suffixes) true
      :else
      (if-let [[^File proj-dir projs] (nearest-csproj-dir (.getParentFile (.getCanonicalFile file)))]
        (let [rel-to-proj (str/replace (subs (.getCanonicalPath file)
                                             (inc (count (.getCanonicalPath proj-dir))))
                                       #"\\" "/")
              segs (butlast (str/split rel-to-proj #"/"))]
          (or (some cs-build-dirs segs)
              (cs-test-folder-name? (.getName proj-dir))
              (some csproj-is-test? projs)))
        (let [rel (str/replace (subs (.getCanonicalPath file)
                                     (min (count (.getCanonicalPath file))
                                          (inc (count (.getCanonicalPath root)))))
                               #"\\" "/")
              segs (butlast (str/split rel #"/"))]
          (or (some cs-build-dirs segs)
              (some cs-test-folder-name? segs)))))))

(defn- cs-files [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (if (.isFile root-file)
      (if (str/ends-with? (str/lower-case (.getName root-file)) ".cs")
        [root-file]
        [])
      (let [acc (atom [])]
        (letfn [(walk [^File dir]
                  (doseq [^File child (sort-by #(.getName ^File %) (or (.listFiles dir) []))]
                    (let [nm (.getName child)]
                      (cond
                        (.isDirectory child)
                        (when-not (or (contains? skip-dirs nm) (contains? cs-build-dirs nm))
                          (walk child))
                        (and (.isFile child)
                             (str/ends-with? (str/lower-case nm) ".cs")
                             (not (cs-excluded? child root-file)))
                        (swap! acc conj child)))))]
          (when (.isDirectory root-file) (walk root-file)))
        @acc))))

(defn mask-csharp
  "Replace comments and string/char literals with spaces while preserving
  newlines and byte/char offsets."
  [^String src]
  (let [n (count src)
        sb (StringBuilder. n)]
    (letfn [(append-blank [start end]
              (dotimes [k (- end start)]
                (let [ch (.charAt src (+ start k))]
                  (.append sb (if (= ch \newline) \newline \space)))))]
      (loop [i 0]
        (if (>= i n)
          (.toString sb)
          (let [c (.charAt src i)
                nxt (graph/next-char src i)
                nxt2 (when (< (+ i 2) n) (.charAt src (+ i 2)))]
            (cond
              ;; Line comment
              (graph/line-comment? c nxt)
              (recur (graph/mask-line-comment src sb i n))

              ;; Block comment
              (graph/block-comment? c nxt)
              (recur (graph/mask-block-comment src sb i n))

              ;; Raw string literal """...""" (including $"""...""")
              (and (= c \") (= nxt \") (= nxt2 \"))
              (let [quotes (loop [k i] (if (and (< k n) (= (.charAt src k) \")) (recur (inc k)) k))
                    qlen (- quotes i)
                    closer (apply str (repeat qlen \"))
                    end-idx (str/index-of src closer quotes)
                    end (if end-idx (+ end-idx qlen) n)]
                (append-blank i end)
                (recur end))

              ;; Verbatim string @"..." or $@"..." / @$"..."
              (or (and (= c \@) (= nxt \"))
                  (and (= c \$) (= nxt \@) (= nxt2 \"))
                  (and (= c \@) (= nxt \$) (= nxt2 \")))
              (let [open-q (if (= nxt \") (+ i 2) (+ i 3))
                    end (loop [j open-q]
                          (cond
                            (>= j n) n
                            (= (.charAt src j) \")
                            (if (and (< (inc j) n) (= (.charAt src (inc j)) \"))
                              (recur (+ j 2))
                              (inc j))
                            :else (recur (inc j))))]
                (append-blank i end)
                (recur end))

              ;; Interpolated string $"..."
              (and (= c \$) (= nxt \"))
              (let [end (graph/scan-quoted src (inc i) \" n)]
                (append-blank i end)
                (recur end))

              ;; Normal string or char literal
              (or (= c \") (= c \'))
              (let [end (graph/scan-quoted src i c n)]
                (append-blank i end)
                (recur end))

              :else
              (do (.append sb c)
                  (recur (inc i))))))))))

(defn- matching-brace [^String masked open-idx]
  (let [n (count masked)]
    (loop [i (inc open-idx) depth 1]
      (cond
        (>= i n) n
        (= (.charAt masked i) \{) (recur (inc i) (inc depth))
        (= (.charAt masked i) \}) (if (= depth 1) i (recur (inc i) (dec depth)))
        :else (recur (inc i) depth)))))

(defn- line-at [^String src idx]
  (inc (count (re-seq #"\n" (subs src 0 (min idx (count src)))))))

(defn- extract-usings [^String masked]
  (->> (re-seq #"(?m)^\s*(?:global\s+)?using\s+(?!static\b)(?:[A-Za-z_]\w*\s*=\s*)?([A-Za-z_][\w.]*)\s*;" masked)
       (map second)
       vec))

(defn- extract-file-namespace [^String masked]
  (second (re-find #"(?m)^\s*namespace\s+([A-Za-z_][\w.]*)\s*;" masked)))

(def ^:private type-pat
  #"(?m)(?:^|[;{}])\s*(?:\[[^\]]*\]\s*)*(?:(?:public|internal|private|protected|abstract|sealed|static|partial|readonly|ref|unsafe|new|file)\s+)*(class|struct|interface|enum|record(?:\s+(?:class|struct))?)\s+([A-Za-z_]\w*)(?:\s*<[^>]+>)?(?:\s*\([^)]*\))?(?:\s*:\s*([^{;]+?))?(?:\s+where\s+[^{;]+)?\s*(\{|;)")

(def ^:private method-pat
  #"(?m)^\s*(?:(?:public|internal|private|protected|static|virtual|override|abstract|sealed|async|extern|unsafe|new|partial|readonly)\s+)*(?:[A-Za-z_][\w.<>,\[\]?\s]*?)\s+([A-Za-z_]\w*)(?:\s*<[^>]+>)?\s*\([^;{}]*\)(?:\s+where\s+[^{;]+)?\s*(\{|=>)")

(def ^:private skip-method-names
  #{"if" "for" "foreach" "while" "switch" "catch" "using" "lock" "fixed" "return" "throw" "new" "where" "get" "set" "init" "add" "remove"})

(defn- parse-methods [^String masked body-start body-end type-name rel-file]
  (let [body (subs masked body-start (min body-end (count masked)))
        m (re-matcher method-pat body)
        ops (atom [])]
    (while (.find m)
      (let [nm (.group m 1)
            tok (.group m 2)
            abs-pos (+ body-start (.start m))]
        (when (and nm
                   (not= nm type-name)
                   (not (contains? skip-method-names nm))
                   (contains? #{"{" "=>"} tok))
          (let [ln (line-at masked (+ body-start (.start m 1)))]
            (swap! ops conj {:name nm :text nm :file rel-file :line ln})))))
    @ops))

(defn- parse-bases [base-str]
  (when (and base-str (not (str/blank? base-str)))
    (->> (str/split base-str #",")
         (map (fn [s]
                (-> s
                    (str/replace #"<[^>]*>" "")
                    (str/replace #"\([^)]*\)" "")
                    str/trim)))
         (remove str/blank?)
         vec)))

(defn- enclosing-block-namespaces [^String masked pos]
  (let [m (re-matcher #"(?m)\bnamespace\s+([A-Za-z_][\w.]*)\s*\{" masked)
        found (atom [])]
    (while (.find m)
      (let [open-idx (dec (.end m))
            close-idx (matching-brace masked open-idx)]
        (when (and (< open-idx pos) (< pos close-idx))
          (swap! found conj (.group m 1)))))
    @found))

(defn- top-level-types [^String masked rel-file]
  (let [file-ns (extract-file-namespace masked)
        usings (extract-usings masked)
        m (re-matcher type-pat masked)
        raw (atom [])]
    (while (.find m)
      (let [kind-raw (.group m 1)
            kind (cond
                   (str/starts-with? kind-raw "interface") :interface
                   (str/starts-with? kind-raw "enum") :enum
                   (str/starts-with? kind-raw "struct") :struct
                   (str/starts-with? kind-raw "record") :record
                   :else :class)
            tname (.group m 2)
            bases (parse-bases (.group m 3))
            delim (.group m 4)
            start (.start m)
            open-idx (dec (.end m))
            end-idx (if (= delim "{") (matching-brace masked open-idx) (.end m))
            block-nss (enclosing-block-namespaces masked start)
            ns-parts (cond-> []
                       file-ns (conj file-ns)
                       (seq block-nss) (into block-nss))
            ns-prefix (str/join "." ns-parts)
            full-ns (if (str/blank? ns-prefix) tname (str ns-prefix "." tname))]
        (swap! raw conj {:kind kind
                         :name tname
                         :ns full-ns
                         :ns-prefix ns-prefix
                         :bases bases
                         :usings usings
                         :file rel-file
                         :start start
                         :open open-idx
                         :end end-idx
                         :delim delim})))
    ;; Keep only top-level types (not nested inside another type's braces)
    (let [all @raw]
      (for [t all
            :when (not (some #(and (not= % t)
                                   (= "{" (:delim %))
                                   (< (:open %) (:start t))
                                   (< (:start t) (:end %)))
                             all))]
        (let [ops (if (= "{" (:delim t))
                    (parse-methods masked (inc (:open t)) (:end t) (:name t) rel-file)
                    [])
              body-text (if (= "{" (:delim t))
                          (subs masked (inc (:open t)) (min (:end t) (count masked)))
                          "")]
          (assoc t :ops ops :body body-text))))))

(defn- primary-file [type-name files]
  (let [expected (str/lower-case (str type-name ".cs"))]
    (or (first (filter #(= expected (str/lower-case (or (graph/file-base %) ""))) files))
        (first (sort files)))))

(defn- merge-partials [type-entries prefix]
  (for [[full-ns parts] (group-by :ns type-entries)]
    (let [first-t (first parts)
          tname (:name first-t)
          files (vec (distinct (map :file parts)))
          pfile (primary-file tname files)
          all-ops (vec (mapcat :ops parts))
          by-op-name (group-by :name all-ops)
          merged-ops (mapv (fn [nm]
                             (let [decls (get by-op-name nm)
                                   primary (first decls)
                                   locs (mapv #(select-keys % [:file :line]) decls)]
                               (cond-> {:name nm :text nm
                                        :file (:file primary)
                                        :line (:line primary)}
                                 (> (count locs) 1) (assoc :locations locs))))
                           (distinct (map :name all-ops)))
          stereotype (case (:kind first-t)
                       :interface "interface"
                       :enum "enum"
                       :struct "struct"
                       :record "record"
                       nil)]
      (cond-> {:id (graph/id-of full-ns prefix)
               :name tname
               :ns full-ns
               :lang :csharp
               :file pfile
               :files files
               :ops merged-ops
               :bases (vec (distinct (mapcat :bases parts)))
               :usings (vec (distinct (mapcat :usings parts)))
               :ns-prefix (:ns-prefix first-t)
               :body (str/join "\n" (map :body parts))
               :kind (:kind first-t)}
        stereotype (assoc :stereotype stereotype)))))

(defn- resolve-type-ref [ref-name owner by-ns by-short]
  (or (get by-ns ref-name)
      (when-not (str/blank? (:ns-prefix owner))
        (get by-ns (str (:ns-prefix owner) "." ref-name)))
      (some #(get by-ns (str % "." ref-name)) (:usings owner))
      (let [matches (get by-short ref-name)]
        (when (= 1 (count matches))
          (first matches)))))

(defn scan-csharp [root opts]
  (let [root-file (.getCanonicalFile (io/file root))
        prefix (or (:prefix opts) "")
        project-root (io/file (or (:project-root opts) root-file))
        files (cs-files root-file)
        entries (mapcat (fn [^File f]
                          (let [rel (let [p (str/replace (.getCanonicalPath f) #"\\" "/")
                                          r (str/replace (.getCanonicalPath project-root) #"\\" "/")]
                                      (if (str/starts-with? p (str r "/"))
                                        (subs p (inc (count r)))
                                        (graph/relative-path f)))
                                masked (mask-csharp (slurp f))]
                            (top-level-types masked rel)))
                        files)
        classes (vec (merge-partials entries prefix))
        by-ns (into {} (map (juxt :ns identity) classes))
        by-short (group-by :name classes)
        edges (->> (for [c classes
                         :let [from (:id c)
                               base-edges (keep (fn [b]
                                                  (when-let [target (resolve-type-ref b c by-ns by-short)]
                                                    (when (not= (:id target) from)
                                                      {:from from
                                                       :to (:id target)
                                                       :kind (if (= :interface (:kind target))
                                                               :implements
                                                               :inheritance)})))
                                                (:bases c))
                               base-ids (set (map :to base-edges))
                               tokens (set (re-seq #"\b[A-Z][A-Za-z0-9_]*\b" (or (:body c) "")))
                               dep-edges (keep (fn [tok]
                                                 (when-let [target (resolve-type-ref tok c by-ns by-short)]
                                                   (when (and (not= (:id target) from)
                                                              (not (contains? base-ids (:id target))))
                                                     {:from from :to (:id target) :kind :dependency})))
                                               tokens)]]
                     (concat base-edges dep-edges))
                   (apply concat)
                   distinct
                   vec)
        clean-classes (mapv #(dissoc % :bases :usings :ns-prefix :body :kind) classes)]
    {:classes clean-classes :edges edges}))

(def impl
  (reify graph/LanguageGraph
    (scan [_ root opts] (scan-csharp root opts))))

(graph/register! :csharp impl)
