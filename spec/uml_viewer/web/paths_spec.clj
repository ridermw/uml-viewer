(ns uml-viewer.web.paths-spec
  (:require [speclj.core :refer :all]
            [clojure.java.io :as io]
            [uml-viewer.web.fixtures :refer :all]
            [uml-viewer.web.paths :as paths])
  (:import [java.nio.file Files]))

(describe "confined web paths"
  (it "resolves files within the canonical project"
    (with-project [root]
      (let [f (write! root "a.edn" "{}")
            p (paths/root! (str root))]
        (should= (.toPath (.getCanonicalFile f)) (paths/file! p "a.edn" 20)))))
  (it "rejects a symlink to another project"
    (with-project [root]
      (with-project [outside]
        (let [f (write! outside "secret.edn" "{}")
              link (.toPath (io/file root "escape.edn"))]
          (Files/createSymbolicLink link (.toPath f)
                                    (make-array java.nio.file.attribute.FileAttribute 0))
          (should-throw clojure.lang.ExceptionInfo "File is outside the selected project."
                        (paths/file! (paths/root! (str root)) "escape.edn" 20))))))
  (it "reports missing and oversized files"
    (with-project [root]
      (write! root "large.edn" "12345")
      (should-throw clojure.lang.ExceptionInfo
                    (paths/file! (paths/root! (str root)) "missing" 20))
      (should-throw clojure.lang.ExceptionInfo "File exceeds the size limit."
                    (paths/file! (paths/root! (str root)) "large.edn" 4)))))
