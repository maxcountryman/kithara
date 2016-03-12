(ns kithara.base
  (:require [kithara.rabbitmq
             [consumer :as consumer]
             [message :as message]]
            [kithara.infrastructure :as i]
            [flake.core :as flake]
            [flake.utils :refer [base62-encode]]
            [peripheral.core :refer [defcomponent]]
            [clojure.tools.logging :as log]))

;; ## Logic

(defn- merge-defaults
  [result default]
  (if (map? result)
    (if (some #(contains? result %) [:ack? :nack? :reject? :done?])
      result
      (merge default result))
    default))

(defn- respond-to-message
  [result _ message]
  (let [message (cond-> message
                  (contains? result :requeue?)
                  (assoc :requeue? (:requeue? result)))]
    (condp #(get %2 %1) result
      :done?   nil
      :ack?    (message/ack message)
      :nack?   (message/nack message)
      :reject? (message/reject message)
      nil))
  result)

(defn- make-log-tag
  [result]
  (condp #(get %2 %1) result
    :done?   "[done]"
    :ack?    "[ack]"
    :nack?   "[nack]"
    :reject? "[reject]"
    ""))

(defn- make-log-info
  [{:keys [exchange routing-key body-raw]}]
  (format "exchange=%s, routing-key=%s, size=%d"
          (pr-str exchange)
          (pr-str routing-key)
          (alength ^bytes body-raw)))

(defn- write-logs
  [{:keys [message error] :as result} consumer-name message-data]
  (let [tag (make-log-tag result)
        info (make-log-info message-data)]
    (cond (instance? Throwable error)
          (log/errorf error
                      "[%s] %s %s (%s)"
                      consumer-name
                      tag
                      (or message "an exception occured.")
                      info)
          (some? error)
          (log/errorf "[%s] %s %s - %s (%s)"
                      consumer-name
                      tag
                      (or message "an exception occured.")
                      error
                      info)
          (some? message)
          (log/debugf "[%s] %s %s (%s)" consumer-name tag message info)
          :else
          (log/tracef "[%s] %s %s" consumer-name tag info))))

(defn wrap
  "Wrap the given function, taking a kithara message map, to ACK/NACK/REJECT
   based on the return value:

   - `{:reject? true, :requeue? <bool>}` -> REJECT (defaults to no requeue),
   - `{:nack? true, :requeue? <bool>}` -> NACK (defaults to requeue),
   - `{:ack? true}`-> ACK,
   - `{:done? true}` -> do nothing (was handled directly).

   Any non-map value (plus those without any of the flags set) will be
   interpreted as `default`.

   Additionally, the following keys can be given:

   - `:message`: a message to log,
   - `:error`: an exception to log.

   "
  [message-handler
   consumer-name
   & [{:keys [auto-ack? default error-default]
       :or {default {:ack? true}
            error-default {:nack? true}}
       :as opts}]]
  (fn [message]
    (try
      (-> (try
            (-> (message-handler message)
                (merge-defaults default))
            (catch Throwable t
              (merge-defaults {:error t} error-default)))
          (respond-to-message opts message)
          (write-logs consumer-name message))
      (catch Throwable t
        (log/errorf t "[%s] uncaught exception in consumer." consumer-name)))))

;; ## Consumer Tag

(defonce __flake-init__
  (flake/init!))

(defn- consumer-tag-for
  [consumer-name]
  (format "%s:%s"
          consumer-name
          (base62-encode (flake/generate))))

;; ## Component

(defn- run-consumer!
  [{:keys [consumer-name queue opts impl]}]
  (let [consumer-tag (or (:consumer-tag opts) (consumer-tag-for consumer-name))
        opts         (assoc opts
                            :consumer-tag consumer-tag
                            :auto-ack?    false)]
    (log/debugf "[%s] starting consumer (desired tag: '%s') ..."
                consumer-name
                consumer-tag)
    (consumer/consume queue opts impl)))

(defn- stop-consumer!
  [{:keys [consumer-name]} consumer-value]
  (log/debugf "[%s] stopping consumer ..." consumer-name)
  (consumer/cancel consumer-value))

(defcomponent BaseConsumer [consumer-name
                            queue
                            handler
                            opts]
  :assert/queue? (some? queue)
  :this/as *this*
  :impl
  (-> handler
      (wrap consumer-name opts)
      (consumer/from-fn queue opts))
  :consumer
  (run-consumer! *this*)
  #(stop-consumer! *this* %)

  i/HasQueue
  (set-queue [this queue]
    (assoc this :queue queue)))

;; ## Constructor

(defn consumer
  "Create a new kithara `BaseConsumer` using the given handler.

   Options:

   - `:consumer-name`: the consumer's name,
   - `:default`: the default result map (see `kithara.base/wrap`),
   - `:as`: the coercer to use for incoming message bodies,
   - `:consumer-tag`,
   - `:local?`,
   - `:exclusive?`,
   - `:arguments`.

   See the documentation of `basic.consume` for an explanation of these
   options."
  [handler
   {:keys [consumer-name]
    :or {consumer-name "kithara"}
    :as opts}]
  {:pre [handler]}
  (map->BaseConsumer
    {:consumer-name consumer-name
     :handler       handler
     :opts          (dissoc opts :consumer-name :auto-ack?)}))