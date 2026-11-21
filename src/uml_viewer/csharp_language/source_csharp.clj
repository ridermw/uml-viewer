(ns uml-viewer.csharp-language.source-csharp
  "C# LanguageSource: opens :file (including partial-file overrides) and locates
  a method or type declaration."
  (:require [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  ["(?m)^\\s*(?:(?:public|internal|private|protected|static|virtual|override|abstract|sealed|async|extern|unsafe|new|partial|readonly)\\s+)*[A-Za-z_][\\w.<>,\\[\\]?\\s]*?\\s+%s(?:\\s*<[^>]+>)?\\s*\\("
   "(?m)\\b(?:class|struct|interface|enum|record(?:\\s+(?:class|struct))?)\\s+%s\\b"])

(def impl (source/file-source pattern-fmts))

(source/register! :csharp impl)
