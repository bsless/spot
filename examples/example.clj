(ns example
  (:require
   [bsless.spot :as spot]
   [org.httpkit.server :as server]
   [clj-async-profiler.core :as prof]))

(defn handlers-registry
  []
  {:post/plus (fn [{{{:keys [x y]} :body} :parameters}]
                {:status 200
                 :body {:total (+ x y)}})
   :get/secure (fn [request]
                 ;; In a real app authentication would be handled by middleware
                 (if (= "secret" (get-in request [:headers "example-api-key"]))
                   {:status 200
                    :body {:secret "I am a marmot"}}
                   {:status 401
                    :body {:error "unauthorized"}}))
   :get/plus (fn [{{{:keys [x y]} :query} :parameters}]
               {:status 200
                :body {:total (+ x y)}})})

(def routes
  (spot/resolve-routes
   (spot/read-edn "./examples/routes.edn")
   (merge
    (spot/handlers-registry nil)
    (handlers-registry))))

(def app
  (spot/ring-handler
   {:options {:swagger? true}
    :router
    (spot/router
     {:routes routes
      :opts
      (spot/router-opts
       {:coercion nil
        :middleware (spot/middleware nil)
        :muuntaja {:return :bytes}
        :print-request-diffs? true
        :pretty-exceptions? true
        :reitit.middleware/registry (spot/middleware-registry nil)})})}))

(def state (server/run-server #'app {:port 7777}))

#_
(app {:uri "http://localhost/index.html"
      :request-method :get})
#_
(app
 {:body nil,
  :character-encoding "utf8",
  :content-length 0,
  :content-type nil,
  :headers {"accept" "application/json,*/*",
            "accept-encoding" "gzip, deflate, br",
            "accept-language" "en-US,en",
            "connection" "keep-alive",
            "host" "localhost:7777",
            "referer" "http://localhost:7777/index.html?urls.primaryName=swagger",
            "sec-ch-ua" "\"Not A(Brand\";v=\"99\", \"Brave\";v=\"121\", \"Chromium\";v=\"121\"",
            "sec-ch-ua-mobile" "?0",
            "sec-ch-ua-platform" "\"Linux\"",
            "sec-fetch-dest" "empty",
            "sec-fetch-mode" "cors",
            "sec-fetch-site" "same-origin",
            "sec-gpc" "1",
            "user-agent" "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"},
  :path-params {},
  :query-string nil,
  :remote-addr "0:0:0:0:0:0:0:1",
  :request-method :get,
  :scheme :http,
  :server-name "localhost",
  :server-port 7777,
  :start-time 52110590206744,
  :uri "/swagger.json",
  :websocket? false})

(prof/serve-ui 7778)
