(ns objective8.api.tokens-integration-tests
  (:require [midje.sweet :refer :all]
            [peridot.core :as p]
            [cheshire.core :as json]
            [objective8.integration-helpers :as helpers]
            [objective8.storage.storage :as storage]
            [objective8.storage.database :as db]
            [objective8.bearer-tokens :as bearer-tokens]))

(defn db-connection [] (db/connect! db/postgres-spec))

(def app (helpers/test-context))
(def the-token "token")
(def some-wrong-token "wrong-token")
(def the-bearer "bearer")
(def bearer-token-map {:entity :bearer-token :bearer-name the-bearer :bearer-token the-token})

(facts "Bearer token tests" :integration
       (against-background [(before :contents (do (db-connection) 
                                                  (helpers/truncate-tables))) 
                            (after :facts (helpers/truncate-tables))]

                           (fact "can't access protected api resource without valid bearer-token"
                                 (storage/pg-store! bearer-token-map) 
                                 (p/request app "/api/v1/users"
                                            :request-method :post
                                            :content-type "application/json"
                                            :headers {"api-bearer-token" some-wrong-token
                                                      "api-bearer-name" the-bearer}
                                            :body (json/generate-string {:twitter-id "Twitter_ID"}))
                                 => (contains {:response (contains  {:status 401})}))

                           (fact "can access protected api resource with valid bearer-token"
                                 (storage/pg-store! bearer-token-map) 
                                 (p/request app "/api/v1/users"
                                            :request-method :post
                                            :content-type "application/json"
                                            :headers {"api-bearer-token" the-token
                                                      "api-bearer-name" the-bearer}
                                            :body (json/generate-string {:twitter-id "Twitter_ID"}))
                                 => (contains {:response (contains  {:status 201})}))))