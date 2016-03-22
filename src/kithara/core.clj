(ns kithara.core
  (:require [kithara.components
             [base-consumer :as base-consumer]
             [channel-consumer :as channel-consumer]
             [connected-consumer :as connected-consumer]
             [dlx-consumer :as dlx-consumer]
             [queue-consumer :as queue-consumer]]
            [potemkin :refer [import-vars]]))

(import-vars
  [kithara.components.base-consumer      consumer]
  [kithara.components.channel-consumer   with-channel]
  [kithara.components.connected-consumer with-connection]
  [kithara.components.queue-consumer
   with-queue
   with-server-named-queue]
  [kithara.components.dlx-consumer
   with-dead-letter-backoff])
