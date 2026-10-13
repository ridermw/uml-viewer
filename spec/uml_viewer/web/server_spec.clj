(ns uml-viewer.web.server-spec
  (:require [speclj.core :refer :all]
            [clojure.data.json :as json]
            [uml-viewer.web.fixtures :refer :all]
            [uml-viewer.web.server :as server])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(defn request [handle method path token body origin]
  (let [b (HttpRequest/newBuilder (URI. (str (:origin handle) path)))]
    (when token (.header b "X-Viewer-Token" token))
    (when origin (.header b "Origin" origin))
    (if (= "GET" method) (.GET b)
        (do (.header b "Content-Type" "application/json")
            (.method b method (HttpRequest$BodyPublishers/ofString (json/write-str body)))))
    (.send (HttpClient/newHttpClient) (.build b) (HttpResponse$BodyHandlers/ofString))))

(describe "local web server"
  (it "serves actual snapshots and rejects unauthenticated or foreign-origin access"
    (with-project [root]
      (write! root "d.edn" (pr-str diagram))
      (write! root "bad.edn" "{")
      (write! root "assets/index.html" "<meta content=\"__VIEWER_TOKEN__\">")
      (let [h (server/start! {:root (str root) :diagram "d.edn" :port 0
                              :assets (str root "/assets")})]
        (try
          (let [index (request h "GET" "/" nil nil nil)
                token (second (re-find #"content=\"([^\"]+)\"" (.body index)))
                response (request h "GET" "/api/document" token nil nil)]
            (should= 200 (.statusCode index))
            (should= 200 (.statusCode response))
            (should= "Example" (get-in (json/read-str (.body response)) ["project" "title"]))
            (should= 403 (.statusCode (request h "GET" "/api/document" nil nil nil)))
            (should= 403 (.statusCode (request h "GET" "/api/document" token nil "https://other.example")))
            (should= 409 (.statusCode (request h "POST" "/api/reload" token {"revision" "old"} nil)))
            (should= 405 (.statusCode (request h "GET" "/api/source" token nil nil)))
            (should= 422 (.statusCode (request h "POST" "/api/open" token {"root" (str root) "diagram" "bad.edn"} nil)))
            (should= 200 (.statusCode (request h "GET" "/api/document" token nil nil)))
            (should= 413 (.statusCode (request h "POST" "/api/open" token {"root" (apply str (repeat 70000 "a"))} nil)))
            (should= 404 (.statusCode (request h "GET" "/api/unknown" token nil nil))))
          (finally (server/stop! h))))))
  (it "rejects unexpected authorities"
    (should-not (server/allowed-origin? "127.0.0.1:123" "evil.example:123" nil))
    (should (server/allowed-origin? "127.0.0.1:123" "127.0.0.1:123" "http://127.0.0.1:123"))))
