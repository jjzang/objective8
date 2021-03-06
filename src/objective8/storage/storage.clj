(ns objective8.storage.storage
  (:require [korma.core :as korma]
            [korma.db :as kdb]
            [objective8.storage.uris :as uris]
            [objective8.storage.mappings :as mappings]
            [objective8.utils :as utils]))

(defn pg-create-global-identifier []
  (first (korma/exec-raw
          "INSERT INTO objective8.global_identifiers values(default) RETURNING _id"
          :results)))

(defn insert
  "Wrapper around Korma's insert call"
  [entity data]
  (if (#{:answer :objective :draft :comment} (:entity data))
    (kdb/transaction
     (let [{global-id :_id} (pg-create-global-identifier)]
       (korma/insert entity (korma/values (assoc data :global-id global-id)))))
    (korma/insert entity (korma/values data))))

(defn pg-store!
  "Transform a map according to its :entity value and save it in the database"
  [{:keys [entity] :as m}]
  (if-let [ent (mappings/get-mapping m)]
    (insert ent m)
    (throw (Exception. (str "Could not find database mapping for " entity)))))

(defn select
  "Wrapper around Korma's select call"
  [entity where options]
  (let [{:keys [limit]} options
        {:keys [field ordering]} (get options :sort {:field :_created_at
                                                     :ordering :ASC})
        select-query (-> (korma/select* entity)
                         (korma/where where)
                         (korma/limit limit)
                         (korma/order field ordering))
        with-relation (first (keys (:rel entity)))]
    (if with-relation
      (-> select-query
          (korma/with (mappings/get-mapping {:entity (keyword with-relation)}))
          (korma/select))
      (korma/select select-query))))

(defn pg-retrieve
  "Retrieves objects from the database based on a query map

   - The map must include an :entity key
   - Hyphens in key words are replaced with underscores"

  ([query]
   (pg-retrieve query {}))

  ([{:keys [entity] :as query} options]
   (if entity
     (let [result (select (mappings/get-mapping query) (-> (dissoc query :entity)
                                                           (utils/transform-map-keys mappings/key->db-column)) options)]
       {:query query
        :options options
        :result result})
     (throw (Exception. "Query map requires an :entity key")))))

(defn pg-retrieve-entity-by-uri [uri & options]
  "Retrieves an entity from the DB by its uri.  Returns nil if entity
  not found, or the uri is invalid.  By default, the global-id for the
  entity is not included.

Options: :with-global-id -- includes the global-id in the entity."
  (let [options (set options)
        optionally-remove-global-id (if (options :with-global-id)
                                      identity
                                      #(dissoc % :global-id))]
    (when-let [query (uris/uri->query uri)]
      (-> (pg-retrieve query)
          :result
          first
          (assoc :uri uri)
          optionally-remove-global-id))))

(defn pg-retrieve-entity-by-global-id [global-id]
  (let [entity-query (-> (korma/exec-raw ["
SELECT _id, 'objective' AS entity FROM objective8.objectives WHERE global_id=?

UNION

SELECT _id, 'draft' AS entity FROM objective8.drafts WHERE global_id=?
" [global-id, global-id]] :results)
                         first
                         (update-in [:entity] keyword))]
    (-> (pg-retrieve entity-query)
        :result
        first)))

(defn update [entity new-fields where]
  (korma/update entity 
                (korma/set-fields new-fields) 
                (korma/where where)))

(defn pg-update-bearer-token!
  "Wrapper around Korma's update call"
  [{:keys [entity] :as m}]
  (if-let [bearer-token-entity (mappings/get-mapping m)] 
    (let [where {:bearer_name (:bearer-name m)}]
      (update bearer-token-entity m where)) 
    (throw (Exception. (str "Could not find database mapping for " entity)))))

(defn pg-update-invitation-status! [invitation new-status]
  (update (mappings/get-mapping {:entity :invitation})
          (assoc invitation :status new-status)
          {:uuid (:uuid invitation)}))

(defn pg-update-objective-status! [objective new-status]
  (update (mappings/get-mapping {:entity :objective})
          (assoc objective 
                 :status new-status)
          {:_id (:_id objective)}))

(defn with-aggregate-votes [unmap-fn]
  (fn [m] (-> (unmap-fn m)
              (assoc :votes {:up (or (:up_votes m) 0) :down (or (:down_votes m) 0)}))))

(def unmap-answer-with-votes
  (-> (mappings/unmap :answer)
      mappings/with-username-if-present
      (mappings/with-columns [:created-by-id :objective-id :question-id :global-id])
      with-aggregate-votes))

(defn pg-retrieve-answers-with-votes-for-question [question-id]
  (apply vector (map unmap-answer-with-votes
                     (korma/exec-raw ["
SELECT answers.*, up_votes, down_votes FROM objective8.answers AS answers
LEFT JOIN (SELECT global_id, count(vote) as down_votes
           FROM objective8.up_down_votes
           WHERE vote < 0 GROUP BY global_id) AS agg
ON agg.global_id = answers.global_id
LEFT JOIN (SELECT global_id, count(vote) as up_votes
           FROM objective8.up_down_votes
           WHERE vote > 0 GROUP BY global_id) AS agg2
ON agg2.global_id = answers.global_id
WHERE answers.question_id = ?
ORDER BY answers._created_at ASC
LIMIT 50" [question-id]] :results))))

(def unmap-comments-with-votes
  (-> (mappings/unmap :comment)
      (mappings/with-columns [:comment-on-id :created-by-id :global-id :objective-id])
      mappings/with-username-if-present
      with-aggregate-votes))

(defn pg-retrieve-comments-with-votes [global-id]
  (apply vector (map unmap-comments-with-votes
                     (korma/exec-raw["
SELECT comments.*, up_votes, down_votes FROM objective8.comments AS comments
LEFT JOIN (SELECT global_id, count(vote) as down_votes
           FROM objective8.up_down_votes
           WHERE vote < 0 GROUP BY global_id) AS agg
ON agg.global_id = comments.global_id
LEFT JOIN (SELECT global_id, count(vote) as up_votes
           FROM objective8.up_down_votes
           WHERE vote > 0 GROUP BY global_id) AS agg2
ON agg2.global_id = comments.global_id
WHERE comments.comment_on_id = ?
ORDER BY comments._created_at DESC
LIMIT 50" [global-id]] :results))))

(defn pg-retrieve-starred-objectives [user-id]
  (let [unmap-objective (first (get mappings/objective :transforms))]
  (apply vector (map unmap-objective 
                     (korma/exec-raw ["
SELECT objectives.*, users.username FROM objective8.objectives AS objectives
JOIN objective8.stars AS stars
ON stars.objective_id = objectives._id
JOIN objective8.users AS users
ON objectives.created_by_id = users._id
WHERE stars.active=true AND stars.created_by_id=?" [user-id]] :results)))))
