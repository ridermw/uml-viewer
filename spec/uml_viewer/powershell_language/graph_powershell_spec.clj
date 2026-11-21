(ns uml-viewer.powershell-language.graph-powershell-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.powershell-language.graph-powershell :as ps-graph]
            [uml-viewer.powershell-language.source-powershell :as ps-source]
            [uml-viewer.source :as source])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-root []
  (.toFile (Files/createTempDirectory "uml-ps-" (into-array FileAttribute []))))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(describe "PowerShell LanguageGraph and LanguageSource"
  (it "derives project-root-relative namespaces, resolves static deps, keeps bin/, and skips *.Tests.ps1"
    (let [dir (temp-root)]
      (spit-file dir "src/Core/Math.psm1"
                 (str "function Get-Total-Ex { param($x) return $x + 1 }\n"
                      "function Get-Total { param($x) return $x }\n"))
      (spit-file dir "scripts/Deploy.ps1"
                 (str "#Requires -Modules Az.Accounts\n"
                      "# . \"$PSScriptRoot/../src/Core/Fake.ps1\"\n"
                      ". \"$PSScriptRoot/../src/Core/Math.psm1\"\n"
                      "function Invoke-Deploy { Get-Total 1 }\n"))
      (spit-file dir "bin/Helper.ps1" "function Get-HelpData { return 1 }\n")
      (spit-file dir "tests/Math.Tests.ps1" "Describe 'Math' { It 'ok' {} }\n")
      (let [g (graph/scan ps-graph/impl dir {:prefix "" :project-root dir})
            by-ns (into {} (map (juxt :ns identity) (:classes g)))
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should-contain "Core.Math" by-ns)
        (should-contain "scripts.Deploy" by-ns)
        (should-contain "bin.Helper" by-ns)
        (should-contain "Az.Accounts" by-ns)
        (should (:foreign (get by-ns "Az.Accounts")))
        (should-not-contain "tests.Math.Tests" by-ns)
        (should (contains? edges [:scripts.Deploy :Core.Math :dependency]))
        (should (contains? edges [:scripts.Deploy :Az.Accounts :dependency]))
        ;; Get-Total must match line 2, NOT Get-Total-Ex on line 1
        (let [math-file (.getPath (io/file dir "src/Core/Math.psm1"))
              res (source/member-source ps-source/impl
                                        {:lang :powershell :ns "Core.Math" :file math-file :name "Get-Total"})]
          (should-not-be-nil res)
          (should= 2 (:line res))))))

  (it "ignores Import-Module inside strings/comments and records function line from captured name"
    (let [dir (temp-root)]
      (spit-file dir "src/Clean.ps1"
                 (str "# Header comment\n\n"
                      "function Get-Clean {\n"
                      "  $msg = 'Import-Module FakeFromString'\n"
                      "  Write-Host ok # Import-Module FakeFromComment\n"
                      "}\n"))
      (let [g (graph/scan ps-graph/impl dir {:prefix "" :project-root dir})
            by-ns (into {} (map (juxt :ns identity) (:classes g)))
            clean (get by-ns "Clean")]
        (should-not-contain "FakeFromString" by-ns)
        (should-not-contain "FakeFromComment" by-ns)
        (should= 3 (:line (first (:ops clean)))))))

  (it "handles quoted paths with spaces, Import-Module switches and $PSScriptRoot, command boundaries, and no path stem fallback"
    (let [dir (temp-root)]
      (spit-file dir "src/My Module.psm1" "function Get-M { return 1 }\n")
      (spit-file dir "src/Other.psm1" "function Get-O { return 2 }\n")
      (spit-file dir "src/Runner.ps1"
                 (str ". \"$PSScriptRoot/My Module.psm1\"\n"
                      "Import-Module -Force -Name Az.Accounts\n"
                      "Import-Module \"$PSScriptRoot/Other.psm1\"\n"
                      "Import-Module './Missing/Other.psm1'\n"
                      "Test-Import-Module ShouldIgnore\n"))
      (let [g (graph/scan ps-graph/impl dir {:prefix "" :project-root dir})
            by-ns (into {} (map (juxt :ns identity) (:classes g)))
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should-contain "Az.Accounts" by-ns)
        (should-not-contain "-Force" by-ns)
        (should-not-contain "ShouldIgnore" by-ns)
        (should (contains? edges [:Runner (keyword "My Module") :dependency]))
        (should (contains? edges [:Runner :Other :dependency]))
        (should (contains? edges [:Runner :Az.Accounts :dependency]))))))
