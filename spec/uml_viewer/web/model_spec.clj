(ns uml-viewer.web.model-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.web.fixtures :refer :all]
            [uml-viewer.web.model :as model]))

(describe "web diagram snapshots"
  (it "preserves full keyword identities and nulls"
    (should= {"id" "demo/Class" "coverage" nil}
             (model/wire {:id :demo/Class :coverage nil})))
  (it "keeps namespaced class IDs distinct after layout qualification"
    (with-project [root]
      (write! root "ids.edn" (pr-str {:packages [{:id :p :classes [{:id :demo/Class}
                                                                {:id :other/Class}]}]
                                     :edges [{:from :demo/Class :to :other/Class}]}))
      (let [scene (get (model/snapshot (model/open-document (str root) "ids.edn")) "scene")]
        (should= #{"d0-demo/Class" "d0-other/Class"} (set (map #(get % "id") (get scene "classes")))))))
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
  (it "keeps unmeasured hierarchical aggregates unknown in the web view"
    (with-project [root]
      (write! root "h.edn" (pr-str {:hierarchical true :classes [{:id :alpha.beta :name "Beta"}] :edges []}))
      (let [c (first (get-in (model/snapshot (model/open-document (str root) "h.edn")) ["scene" "classes"]))]
        (should-be-nil (get c "crap"))
        (should-be-nil (get c "crapBand"))
        (should-be-nil (get c "killed"))
        (should-be-nil (get c "survived"))
        (should-be-nil (get c "mutationBand")))))
  (it "preserves measured hierarchical rollups"
    (with-project [root]
      (write! root "h.edn"
              (pr-str {:hierarchical true
                       :classes [{:id :alpha.beta :name "Beta"
                                  :crap {:mu 8 :max 8 :sigma 0 :n 1}
                                  :killed 8 :survived 2}]
                       :edges []}))
      (let [c (first (get-in (model/snapshot (model/open-document (str root) "h.edn")) ["scene" "classes"]))]
        (should= 8.0 (get-in c ["crap" "mu"]))
        (should= "green" (get c "crapBand"))
        (should= 8 (get c "killed"))
        (should= 2 (get c "survived")))))
  (it "rejects malformed and non-document EDN"
    (with-project [root]
      (write! root "bad.edn" "{")
      (write! root "scalar.edn" "42")
      (should-throw clojure.lang.ExceptionInfo
                    (model/open-document (str root) "bad.edn"))
      (should-throw clojure.lang.ExceptionInfo
                    (model/open-document (str root) "scalar.edn"))))
  (it "uses class risk dispersion and known method mutation counts"
    (with-project [root]
      (write! root "d.edn"
              (pr-str (-> diagram
                          (assoc-in [:packages 0 :classes 0 :crap] {:mu 1 :max 16 :sigma 15})
                          (assoc-in [:packages 0 :classes 0 :ops 0 :killed] 8)
                          (assoc-in [:packages 0 :classes 0 :ops 0 :survived] 2))))
      (let [c (first (get-in (model/snapshot (model/open-document (str root) "d.edn")) ["scene" "classes"]))]
        (should= "red" (get c "crapBand"))
        (should= 8 (get c "killed"))
        (should= 2 (get c "survived"))))))
