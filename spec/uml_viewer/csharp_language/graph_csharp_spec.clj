(ns uml-viewer.csharp-language.graph-csharp-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.csharp-language.graph-csharp :as cs-graph]
            [uml-viewer.csharp-language.source-csharp :as cs-source]
            [uml-viewer.graph :as graph]
            [uml-viewer.source :as source])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-root []
  (.toFile (Files/createTempDirectory "uml-cs-" (into-array FileAttribute []))))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(describe "C# LanguageGraph, source lookup, and metric joins"
  (it "merges partial classes while preserving per-method file/line provenance and edges"
    (let [dir (temp-root)]
      (spit-file dir "src/IBoard.cs"
                 "namespace Demo;\npublic interface IBoard { void Reset(); }\n")
      (spit-file dir "src/Board.cs"
                 (str "namespace Demo;\n"
                      "public partial class Board : IBoard {\n"
                      "  // class FakeInComment {}\n"
                      "  private string s = @\"class FakeInVerbatim {}\";\n"
                      "  public int Add(int x) => x + 1;\n"
                      "  public int Add(string s) { return s.Length; }\n"
                      "}\n"))
      (spit-file dir "src/Board.Part.cs"
                 (str "namespace Demo;\n"
                      "public partial class Board {\n"
                      "  public void Reset() { }\n"
                      "}\n"))
      (spit-file dir "App.Tests/App.Tests.csproj"
                 "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><IsTestProject>true</IsTestProject></PropertyGroup></Project>\n")
      (spit-file dir "App.Tests/BoardTests.cs"
                 "namespace Demo.Tests; public class BoardTests { public void T() {} }\n")
      (let [g (graph/scan cs-graph/impl dir {:prefix "Demo" :project-root dir})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            edges (set (map (juxt :from :to :kind) (:edges g)))
            board (:Board by-id)
            reset-op (first (filter #(= "Reset" (:name %)) (:ops board)))]
        (should-contain :IBoard by-id)
        (should-contain :Board by-id)
        (should-not-contain :FakeInComment by-id)
        (should-not-contain :FakeInVerbatim by-id)
        (should-not-contain :Tests.BoardTests by-id)
        (should= "Demo.Board" (:ns board))
        (should= "src/Board.cs" (:file board))
        (should= "src/Board.Part.cs" (:file reset-op))
        (should= 3 (:line reset-op))
        (should (contains? edges [:Board :IBoard :implements]))
        ;; Source lookup opens Board.Part.cs for Reset
        (let [src-res (source/member-source cs-source/impl
                                            {:lang :csharp
                                             :ns "Demo.Board"
                                             :name "Reset"
                                             :file (.getPath (io/file dir (:file reset-op)))
                                             :line (:line reset-op)})]
          (should-not-be-nil src-res)
          (should= 3 (:line src-res))
          (should-contain "void Reset()" (:body src-res))))))

  (it "claims nested canonical namespaces and sums partial mutation + conservative overload CRAP"
    (let [doc {:hierarchical true
               :prefix "Demo"
               :classes [{:id :Board :name "Board" :ns "Demo.Board" :lang :csharp
                          :ops [{:name "Add" :text "Add"} {:name "Reset" :text "Reset"}]}]}
          metrics {:crap {"Demo.Board" [{:name "Add" :namespace "Demo.Board" :complexity 2 :coverage 100.0 :crap 2.0}
                                        {:name "Add" :namespace "Demo.Board" :complexity 5 :coverage 0.0 :crap 30.0}]
                          "Demo.Board.Inner" [{:name "Step" :namespace "Demo.Board.Inner" :complexity 3 :coverage 50.0 :crap 4.1}]}
                   :mutate {"Demo.Board" {:namespace "Demo.Board"
                                          :forms [{:id "defn/Add" :file "src/Board.cs" :line 5 :hash "h1"
                                                   :killed 2 :survived 1 :uncovered 0 :sites 3 :invalid 1}
                                                  {:id "defn/Add" :file "src/Board.Part.cs" :line 3 :hash "h2"
                                                   :killed 3 :survived 0 :uncovered 1 :sites 4 :errors 1 :incomplete true}]}}}
          painted (overlay/apply-metrics doc metrics)
          board (first (:classes painted))
          add-op (first (filter #(= "Add" (:name %)) (:ops board)))]
      ;; Conservative overload CRAP (max = 30.0) and summed partial mutation counts
      (should= 30.0 (:crap add-op))
      (should= 5 (:killed add-op))
      (should= 1 (:survived add-op))
      (should= 1 (:uncovered add-op))
      (should= 1 (:invalid add-op))
      (should= 1 (:errors add-op))
      (should (:incomplete add-op))
      (should (:incomplete board))
      ;; Nested canonical namespace Demo.Board.Inner claimed by Demo.Board
      (should (some #(= "Step" (:name %)) (:ops board)))))

  (it "handles balanced generic bases, single-line block namespaces, IR normalization, and hierarchy incomplete rollups"
    (let [dir (temp-root)]
      (spit-file dir "src/Pair.cs"
                 (str "namespace Demo { public interface IPair<K, V> { } }\n"
                      "namespace Demo { public class Map : IPair<string, List<int>> { public void Go() {} } }\n"))
      (let [g (graph/scan cs-graph/impl dir {:prefix "Demo" :project-root dir})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should-contain :IPair by-id)
        (should-contain :Map by-id)
        (should= "Demo.Map" (:ns (:Map by-id)))
        (should (contains? edges [:Map :IPair :implements]))))
    ;; Packaged diagram normalization preserves operation provenance and incomplete flags
    (let [doc {:title "T"
               :packages [{:id :demo :label "Demo"
                           :classes [{:id :Board :name "Board" :ns "Demo.Board" :file "src/Board.cs"
                                      :ops [{:name "Reset" :text "Reset" :file "src/Board.Part.cs" :line 3}]}]}]}
          metrics {:crap {}
                   :mutate {"Demo.Board" {:namespace "Demo.Board" :incomplete true
                                          :forms [{:id "defn/Reset" :file "src/Board.Part.cs" :line 3 :hash "h"
                                                   :killed 1 :survived 0 :uncovered 0 :sites 1 :errors 1 :incomplete true}]}}}
          painted (overlay/apply-metrics doc metrics)
          board (first (:classes (first (:packages painted))))
          reset-op (first (:ops board))]
      (should (:incomplete board))
      (should= 1 (:errors board))
      (should= "src/Board.Part.cs" (:file reset-op))
      (should= 3 (:line reset-op))
      (should (:incomplete reset-op)))
    ;; Legacy forms without :file/:line from distinct snapshots are not collapsed
    (let [doc {:hierarchical true
               :prefix "Demo"
               :classes [{:id :Board :name "Board" :ns "Demo.Board" :lang :csharp}]}
          metrics {:crap {}
                   :mutate {"Demo.Board" {:namespace "Demo.Board"
                                          :forms [{:id "defn/Run" :hash "same" :killed 2 :survived 0 :uncovered 0 :sites 2}]}
                            "Demo.Board.Inner" {:namespace "Demo.Board.Inner"
                                                :forms [{:id "defn/Run" :hash "same" :killed 3 :survived 0 :uncovered 0 :sites 3}]}}}
          painted (overlay/apply-metrics doc metrics)
          board (first (:classes painted))]
      (should= 5 (:killed board)))))
