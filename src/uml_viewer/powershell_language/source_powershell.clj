(ns uml-viewer.powershell-language.source-powershell
  "PowerShell LanguageSource: opens :file and finds a function or class method.
  Uses explicit boundaries so `Get-Total` never matches `Get-Total-Ex`."
  (:require [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  ["(?im)^\\s*function\\s+(?:(?:global|local|script|private):)?%s(?=[\\s({]|$)"
   "(?im)^\\s*(?:hidden\\s+)?(?:static\\s+)?(?:\\[[^\\]]+\\]\\s+)?%s\\s*\\("
   "(?im)^\\s*class\\s+%s(?=[\\s:{]|$)"])

(def impl (source/file-source pattern-fmts))

(source/register! :powershell impl)
