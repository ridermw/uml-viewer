(ns uml-viewer.web.paths
  (:require [clojure.string :as str])
  (:import [java.nio.file Files Path Paths LinkOption NoSuchFileException]))

(defn error! [status code message]
  (throw (ex-info message {:status status :code code})))

(defn- real! [^Path path]
  (try
    (.toRealPath path (make-array LinkOption 0))
    (catch NoSuchFileException _
      (error! 404 "missing-file" (str "File not found: " path)))))

(defn root! [path]
  (when-not (and (string? path) (not (str/blank? path)))
    (error! 400 "invalid-root" "A project directory is required."))
  (let [root (real! (Paths/get path (make-array String 0)))]
    (when-not (Files/isDirectory root (make-array LinkOption 0))
      (error! 400 "not-directory" "Project root must be a directory."))
    root))

(defn file! [^Path root path limit]
  (when-not (and (string? path) (not (str/blank? path)))
    (error! 400 "invalid-path" "A file path is required."))
  (let [resolved (real! (.resolve root path))]
    (when-not (.startsWith resolved root)
      (error! 403 "outside-project" "File is outside the selected project."))
    (when-not (Files/isRegularFile resolved (make-array LinkOption 0))
      (error! 400 "not-file" "Expected a regular file."))
    (when (> (Files/size resolved) limit)
      (error! 413 "file-too-large" "File exceeds the size limit."))
    resolved))
