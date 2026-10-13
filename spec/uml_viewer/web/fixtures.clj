(ns uml-viewer.web.fixtures
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files]))

(defmacro with-project [[root] & body]
  `(let [~root (.toFile (Files/createTempDirectory
                         "uml-web-spec-" (make-array java.nio.file.attribute.FileAttribute 0)))]
     (try ~@body
          (finally
            (doseq [f# (reverse (file-seq ~root))]
              (.delete f#))))))

(defn write! [root path text]
  (let [f (io/file root path)]
    (io/make-parents f)
    (spit f text)
    f))

(def diagram
  {:title "Example"
   :packages [{:id :domain :label "Domain"
               :classes [{:id :catalog :name "Catalog"
                          :ns "example.catalog" :lang :python
                          :file "src/catalog.py"
                          :ops [{:name "find" :text "find(isbn)"}]}]}]})
