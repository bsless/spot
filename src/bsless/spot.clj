(ns bsless.spot
  (:require
   [clj-async-profiler.core :as prof]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.walk :refer [postwalk]]
   [malli.util :as mu]
   [muuntaja.core :as m]
   [org.httpkit.server :as server]
   [reitit.coercion.malli :as rcm]
   [reitit.dev.pretty :as pretty]
   [reitit.impl :refer [meta-merge]]
   [reitit.openapi :as openapi]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.malli :as rrm]
   [reitit.ring.middleware.dev :as dev]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [reitit.ring.spec :as spec]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui])
  (:import
   (java.io PushbackReader)))

(defn resolve-routes
  [routes registry]
  (postwalk
   (fn [m]
     (if-let [k (and (map? m) (:handler m))]
       (let [h (registry k)]
         (when-not h
           (throw (ex-info (str "Cannot resolve handler " k)
                           {:handler k
                            :routes routes
                            :registry registry})))
         (assoc m :handler h))
       m))
   routes))

(defn read-edn
  [f]
  (let [f (io/file f)]
    (with-open [r (io/reader f)]
      (edn/read (PushbackReader. r)))))

(defn handlers-registry
  [_]
  {:openapi/handler (openapi/create-openapi-handler)
   :swagger/handler (swagger/create-swagger-handler)})

(defn middleware-registry
  [_]
  {
   :coercion/coerce-request-middleware coercion/coerce-request-middleware
   :coercion/coerce-response-middleware coercion/coerce-response-middleware
   :coercion/coerce-exceptions-middleware coercion/coerce-exceptions-middleware
   :exception/exception-middleware exception/exception-middleware
   :muuntaja/format-negotiate-middeleware muuntaja/format-negotiate-middleware
   :muuntaja/format-request-middleware muuntaja/format-request-middleware
   :muuntaja/format-response-middleware muuntaja/format-response-middleware
   :openapi/openapi-feature openapi/openapi-feature
   :parameters/parameters-middleware parameters/parameters-middleware
   :swagger/swagger-feature swagger/swagger-feature
   })

(def coercion-opts
  {;; set of keys to include in error messages
   :error-keys #{:type
                 :coercion
                 :in
                 #_:schema
                 #_:value
                 #_:errors
                 :humanized
                 #_:transformed}
   ;; support lite syntax?
   :lite true
   ;; schema identity function (default: close all map schemas)
   :compile mu/closed-schema
   ;; validate request & response
   :validate true
   ;; top-level short-circuit to disable request & response coercion
   :enabled true
   ;; strip-extra-keys (effects only predefined transformers)
   :strip-extra-keys true
   ;; add/set default values
   :default-values true
   ;; encode-error
   :encode-error true
   ;; malli options
   :options nil})

(defn coercion
  [opts]
  (rcm/create (meta-merge coercion-opts opts nil)))

(defn middleware
  [_]
  [
   :openapi/openapi-feature
   :swagger/swagger-feature
   :parameters/parameters-middleware
   :muuntaja/format-negotiate-middeleware
   :muuntaja/format-response-middleware
   :exception/exception-middleware
   :muuntaja/format-request-middleware
   :coercion/coerce-exceptions-middleware
   :coercion/coerce-response-middleware
   :coercion/coerce-request-middleware
   ])


(defn muuntaja
  [options]
  (m/create (meta-merge m/default-options options nil)))

(defn router-opts
  [{coercion-opts :coercion
    muuntaja-opts :muuntaja
    middleware :middleware
    print-request-diffs? :print-request-diffs?
    pretty-exceptions? :pretty-exceptions?
    middleware-registry :reitit.middleware/registry}]
  (cond->
      {:validate spec/validate
       :data {:coercion (coercion coercion-opts)
              :muuntaja (muuntaja muuntaja-opts)
              :middleware middleware}}
    print-request-diffs? (update :reitit.middleware/transform (fnil conj []) dev/print-request-diffs)
    pretty-exceptions? (assoc :exception pretty/exception)
    middleware-registry (assoc :reitit.middleware/registry middleware-registry)))

(defn router
  [{:keys [routes opts]}]
  (ring/router routes opts))

(def swagger-config
  {:path "/"
   :config {:validatorUrl nil
            :urls [{:name "openapi" :url "openapi.json"}
                   {:name "swagger" :url "swagger.json"}]
            :urls.primaryName "openapi"
            :operationsSorter "alpha"}})

(defn swagger-handler
  ([]
   (swagger-handler nil))
  ([opts]
   (swagger-ui/create-swagger-ui-handler (merge swagger-config opts))))

(defn ring-handler
  [{:keys [router default-handler options registry]}]
  (ring/ring-handler
   router
   ;; default handler
   (cond->> (or default-handler (ring/create-default-handler))
     (:swagger? options)
     (ring/routes (swagger-handler (:swagger-options options))))
   ;; options
   (cond-> options
     registry (assoc :reitit.middleware/registry registry))))
