(ns uml-viewer.web.model-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.web.fixtures :refer :all]
            [uml-viewer.web.model :as model]))

(describe "web diagram snapshots"
  (it "preserves full keyword identities and nulls"
    (should= {"id" "demo/Class" "coverage" nil}
             (model/wire {:id :demo/Class :coverage nil})))
  (it "loads a real document immediately"
    (let [response (model/snapshot (model/open-document "." "examples/library.edn"))]
      (should= "Lending library" (get-in response ["project" "title"]))
      (should= 10 (count (get-in response ["scene" "classes"])))))
  (it "keeps omitted metrics unknown and does not use working-directory snapshots"
    (with-project [root]
      (write! root "d.edn" (pr-str diagram))
      (let [response (model/snapshot (model/open-document (str root) "d.edn"))
            c (first (get-in response ["scene" "classes"]))]
        (should-be-nil (get c "coverage"))
        (should-be-nil (get c "crap"))
        (should-be-nil (get c "crapBand"))
        (should-be-nil (get c "mutationBand")))))
  (it "navigates reachable hierarchical components"
    (with-project [root]
      (write! root "h.edn"
              (pr-str {:hierarchical true :title "Hierarchy"
                       :classes [{:id :alpha.beta :name "Beta"}]
                       :edges []}))
      (let [state (model/open-document (str root) "h.edn")
            focused (model/focus-document state ["alpha"])]
        (should= ["alpha"] (get (model/snapshot focused) "focus"))
        (should-not= (:revision state) (:revision focused))
        (should-throw clojure.lang.ExceptionInfo
                      (model/focus-document state ["unknown"])))))
  (it "rejects malformed and non-document EDN"
    (with-project [root]
      (write! root "bad.edn" "{")
      (write! root "scalar.edn" "42")
      (should-throw clojure.lang.ExceptionInfo
                    (model/open-document (str root) "bad.edn"))
      (should-throw clojure.lang.ExceptionInfo
                    (model/open-document (str root) "scalar.edn")))))
