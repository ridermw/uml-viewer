(ns uml-viewer.main.web-viewer
  (:require [clojure.string :as str]
            [uml-viewer.clojure-language.source-clojure]
            [uml-viewer.python-language.source-python]
            [uml-viewer.rust-language.source-rust]
            [uml-viewer.typescript-language.source-typescript]
            [uml-viewer.web.paths :as paths]
            [uml-viewer.web.server :as server]))

(def help-text
  "Usage: clojure -M:web [--root DIR] [--diagram FILE] [--port NUMBER] [--assets DIR]\nBuild the frontend first: npm --prefix web ci && npm --prefix web run build\nDefault: current project, examples/library.edn, an available local port.\n")

(defn parse-args [args]
  (loop [args args options {:root "." :diagram "examples/library.edn" :port 0 :assets "web/dist"}]
    (if (empty? args) options
        (let [[flag value & more] args
              key (get {"--root" :root "--diagram" :diagram "--port" :port "--assets" :assets} flag)]
          (cond
            (contains? #{"-h" "--help"} flag) {:help true}
            (nil? key) (paths/error! 400 "invalid-option" (str "Unknown option: " flag))
            (or (nil? value) (str/starts-with? value "--"))
            (paths/error! 400 "missing-value" (str "Value required for " flag))
            :else
            (let [value (if (= key :port)
                          (try
                            (let [p (Integer/parseInt value)]
                              (when-not (<= 0 p 65535)
                                (paths/error! 400 "invalid-port" "Port must be between 0 and 65535."))
                              p)
                            (catch NumberFormatException _
                              (paths/error! 400 "invalid-port" "Port must be a number.")))
                          value)]
              (recur more (assoc options key value))))))))

(defn -main [& args]
  (try
    (let [options (parse-args args)]
      (if (:help options) (print help-text)
          (let [handle (server/start! options)]
            (.addShutdownHook (Runtime/getRuntime) (Thread. #(server/stop! handle)))
            (println "UML web viewer:" (:origin handle))
            (println "Open this URL in your browser. Stop with Ctrl+C.")
            @(promise))))
    (catch Exception e
      (binding [*out* *err*]
        (println "UML web viewer:" (.getMessage e))
        (println "Build assets with: npm --prefix web ci && npm --prefix web run build"))
      (System/exit 1))))
