(ns uml-viewer.web.source-spec
  (:require [speclj.core :refer :all]
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
                                        "d0-catalog" nil)))))
