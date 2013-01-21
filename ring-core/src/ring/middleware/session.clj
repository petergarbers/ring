(ns ring.middleware.session
  "Session manipulation."
  (:use ring.middleware.cookies
        [ring.middleware.session store memory])
  (:require [ring.middleware.cookies :as cookies]
            [ring.middleware.session.store :as store]
            [ring.middleware.session.memory :as mem]))

(defn session-options
  [options]
  {:store (options :store (mem/memory-store))
   :cookie-name (options :cookie-name "ring-session")
   :cookie-attrs (merge {:path "/"}
                        (options :cookie-attrs)
                        (if-let [root (options :root)]
                          {:path root}))})

(defn session-request-fn
  [{:keys [store cookie-name]}]
  (fn [request]
    (let [req-key  (get-in request [:cookies cookie-name :value])
          session  (store/read-session store req-key)
          session-key (if session req-key)]
      (merge request {:session (or session {})
                      :session/key session-key}))))

(defn session-response-fn
  [{:keys [store cookie-name cookie-attrs]}]
  (fn [{session-key :session/key :as response}]
    (when (seq (dissoc response :session/key))
      (let [new-session-key (when (contains? response :session)
                              (if-let [session (response :session)]
                                (store/write-session store session-key session)
                                (when session-key
                                  (store/delete-session store session-key))))
            response (dissoc response :session)
            cookie   {cookie-name
                      (merge cookie-attrs
                             (response :session-cookie-attrs)
                             {:value new-session-key})}]
        (if (and new-session-key (not= session-key new-session-key))
          (assoc response :cookies (merge (response :cookies) cookie))
          response)))))

(defn wrap-session
  "Reads in the current HTTP session map, and adds it to the :session key on
  the request. If a :session key is added to the response by the handler, the
  session is updated with the new value. If the value is nil, the session is
  deleted.

  The following options are available:
    :store
      An implementation of the SessionStore protocol in the
      ring.middleware.session.store namespace. This determines how the
      session is stored. Defaults to in-memory storage
      (ring.middleware.session.store.MemoryStore).
    :root
      The root path of the session. Any path above this will not be able to
      see this session. Equivalent to setting the cookie's path attribute.
      Defaults to \"/\".
    :cookie-name
      The name of the cookie that holds the session key. Defaults to
      \"ring-session\"
    :cookie-attrs
      A map of attributes to associate with the session cookie. Defaults
      to {}."
  ([handler]
     (wrap-session handler {}))
  ([handler options]
     (let [options (session-options options)
           session-request (session-request-fn options)
           session-response (session-response-fn options)]
       (cookies/wrap-cookies
        (fn [request]
          (let [new-request (session-request request)
                session-key (:session/key new-request)]
            (-> new-request
                handler
                (assoc :session/key session-key)
                session-response)))))))
