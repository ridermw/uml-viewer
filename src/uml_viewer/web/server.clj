(ns uml-viewer.web.server
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [uml-viewer.domain.log :as log]
            [uml-viewer.web.model :as model]
            [uml-viewer.web.paths :as paths]
            [uml-viewer.web.source :as source])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.security SecureRandom MessageDigest]
           [java.util Base64]
           [java.util.concurrent Executors]))

(defn allowed-origin? [authority host origin]
  (and (= authority host) (or (nil? origin) (= (str "http://" authority) origin))))

(defn- header [^HttpExchange exchange name]
  (.getFirst (.getRequestHeaders exchange) name))

(defn- send! [^HttpExchange exchange status type body]
  (let [bytes (if (string? body) (.getBytes ^String body StandardCharsets/UTF_8) body)
        headers (.getResponseHeaders exchange)]
    (.set headers "Content-Type" type)
    (.set headers "X-Content-Type-Options" "nosniff")
    (.set headers "Cache-Control" "no-store")
    (.set headers "X-Frame-Options" "DENY")
    (.set headers "Content-Security-Policy"
          "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")
    (.sendResponseHeaders exchange status (alength ^bytes bytes))
    (with-open [out (.getResponseBody exchange)] (.write out ^bytes bytes))))

(defn- json! [exchange status data]
  (send! exchange status "application/json; charset=utf-8" (json/write-str data)))

(defn- body! [^HttpExchange exchange]
  (let [data (.readNBytes (.getRequestBody exchange) 65537)]
    (when (> (alength data) 65536)
      (paths/error! 413 "body-too-large" "Request body exceeds the size limit."))
    (let [body (try (json/read-str (String. data StandardCharsets/UTF_8))
                    (catch RuntimeException _
                      (paths/error! 400 "invalid-json" "Request must contain valid JSON.")))]
      (when-not (map? body) (paths/error! 400 "invalid-input" "Request must be a JSON object."))
      body)))

(defn- revision! [state body]
  (when-not (= (:revision state) (get body "revision"))
    (paths/error! 409 "stale-document" "The diagram changed. Reload it before continuing.")))

(defn- api! [exchange state token path]
  (let [provided (or (header exchange "X-Viewer-Token") "")]
    (when-not (MessageDigest/isEqual (.getBytes ^String token StandardCharsets/UTF_8)
                                    (.getBytes ^String provided StandardCharsets/UTF_8))
      (paths/error! 403 "unauthorized" "Viewer token is missing or invalid.")))
  (let [method (.getRequestMethod ^HttpExchange exchange)]
    (when-not (contains? #{"/api/document" "/api/open" "/api/focus" "/api/reload" "/api/source"} path)
      (paths/error! 404 "unknown-route" "API route not found."))
    (when-not (= method (if (= path "/api/document") "GET" "POST"))
      (paths/error! 405 "wrong-method" "Method is not supported for this operation."))
    (locking state
      (if (= path "/api/document")
        (json! exchange 200 (model/snapshot @state))
        (let [body (body! exchange)]
          (when-not (= path "/api/open") (revision! @state body))
          (if (= path "/api/source")
            (let [id (get body "classId") member (get body "member")]
              (when-not (and (string? id) (or (nil? member) (string? member)))
                (paths/error! 400 "invalid-source-request" "A class ID and optional method name are required."))
              (json! exchange 200 (source/read-member @state id member)))
            (let [candidate (case path
                              "/api/open" (model/open-document (get body "root") (get body "diagram"))
                              "/api/focus" (model/focus-document @state (get body "focus"))
                              "/api/reload" (model/reload-document @state))
                  response (model/snapshot candidate)]
              (reset! state candidate)
              (json! exchange 200 response))))))))

(def content-types
  {"html" "text/html; charset=utf-8" "js" "text/javascript; charset=utf-8"
   "css" "text/css; charset=utf-8" "svg" "image/svg+xml" "png" "image/png"
   "ico" "image/x-icon" "woff2" "font/woff2"})

(defn- static! [exchange ^Path assets token path]
  (when-not (= "GET" (.getRequestMethod ^HttpExchange exchange))
    (paths/error! 405 "wrong-method" "Static files support GET only."))
  (let [relative (if (contains? #{"/" "/index.html"} path) "index.html" (subs path 1))
        file (paths/file! assets relative 16777216)
        extension (last (str/split relative #"\."))
        type (get content-types extension "application/octet-stream")]
    (send! exchange 200 type
           (if (= relative "index.html")
             (str/replace (slurp (str file)) "__VIEWER_TOKEN__" token)
             (Files/readAllBytes file)))))

(defn start! [{:keys [root diagram port assets]}]
  (let [assets (paths/root! assets)
        _ (paths/file! assets "index.html" 1048576)
        state (atom (model/open-document root diagram))
        _ (model/snapshot @state)
        bytes (byte-array 32)
        _ (.nextBytes (SecureRandom.) bytes)
        token (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)
        http (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)
        authority (str "127.0.0.1:" (.getPort (.getAddress http)))
        executor (Executors/newFixedThreadPool 4)]
    (.setExecutor http executor)
    (.createContext http "/"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (when-not (allowed-origin? authority (header exchange "Host") (header exchange "Origin"))
              (paths/error! 403 "foreign-origin" "Only the local viewer origin is allowed."))
            (let [path (.getPath (.getRequestURI exchange))]
              (if (str/starts-with? path "/api/")
                (api! exchange state token path)
                (static! exchange assets token path)))
            (catch Exception e
              (let [{:keys [status code]} (ex-data e)]
                (when-not status (log/log-exception! e "web request"))
                (json! exchange (or status 500)
                       {"error" {"code" (or code "server-error")
                                 "message" (if status (.getMessage e) "The viewer could not complete the request.")}})))
            (finally (.close exchange))))))
    (.start http)
    {:server http :origin (str "http://" authority)
     :stop! (fn [] (.stop http 0) (.shutdownNow executor))}))

(defn stop! [handle] ((:stop! handle)))
