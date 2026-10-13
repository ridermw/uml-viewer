(ns uml-viewer.main.web-viewer-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.main.web-viewer :as cli]))

(describe "web viewer CLI"
  (it "parses explicit project and diagram options"
    (should= {:root "/project" :diagram "demo.edn" :port 4173 :assets "web/dist"}
             (cli/parse-args ["--root" "/project" "--diagram" "demo.edn" "--port" "4173"])))
  (it "rejects missing values and invalid ports"
    (doseq [args [["--root"] ["--unknown"] ["--port" "abc"] ["--port" "65536"]]]
      (should-throw clojure.lang.ExceptionInfo (cli/parse-args args)))))
