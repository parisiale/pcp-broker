(ns puppetlabs.cthun.testutils.client
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [timeout alts!! chan >!! <!!]]
            [http.async.client :as http]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.cthun.message :as message]
            [puppetlabs.ssl-utils.core :as ssl-utils]))

;; A simple websockets client with some assertions - for non-testing uses use pcp-client.

(defprotocol WsClient
  (close [this])
  (send! [this message])
  (recv! [this] [this timeout]
    "Returns nil on timeout, [code reason] on close, message/Message on message"))

(defrecord ChanClient [http-client ws-client message-channel]
  WsClient
  (close [this]
    (async/close! (:message-channel this))
    (.close (:ws-client this))
    (.close (:http-client this)))
  (send! [this message]
    (http/send (:ws-client this) :byte (message/encode message)))
  (recv! [this] (recv! this (* 10 1000)))
  (recv! [this timeout-ms]
    (let [[message channel] (alts!! [(:message-channel this) (timeout timeout-ms)])]
      message)))

(defn http-client-with-cert
  [certname]
  (let [cert        (format "./test-resources/ssl/certs/%s.pem" certname)
        private-key (format "./test-resources/ssl/private_keys/%s.pem" certname)
        ca-cert     "./test-resources/ssl/ca/ca_crt.pem"
        ssl-context (ssl-utils/pems->ssl-context cert private-key ca-cert)]
    (http/create-client :ssl-context ssl-context)))

(defn make-association-request
  [uri]
  (-> (message/make-message)
      (assoc :id (ks/uuid)
             :message_type "http://puppetlabs.com/associate_request"
             :targets ["cth:///server"]
             :sender uri)
      (message/set-expiry 3 :seconds)))

(defn connect
  "Makes a client for testing"
  [certname identity check-ok]
  (let [association-request (make-association-request identity)
        client              (http-client-with-cert certname)
        message-chan        (chan)
        ws                  (http/websocket client "wss://127.0.0.1:8081/cthun"
                                            :open  (fn [ws]
                                                     (http/send ws :byte (message/encode association-request)))
                                            :byte  (fn [ws msg]
                                                     (>!! message-chan (message/decode msg)))
                                            :close (fn [ws code reason]
                                                     (>!! message-chan [code reason])))
        wrapper             (ChanClient. client ws message-chan)]
    (if check-ok
      (let [response (recv! wrapper)]
        (is (= "http://puppetlabs.com/associate_response" (:message_type response)))
        (is (= {:id (:id association-request)
                :success true}
               (message/get-json-data response)))))
    wrapper))