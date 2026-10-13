(ns uml-viewer.web.source-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.python-language.source-python]
            [uml-viewer.clojure-language.source-clojure]
            [uml-viewer.web.fixtures :refer :all]
            [uml-viewer.web.model :as model]
            [uml-viewer.web.source :as source]))

(describe "web source inspection"
  (it "extracts a real Python method in the selected project"
    (with-project [root]
      (write! root "d.edn" (pr-str diagram))
      (write! root "src/catalog.py" "class Catalog:\n    def find(self, isbn):\n        return isbn\n")
      (let [state (model/open-document (str root) "d.edn")
            result (source/read-member state "d0-catalog" "find")]
        (should= 2 (get result "line"))
        (should= "src/catalog.py" (get result "file"))
        (should-contain "return isbn" (get result "body")))))
  (it "does not accept unknown identities or methods"
    (with-project [root]
      (write! root "d.edn" (pr-str diagram))
      (write! root "src/catalog.py" "class Catalog:\n    def find(self, isbn):\n        return isbn\n")
      (let [state (model/open-document (str root) "d.edn")]
        (should-throw clojure.lang.ExceptionInfo (source/read-member state "absent" nil))
        (should-throw clojure.lang.ExceptionInfo (source/read-member state "d0-catalog" "absent")))))
  (it "reports missing source rather than returning empty text"
    (with-project [root]
      (write! root "d.edn" (pr-str diagram))
      (should-throw clojure.lang.ExceptionInfo
                    (source/read-member (model/open-document (str root) "d.edn")
                                        "d0-catalog" nil))))
  (it "resolves Clojure namespaces against the chosen root"
    (with-project [root]
      (write! root "d.edn" (pr-str (-> diagram
                                     (assoc-in [:packages 0 :classes 0 :lang] :clojure)
                                     (assoc-in [:packages 0 :classes 0 :file] nil))))
      (write! root "src/example/catalog.clj" "(defn find [isbn]\n  isbn)\n")
      (let [result (source/read-member (model/open-document (str root) "d.edn") "d0-catalog" "find")]
        (should= 1 (get result "line"))
        (should= "src/example/catalog.clj" (get result "file")))))
  (it "refuses source outside the project before reading its body"
    (with-project [root]
      (with-project [outside]
        (let [file (write! outside "catalog.py" "private content")]
          (write! root "d.edn" (pr-str (assoc-in diagram [:packages 0 :classes 0 :file] (str file))))
          (should-throw clojure.lang.ExceptionInfo "File is outside the selected project."
                        (source/read-member (model/open-document (str root) "d.edn") "d0-catalog" nil))))))
  (it "refuses oversized source"
    (with-project [root]
      (write! root "d.edn" (pr-str diagram))
      (write! root "src/catalog.py" (apply str (repeat 1048577 "x")))
      (should-throw clojure.lang.ExceptionInfo "File exceeds the size limit."
                    (source/read-member (model/open-document (str root) "d.edn") "d0-catalog" nil)))))
