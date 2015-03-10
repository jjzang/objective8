(ns objective8.handlers.front-end
  (:require [clojure.tools.logging :as log]
            [cemerick.friend :as friend]
            [ring.util.response :as response]
            [endophile.core :as ec]
            [endophile.hiccup :as eh]
            [hiccup.core :as hc]
            [cheshire.core :as json]
            [org.httpkit.client :as http]
            [objective8.responses :refer :all]
            [objective8.http-api :as http-api]
            [objective8.front-end-helpers :as helpers]
            [objective8.utils :as utils]
            [objective8.views :as views]))

;; HELPERS

(defn signed-in? []
  (friend/authorized? #{:signed-in} friend/*identity*))

(defn error-404-response [request]
  (assoc (views/four-o-four "error-404" request) :status 404))

(defn invitation? [session]
  (:invitation session))

;; HANDLERS

(defn error-404 [request]
  (error-404-response request))

(defn index [{:keys [t' locale] :as request}]
  (views/index "index" request))

(defn sign-in [{{refer :refer} :params
                :keys [t' locale]
                :as request}]
  (-> (views/sign-in "sign-in" request)
      (assoc :session (:session request))
      (assoc-in [:session :sign-in-referrer] refer)))

(defn sign-out [request]
  (assoc
   (friend/logout* (response/redirect "/"))
   :session {}))

(defn project-status [{:keys [t' locale] :as request}]
  (views/project-status "project-status" request))

(defn learn-more [{:keys [t' locale] :as request}]
  (views/learn-more "learn-more" request))
;; USER PROFILE

(defn sign-up-form [{:keys [t' locale errors] :as request}]
  (views/sign-up-form "sign-up" request :errors errors))

;; OBJECTIVES
(defn format-objective [objective]
  (let [goals (if (objective :goals)
                (list (objective :goals))
                (remove clojure.string/blank? [(:goal-1 objective) (:goal-2 objective) (:goal-3 objective)]))
        formatted-objective (-> objective
                                (update-in [:end-date] utils/date-time->pretty-date)
                                (assoc :goals goals)
                                (dissoc :goal-1 :goal-2 :goal-3))]
    formatted-objective))

(defn objective-list [{:keys [t' locale] :as request}]
  (let [{status :status objectives :result} (http-api/get-all-objectives)]
    (cond 
      (= status ::http-api/success)
        (views/objectives-list "objective-list"
                              request
                              :objectives (map format-objective objectives))
      (= status ::http-api/error)
        {:status 502})))

(defn create-objective-form [{:keys [t' locale] :as request}]
  (views/create-objective-form "objective-create" request))

(defn create-objective-form-post [{:keys [t' locale] :as request}]
  (if-let [objective (helpers/request->objective request (get (friend/current-authentication) :username))]
    (let [{status :status stored-objective :result} (http-api/create-objective objective)]
      (cond (= status ::http-api/success)
            (let [objective-url (str utils/host-url "/objectives/" (:_id stored-objective))
                  message (t' :objective-view/created-message)]
              (assoc (response/redirect objective-url) :flash message)) 

            (= status ::http-api/invalid-input) {:status 400}

            :else {:status 502}))
    {:status 400}))

(defn objective-detail [{{id :id} :route-params
                         message :flash
                         :keys [uri t' locale]
                         :as request}]
  (try (let [objective-id (Integer/parseInt id)
             {objective-status :status objective :result} (http-api/get-objective objective-id)
             {comments-status :status comments :result} (http-api/retrieve-comments objective-id)]
         (cond
           (every? #(= ::http-api/success %) [objective-status comments-status])
           (let [formatted-objective (format-objective objective)]
             (views/objective-detail-page "objective-details"
                                          request
                                          :objective formatted-objective
                                          :comments comments
                                          :doc (let [details (str (:title objective) " | Objective[8]")]
                                                 {:title details
                                                  :description details})))
           (= objective-status ::http-api/not-found) (error-404-response request)

           (= objective-status ::http-api/invalid-input) {:status 400}

           :else {:status 500}))
       (catch NumberFormatException e
         (log/info "Invalid route: " e)
         (error-404-response request))))

;; COMMENTS

(defn create-comment-form-post [{{objective-id :objective-id} :params
                                 :keys [t' locale] :as request}]
  (if-let [comment (helpers/request->comment request (get (friend/current-authentication) :username))]
    (let [{status :status stored-comment :result} (http-api/create-comment comment)]
      (cond
        (= status ::http-api/success)
        (let [comment-url (str utils/host-url "/objectives/" (:objective-id stored-comment))
              message (t' :comment-view/created-message)]
          (assoc (response/redirect comment-url) :flash message))

        (= status ::http-api/invalid-input) {:status 400}

        :else {:status 502}))
    {:status 400}))

;; QUESTIONS

(defn question-list [{{id :id} :route-params
                          :keys [uri t' locale] :as request}]
  (try 
    (let [objective-id (Integer/parseInt id)
          {objective-status :status objective :result} (http-api/get-objective objective-id)
          {questions-status :status questions :result} (http-api/retrieve-questions objective-id)]
      (cond
        (every? #(= ::http-api/success %) [objective-status questions-status])
        (views/question-list "question-list"
                             request
                             :objective (format-objective objective)
                             :questions questions
                             :doc {:title (str (:title objective) " | Objective[8]")
                                   :description (str (t' :question-list/questions-about) " " (:title objective))})
        
        (= objective-status ::http-api/not-found) (error-404-response request)
        (= questions-status ::http-api/not-found) (error-404-response request)
        (= questions-status ::http-api/invalid-input) {:status 400}
        :else {:status 500}))
    (catch NumberFormatException e
      (log/info "Invalid route: " e)
      (error-404-response request))))

(defn add-question-form-post [{:keys [uri t' locale] :as request}]
  (if-let [question (helpers/request->question request (get (friend/current-authentication) :username))]
    (let [{status :status stored-question :result} (http-api/create-question question)]
      (cond 
        (= status ::http-api/success)
        (let [question-url (str utils/host-url "/objectives/" (:objective-id stored-question) "/questions/" (:_id stored-question))
              message (t' :question-view/added-message)]
          (assoc (response/redirect question-url) :flash message))

        (= status ::http-api/invalid-input) {:status 400}

        :else {:status 502}))
    {:status 400}))

(defn question-detail [{{q-id :q-id id :id} :route-params
                        message :flash
                        :keys [uri t' locale]
                        :as request}]
  (try (let [{question-status :status question :result} (http-api/get-question (Integer/parseInt id) (Integer/parseInt q-id))
             {answer-status :status answers :result} (http-api/retrieve-answers (:objective-id question) (:_id question))
             {objective-status :status objective :result} (http-api/get-objective (:objective-id question))]
         (cond
           (every? #(= ::http-api/success %) [question-status answer-status objective-status])
               (views/question-detail "question-detail"
                                  request
                                  :objective (format-objective objective)
                                  :question question
                                  :answers answers) 
           (= question-status ::http-api/not-found) (error-404-response request)
           (= question-status ::http-api/invalid-input) {:status 400}
           :else {:status 500}))
       (catch NumberFormatException e
         (log/info "Invalid route: " e)
         (error-404-response request))))


;; ANSWERS

(defn add-answer-form-post [{:keys [uri t' locale] :as request}]
  (if-let [answer (helpers/request->answer-info request (get (friend/current-authentication) :username))]
    (let [{status :status stored-answer :result} (http-api/create-answer answer)]
      (cond
        (= status ::http-api/success)
        (let [answer-url (str utils/host-url "/objectives/" (:objective-id stored-answer)
                              "/questions/" (:question-id stored-answer))
              message (t' :question-view/added-answer-message)]
          (assoc (response/redirect answer-url) :flash message))

        (= status ::http-api/invalid-input) {:status 400}

        :else {:status 502}))
    {:status 400}))

;; WRITERS 

(defn candidate-list [{{id :id} :route-params
                       :keys [uri t' locale] :as request}]
  (try
    (let [objective-id (Integer/parseInt id)
          {objective-status :status objective :result} (http-api/get-objective objective-id)
          {candidate-status :status  candidates :result} (http-api/retrieve-candidates objective-id)]
      (cond
        (every? #(= ::http-api/success %) [candidate-status objective-status])
        (views/candidate-list "candidate-list"
                              request
                              :objective (format-objective objective)
                              :candidates candidates)
        #_(rendered-response candidate-list-page {:translation t'
                                                :locale (subs (str locale) 1)
                                                :doc-title (t' :invitation/doc-title)
                                                :doc-description (t' :invitation/doc-description)
                                                :objective (format-objective objective)
                                                :candidates candidates
                                                :uri uri
                                                :invitation (invitation? (:session request))
                                                :signed-in (signed-in?)})
        (= objective-status ::http-api/not-found) (error-404-response request)
        (= candidate-status ::http-api/not-found) (error-404-response request)
        (= candidate-status ::http-api/invalid-input) {:status 400}
        :else {:status 500}))
    (catch NumberFormatException e
      (log/info "Invalid route: " e)
      (error-404-response request))))

(defn invitation-form-post [{:keys [t' locale] :as request}]
  (if-let [invitation (helpers/request->invitation-info request (get (friend/current-authentication) :username))]
    (let [{status :status stored-invitation :result} (http-api/create-invitation invitation)]
      (cond
        (= status ::http-api/success)
        (let [objective-url (str utils/host-url "/objectives/" (:objective-id stored-invitation))
              invitation-url (str utils/host-url "/invitations/" (:uuid stored-invitation))
              message (str "Your invited writer can accept their invitation by going to " invitation-url)]
          (assoc (response/redirect objective-url) :flash message))
        (= status ::http-api/invalid-input) {:status 400}
        :else {:status 502}))
    {:status 400}))

(defn writer-invitation [{{uuid :uuid} :route-params :keys [t' locale session] :as request}]
  (let [{status :status
         invitation :result} (http-api/retrieve-invitation-by-uuid uuid)
         {:keys [objective-id _id] invitation-status :status} invitation]


    (cond

      (= status ::http-api/success)
      (cond
        (= invitation-status "active")
        (-> (str utils/host-url "/objectives/" objective-id "/writer-invitations/" _id)
            response/redirect
            (assoc :session session)
            (assoc-in [:session :invitation] {:uuid uuid :objective-id objective-id :invitation-id _id}))
 
        (= invitation-status "expired")
        (-> (str utils/host-url "/objectives/" objective-id)
            response/redirect
            (assoc :flash "This invitation has expired"))

        :else (error-404-response request))

      (= status ::http-api/not-found) (error-404-response request)
      :else {:status 500})))

(defn remove-invitation-credentials [response]
  (update-in response [:session] dissoc :invitation))

(defn accept-or-decline-invitation [{:keys [session t' locale uri] :as request}]
  (if-let [invitation-details (:invitation session)]
    (if (= ::http-api/success (:status (http-api/retrieve-invitation-by-uuid (:uuid invitation-details))))
      (let [{objective :result} (http-api/get-objective (:objective-id invitation-details))]
        (views/invitation-response "invitation-response"
                                   request
                                   :objective (format-objective objective)))
      (-> (error-404-response request)
          (assoc :session session)
          remove-invitation-credentials)) 
    (error-404-response request)))

(defn accept-invitation [{:keys [session]}]
  (if-let [invitation-credentials (:invitation session)]
    (let [candidate-writer {:invitee-id (get (friend/current-authentication) :username)
                            :invitation-uuid (:uuid invitation-credentials)
                            :objective-id (:objective-id invitation-credentials)}
          {status :status} (http-api/post-candidate-writer candidate-writer)]
      (cond
        (= status ::http-api/success)
        (-> (str utils/host-url "/objectives/" (:objective-id invitation-credentials) "/candidate-writers")
            response/redirect
            (assoc :session session)
            remove-invitation-credentials
            (utils/add-authorisation-role (utils/writer-for (:objective-id invitation-credentials))))
        
        :else {:status 500}))
    {:status 401}))

(defn decline-invitation [{session :session :as request}]
  (if-let [invitation-credentials (:invitation session)]
    (let [{status :status} (http-api/decline-invitation {:invitation-id (:invitation-id invitation-credentials)
                                                         :objective-id (:objective-id invitation-credentials)
                                                         :invitation-uuid (:uuid invitation-credentials)})] 
      (cond
       (#{::http-api/success ::http-api/invalid-input ::http-api/not-found} status)
       (-> (str utils/host-url)
                  response/redirect
                  (assoc :flash "Invitation declined")
                  (assoc :session session)
                  remove-invitation-credentials)
       
       :else {:status 500}))
    {:status 401}))

;;DRAFTS

(defn current-draft [{{id :id} :route-params
                      :keys [uri t' locale] :as request}]
  (try 
    (let [objective-id (Integer/parseInt id)
          {objective-status :status objective :result} (http-api/get-objective objective-id)]
      (cond
        (= objective-status ::http-api/success)
        (rendered-response current-draft-page {:translation t'
                                               :locale (subs (str locale) 1)
                                               :doc-title (str (:title objective) " | Objective[8]")
                                               :objective (format-objective objective)
                                               :uri uri
                                               :signed-in (signed-in?)})
        (= objective-status ::http-api/not-found) (error-404-response t' locale)
        :else {:status 500}))
    (catch NumberFormatException e
      (log/info "Invalid route: " e)
      (error-404-response t' locale))))

(defn edit-draft-get [{{objective-id :id} :route-params :as request}]
  (views/edit-draft "edit-draft" request :objective-id objective-id))

(defn edit-draft-with-preview [{{objective-id :id} :route-params params :params :as request}]
  (let [preview (hc/html (eh/to-hiccup (ec/mp (:content params))))]
    (views/edit-draft "edit-draft" request :objective-id objective-id :preview preview)))

(defn create-draft [{{o-id :id} :route-params :as request}]
  (let [{draft :result} (http-api/create-draft {})]
    (response/redirect (str "/objectives/" o-id "/drafts/" (:_id draft)))))
