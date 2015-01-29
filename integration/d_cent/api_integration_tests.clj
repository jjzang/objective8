(ns d-cent.api-integration-tests
  (:require [midje.sweet :refer :all]
            [peridot.core :as p]
            [oauth.client :as oauth]
            [cheshire.core :as json]
            [d-cent.core :as core]
            [d-cent.utils :as utils]
            [d-cent.storage :as s]
            [d-cent.user :as user]))

(def the-user-id "twitter-user_id")
(def the-email-address "test@email.address.com")

(def temp-store (atom {}))
(def app-session (p/session (core/app (assoc core/app-config :store temp-store))))

(def the-objective {:title "my objective title"
                    :goals "my objective goals"
                    :description "my objective description"
                    :end-date "my objective end-date"
                    :created-by "some dude"})

(fact "Objectives posted to the API get stored"
      (let [request-to-create-objective (p/request app-session "/api/v1/objectives"
                                                   :request-method :post
                                                   :content-type "application/json"
                                                   :body (json/generate-string the-objective))
            response (:response request-to-create-objective)
            headers (:headers response)]
        response => (contains {:status 201})
        headers => (contains {"Location" "value"})
        (s/find-by temp-store "objectives" (constantly true)) => (contains the-objective)))

(fact "should be able to store email addresses"
      (let [temp-store (atom {})
            app-config (into core/app-config {:store temp-store})
            user-session (p/session (core/app app-config))]
        (do
          (-> user-session
              (p/content-type "application/json")
              (p/request "/api/v1/users"
                         :request-method :post
                         :headers {"Content-Type" "application/json"}
                         :body (json/generate-string {:email-address the-email-address
                                                      :user-id the-user-id})))
          (:email-address (user/retrieve-user-record temp-store the-user-id))))
      => the-email-address)
