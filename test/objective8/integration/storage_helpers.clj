(ns objective8.integration.storage-helpers
  (:require [clj-time.core :as tc] 
            [objective8.storage.storage :as storage] 
            [objective8.actions :as actions]))


(def username-index (atom 0))

(defn generate-unique-username []
  (str "username-" (swap! username-index inc)))

(defn store-a-user [] (storage/pg-store! {:entity :user
                                          :twitter-id "twitter-TWITTER_ID"
                                          :username (generate-unique-username)}))

(defn store-an-open-objective 
  
  ([]
   (store-an-open-objective {}))

  ([entities]
   (let [{user-id :_id} (get entities :user (store-a-user))]
     (storage/pg-store! {:entity :objective
                         :status "open"
                         :title "test title"
                         :description "test description"
                         :goals "test goal"
                         :created-by-id user-id
                         :end-date (str (tc/from-now (tc/days 1)))}))))

(defn store-an-objective-due-for-drafting
([] 
 (store-an-objective-due-for-drafting {})) 
 ([required-entities] 
  (let [{user-id :_id} (get required-entities :user (store-a-user))]
    (storage/pg-store! {:entity :objective
                        :created-by-id user-id
                        :status "open"
                        :end-date (str (tc/ago (tc/days 1)))}))))

(defn store-an-objective-in-draft []
  (let [{user-id :_id} (store-a-user)]
    (storage/pg-store! {:entity :objective
                        :created-by-id user-id
                        :end-date (str (tc/ago (tc/days 1))) 
                        :status "drafting"})))

(defn store-a-comment
  ([]
   (store-a-comment {:user (store-a-user) :entity (store-an-open-objective)}))

  ([required-entities]
   (let [{created-by-id :_id} (get required-entities :user (store-a-user))
         {:keys [_id objective-id global-id entity]} (get required-entities :entity (store-an-open-objective))
         objective-id (if (= entity :objective) _id objective-id)]
     (storage/pg-store! {:entity :comment
                         :created-by-id created-by-id
                         :objective-id objective-id
                         :comment-on-id global-id
                         :comment "The comment"}))))

(defn store-an-invitation
  ([] (store-an-invitation {}))

  ([required-entities]
   (let [{invited-by-id :_id} (get required-entities :user (store-a-user))
         {objective-id :_id} (get required-entities :objective (store-an-open-objective))
         status (get required-entities :status "active")]
     (storage/pg-store! {:entity :invitation
                         :uuid (java.util.UUID/randomUUID)
                         :status status
                         :invited-by-id invited-by-id
                         :objective-id objective-id
                         :reason "some reason"
                         :writer-name "writer name"}))))

(defn store-a-question
  ([]
   (store-a-question {}))

  ([required-entities]
   (let [{created-by-id :_id} (get required-entities :user (store-a-user))
         {objective-id :_id} (get required-entities :objective (store-an-open-objective))]
     (storage/pg-store! {:entity :question
                         :created-by-id created-by-id
                         :objective-id objective-id
                         :question "A question"}))))

(defn store-an-answer
  ([]
   (store-an-answer {}))

  ([required-entities]
   (let [{created-by-id :_id} (get required-entities :user (store-a-user))
         {objective-id :objective-id q-id :_id} (get required-entities :question (store-a-question))]
     (storage/pg-store! {:entity :answer
                         :created-by-id created-by-id
                         :objective-id objective-id
                         :question-id q-id
                         :answer "An answer"}))))

(defn store-an-up-down-vote
  ([global-id vote-type]
   (store-an-up-down-vote global-id vote-type {}))

  ([global-id vote-type required-entities]
   (let [user (get required-entities :user (store-a-user))]
     (storage/pg-store! {:entity :up-down-vote
                         :global-id global-id
                         :vote-type vote-type
                         :created-by-id (:_id user)}))))

(defn store-a-candidate
  ([]
   (store-a-candidate {}))

  ([required-entities]
   (let [{user-id :_id} (get required-entities :user (store-a-user))
         {i-id :_id o-id :objective-id} (get required-entities :invitation (store-an-invitation))]
     (storage/pg-store! {:entity :candidate
                         :user-id user-id
                         :objective-id o-id
                         :invitation-id i-id}))))

(defn store-a-draft
  ([]
   (store-a-draft {}))

  ([required-entities]
   (let [{objective-id :_id} (get required-entities :objective (store-an-objective-in-draft))
         ;; NB: Candidate id not required, but for consistency, the submitter should be authorised to draft documents for this objective
         {submitter-id :user-id} (get required-entities :submitter (store-a-candidate))]
     (storage/pg-store! {:entity :draft
                         :submitter-id submitter-id
                         :objective-id objective-id
                         :content [["h1" "A Heading"] ["p" "A paragraph"]]}))))

(defn start-drafting! [objective-id]
  (actions/start-drafting! objective-id)) 

(defn retrieve-invitation [invitation-id]
  (-> (storage/pg-retrieve {:entity :invitation :_id invitation-id}) 
      :result
      first))

(defn store-a-star 
  ([]
   (store-a-star {})) 

  ([entities]
   (let [{objective-id :_id} (get entities :objective (store-an-open-objective))
         {created-by-id :_id} (get entities :user (store-a-user))]
     (storage/pg-store! {:entity :star
                         :created-by-id created-by-id
                         :objective-id objective-id
                         :active true}))))
