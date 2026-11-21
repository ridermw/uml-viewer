(ns uml-viewer.web.source
  (:require [clojure.string :as str]
            [uml-viewer.source :as source]
            [uml-viewer.web.model :as model]
            [uml-viewer.web.paths :as paths])
  (:import [java.nio.file Files LinkOption Path]))

(defn- source-path [state c]
  (or (:file c)
      (when (and (= :clojure (or (:lang c) :clojure)) (:ns c))
        (let [stem (-> (:ns c) (str/replace "-" "_") (str/replace "." "/"))]
          (some (fn [ext]
                  (let [relative (str "src/" stem ext)]
                    (when (Files/exists (.resolve ^Path (:root state) relative)
                                        (make-array LinkOption 0))
                      relative)))
                [".clj" ".cljc" ".cljs"])))
      (paths/error! 404 "missing-source" "This class has no available source file.")))

(defn read-member [state class-id member-name]
  (let [c (some #(when (= class-id (model/wire (:id %))) %)
                (:classes (model/scene state)))]
    (when-not c (paths/error! 404 "unknown-class" "Class is not in the current diagram."))
    (let [op (when member-name (first (filter #(= member-name (:name %)) (:ops c))))]
      (when (and member-name (not op))
        (paths/error! 404 "unknown-member" "Method is not in the selected class."))
      (let [lang (or (:lang c) :clojure)
            impl (or (source/lookup lang)
                     (paths/error! 422 "unsupported-language" "Source extraction is not supported for this language."))
            rel-file (or (:file op) (source-path state c))
            path (paths/file! (:root state) rel-file 1048576)
            body (slurp (str path))
            ident (cond-> {:ns (:ns c) :file (str path) :name member-name}
                    (:line op) (assoc :line (:line op)))
            line (when member-name (or (:line op) (source/start-line impl body ident)))]
        (when (and member-name (not (:line op)) (not (source/extract impl body ident)))
          (paths/error! 404 "missing-member" "The method was not found in its source file."))
        (model/wire {:file (str (.relativize ^Path (:root state) path))
                     :body body :line line :lang lang :revision (:revision state)})))))
