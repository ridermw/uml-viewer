(ns uml-viewer.powershell-language.graph-powershell
  "Native lexical PowerShell LanguageGraph adapter.

  Emits one node per production .ps1/.psm1 file with project-root-relative
  module identity matching crapper and mutator (stripping one leading src/).
  Preserves #Requires -Modules while masking ordinary comments and strings."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph])
  (:import [java.io File]))

(def ^:private skip-dirs
  #{".git" ".hg" ".svn" ".idea" ".venv" "venv" "node_modules" "vendor"
    "target" "dist" "build" "out" "coverage" ".metrics" "testdata"
    "__pycache__" ".worktrees" "test" "tests" "spec" "specs" "__tests__"})

(def ^:private ps-exts [".ps1" ".psm1"])

(defn- ps-file? [^File f]
  (let [lname (str/lower-case (.getName f))]
    (and (.isFile f)
         (some #(str/ends-with? lname %) ps-exts)
         (not (str/ends-with? lname ".tests.ps1")))))

(defn- ps-files [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (if (.isFile root-file)
      (if (ps-file? root-file) [root-file] [])
      (let [acc (atom [])]
        (letfn [(walk [^File dir]
                  (doseq [^File child (sort-by #(.getName ^File %) (or (.listFiles dir) []))]
                    (cond
                      (.isDirectory child)
                      (when-not (contains? skip-dirs (.getName child))
                        (walk child))
                      (ps-file? child)
                      (swap! acc conj child))))]
          (when (.isDirectory root-file) (walk root-file)))
        @acc))))

(defn- module-namespace [rel-path]
  (let [p (if (str/starts-with? rel-path "src/")
            (subs rel-path 4)
            rel-path)
        no-ext (str/replace p #"(?i)\.(?:psm1|ps1)$" "")]
    (str/replace no-ext "/" ".")))

(defn mask-powershell
  "Mask <# ... #> block comments, ordinary # line comments (keeping #Requires),
  and single/double/here strings while preserving newlines and offsets."
  [^String src]
  (let [n (count src)
        sb (StringBuilder. n)]
    (letfn [(blank [start end]
              (dotimes [k (- end start)]
                (let [ch (.charAt src (+ start k))]
                  (.append sb (if (= ch \newline) \newline \space)))))]
      (loop [i 0]
        (if (>= i n)
          (.toString sb)
          (let [c (.charAt src i)
                nxt (graph/next-char src i)]
            (cond
              ;; Block comment <# ... #>
              (and (= c \<) (= nxt \#))
              (let [close (str/index-of src "#>" (+ i 2))
                    end (if close (+ close 2) n)]
                (blank i end)
                (recur end))

              ;; Here-string @" ... "@ or @' ... '@
              (and (= c \@) (or (= nxt \") (= nxt \')))
              (let [closer (str "\n" nxt "@")
                    idx (str/index-of src closer (+ i 2))
                    end (if idx (+ idx (count closer)) n)]
                (blank i end)
                (recur end))

              ;; Line comment, EXCEPT #Requires
              (= c \#)
              (let [eol (or (str/index-of src \newline i) n)
                    line (subs src i eol)]
                (if (re-find #"(?i)^#requires\b" line)
                  (do (.append sb line) (recur eol))
                  (do (blank i eol) (recur eol))))

              ;; Single-quoted string ('' escapes)
              (= c \')
              (let [end (loop [j (inc i)]
                          (cond
                            (>= j n) n
                            (= (.charAt src j) \')
                            (if (and (< (inc j) n) (= (.charAt src (inc j)) \'))
                              (recur (+ j 2))
                              (inc j))
                            :else (recur (inc j))))]
                (blank i end)
                (recur end))

              ;; Double-quoted string (` escapes)
              (= c \")
              (let [end (loop [j (inc i) esc false]
                          (cond
                            (>= j n) n
                            esc (recur (inc j) false)
                            (= (.charAt src j) \`) (recur (inc j) true)
                            (= (.charAt src j) \") (inc j)
                            :else (recur (inc j) false)))]
                (blank i end)
                (recur end))

              :else
              (do (.append sb c)
                  (recur (inc i))))))))))

(defn- line-at [^String src idx]
  (inc (count (re-seq #"\n" (subs src 0 (min idx (count src)))))))

(defn- strip-scope [nm]
  (str/replace nm #"(?i)^(?:global|local|script|private):" ""))

(defn- extract-ops [^String masked rel-file]
  (let [m (re-matcher #"(?im)^\s*function\s+([A-Za-z_][\w:-]*)\b" masked)
        ops (atom [])]
    (while (.find m)
      (let [nm (strip-scope (.group m 1))
            ln (line-at masked (.start m 1))]
        (swap! ops conj {:name nm :text nm :file rel-file :line ln})))
    @ops))

(defn- parse-arg-token
  "Extract the next argument from `s`, preserving spaces inside single/double quotes."
  [^String s]
  (let [t (str/triml s)]
    (cond
      (str/blank? t) [nil ""]
      (str/starts-with? t "\"")
      (when-let [[m inner] (re-find #"^\"([^\"]+)\"" t)]
        [inner (subs t (count m))])
      (str/starts-with? t "'")
      (when-let [[m inner] (re-find #"^'([^']+)'" t)]
        [inner (subs t (count m))])
      :else
      (when-let [[m tok] (re-find #"^([^\s;,|)\r\n]+)" t)]
        [tok (subs t (count m))]))))

(defn- static-ps-target? [target]
  (when (and target (not (str/blank? target)))
    ;; Allow $PSScriptRoot prefix while rejecting other runtime variables.
    (let [without-root (str/replace target #"(?i)\$PSScriptRoot[/\\]?" "")]
      (not (str/includes? without-root "$")))))

(defn- parse-import-module-target [^String tail]
  ;; Walk arguments skipping switches (-Force, -Global, -AsCustomObject, -DisableNameChecking, etc.)
  ;; and picking the value after -Name or the first positional argument.
  (let [value-params #{"-minimumversion" "-maximumversion" "-requiredversion" "-prefix"
                       "-scope" "-alias" "-cmdlet" "-function" "-variable" "-argumentlist"}]
    (loop [rem tail]
      (let [[tok rest-s] (parse-arg-token rem)]
        (cond
          (nil? tok) nil
          (= (str/lower-case tok) "-name")
          (first (parse-arg-token rest-s))
          (contains? value-params (str/lower-case tok))
          (let [[_ after-val] (parse-arg-token rest-s)]
            (recur after-val))
          (str/starts-with? tok "-")
          (recur rest-s)
          :else tok)))))

(defn- extract-static-refs [^String raw ^String masked]
  ;; Match command tokens strictly in the masked text (never inside comments or strings),
  ;; then read the path/module argument from the corresponding raw line offset.
  (let [refs (atom [])]
    (doseq [[line mline] (map vector (str/split-lines raw) (str/split-lines masked))]
      (when-not (str/blank? mline)
        ;; Dot-source: `.` must appear as an unmasked command token at line start
        (let [m (re-matcher #"(?i)^\s*(\.)(?=[ \t])" mline)]
          (when (.find m)
            (let [tail (subs line (min (.end m 1) (count line)))
                  [path _] (parse-arg-token tail)]
              (when (and path
                         (re-find #"(?i)\.(?:ps1|psm1)$" path)
                         (static-ps-target? path))
                (swap! refs conj {:kind :dot :target path})))))
        ;; Import-Module: require a true command position (line start or statement/pipeline/group delimiter)
        (let [m (re-matcher #"(?i)(?:^|[;|&({])\s*(Import-Module)(?=[ \t])" mline)]
          (while (.find m)
            (let [tail (subs line (min (.end m 1) (count line)))
                  target (parse-import-module-target tail)]
              (when (static-ps-target? target)
                (swap! refs conj {:kind :module :target target})))))
        ;; using module: command tokens must be unmasked (always script-relative)
        (let [m (re-matcher #"(?i)^\s*(using\s+module)(?=[ \t])" mline)]
          (when (.find m)
            (let [tail (subs line (min (.end m 1) (count line)))
                  [target _] (parse-arg-token tail)]
              (when (static-ps-target? target)
                (swap! refs conj {:kind :using :target target})))))
        ;; #Requires -Modules: preserved verbatim in masked text
        (when-let [[_ mods] (re-find #"(?i)^\s*#Requires\s+-Modules\s+(.+)$" mline)]
          (doseq [mod (str/split mods #",")]
            (let [cleaned (str/trim (str/replace mod #"[\"']" ""))]
              (when (and (seq cleaned) (not (str/includes? cleaned "$")) (not (str/starts-with? cleaned "@")))
                (swap! refs conj {:kind :requires :target cleaned})))))))
    @refs))

(defn- resolve-path [^File source-file ^File work-dir kind target path->id]
  (let [script-root? (boolean (re-find #"(?i)^\$PSScriptRoot(?:[/\\]|$)" target))
        cleaned (-> target
                    (str/replace #"(?i)^\$PSScriptRoot[/\\]?" "")
                    (str/replace #"\\" "/"))]
    (when-not (str/includes? cleaned "$")
      (let [base-dir (if (or script-root? (= kind :using))
                       (.getParentFile source-file)
                       work-dir)
            cand (.getCanonicalFile (if (.isAbsolute (io/file cleaned))
                                      (io/file cleaned)
                                      (io/file base-dir cleaned)))]
        (get path->id (.getPath cand))))))

(defn- explicit-path? [target]
  (or (str/includes? target "/")
      (str/includes? target "\\")
      (str/starts-with? target ".")
      (re-find #"(?i)\.(?:ps1|psm1|psd1)$" target)))

(defn- resolve-module [^File source-file ^File work-dir kind target path->id by-stem]
  (let [cleaned (str/replace target #"\\" "/")]
    (if (explicit-path? cleaned)
      ;; Explicit paths must resolve to that path — never fall back to an unrelated stem match.
      (resolve-path source-file work-dir kind target path->id)
      (let [stem (str/lower-case cleaned)
            matches (get by-stem stem)]
        (when (= 1 (count matches))
          (first matches))))))

(defn scan-powershell [root opts]
  (let [root-file (.getCanonicalFile (io/file root))
        project-root (.getCanonicalFile (io/file (or (:project-root opts)
                                                     (System/getProperty "user.dir")
                                                     ".")))
        prefix (or (:prefix opts) "")
        files (ps-files root-file)
        nodes (mapv (fn [^File f]
                      (let [p (str/replace (.getCanonicalPath f) #"\\" "/")
                            r (str/replace (.getCanonicalPath project-root) #"\\" "/")
                            rel (if (str/starts-with? p (str r "/"))
                                  (subs p (inc (count r)))
                                  (graph/relative-path f))
                            ns-name (module-namespace rel)
                            raw (slurp f)
                            masked (mask-powershell raw)]
                        {:id (graph/id-of ns-name prefix)
                         :name (graph/module-name (graph/id-of ns-name prefix))
                         :ns ns-name
                         :lang :powershell
                         :file rel
                         :canonical-path (.getCanonicalPath f)
                         :stem (str/lower-case (str/replace (.getName f) #"(?i)\.(?:psm1|ps1)$" ""))
                         :ops (extract-ops masked rel)
                         :refs (extract-static-refs raw masked)
                         :raw-file f}))
                    files)
        path->id (into {} (map (juxt :canonical-path :id) nodes))
        by-stem (reduce (fn [acc n] (update acc (:stem n) (fnil conj []) (:id n))) {} nodes)
        foreign-atom (atom {})
        edges (->> (for [n nodes
                         r (:refs n)
                         :let [resolved (if (= :dot (:kind r))
                                          (resolve-path (:raw-file n) project-root :dot (:target r) path->id)
                                          (resolve-module (:raw-file n) project-root (:kind r) (:target r) path->id by-stem))
                               to (or resolved
                                      (when (and (not= :dot (:kind r))
                                                 (not (explicit-path? (:target r))))
                                        (let [fid (keyword (:target r))]
                                          (swap! foreign-atom assoc fid (graph/foreign-class fid))
                                          fid)))]
                         :when (and to (not= to (:id n)))]
                     {:from (:id n) :to to :kind :dependency})
                   distinct
                   vec)
        clean-nodes (mapv #(dissoc % :canonical-path :stem :refs :raw-file) nodes)]
    {:classes (into clean-nodes (vals @foreign-atom))
     :edges edges}))

(def impl
  (reify graph/LanguageGraph
    (scan [_ root opts] (scan-powershell root opts))))

(graph/register! :powershell impl)
