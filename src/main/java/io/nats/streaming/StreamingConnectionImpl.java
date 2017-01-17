/*
 *  Copyright (c) 2015-2016 Apcera Inc. All rights reserved. This program and the accompanying
 *  materials are made available under the terms of the MIT License (MIT) which accompanies this
 *  distribution, and is available at http://opensource.org/licenses/MIT
 */

package io.nats.streaming;

import static io.nats.streaming.NatsStreaming.ERR_CONNECTION_REQ_TIMEOUT;
import static io.nats.streaming.NatsStreaming.ERR_SUB_REQ_TIMEOUT;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import io.nats.client.NUID;
import io.nats.client.Nats;
import io.nats.streaming.protobuf.Ack;
import io.nats.streaming.protobuf.CloseRequest;
import io.nats.streaming.protobuf.CloseResponse;
import io.nats.streaming.protobuf.ConnectRequest;
import io.nats.streaming.protobuf.ConnectResponse;
import io.nats.streaming.protobuf.MsgProto;
import io.nats.streaming.protobuf.PubAck;
import io.nats.streaming.protobuf.PubMsg;
import io.nats.streaming.protobuf.SubscriptionRequest;
import io.nats.streaming.protobuf.SubscriptionResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StreamingConnectionImpl implements StreamingConnection, io.nats.client.MessageHandler {

    static final String ERR_MANUAL_ACK = NatsStreaming.PFX + "cannot manually ack in auto-ack mode";

    static final Logger logger = LoggerFactory.getLogger(StreamingConnectionImpl.class);

    final ReadWriteLock mu = new ReentrantReadWriteLock();

    private String clientId;
    private String clusterId;
    String pubPrefix; // Publish prefix set by streaming, append our subject.
    String subRequests; // Subject to send subscription requests.
    String unsubRequests; // Subject to send unsubscribe requests.
    String subCloseRequests; // Subject to send subscription close requests.
    String closeRequests; // Subject to send close requests.
    String ackSubject; // publish acks
    io.nats.client.Subscription ackSubscription;
    String hbInbox;
    io.nats.client.Subscription hbSubscription;
    io.nats.client.MessageHandler hbCallback;

    Map<String, Subscription> subMap;
    Map<String, AckClosure> pubAckMap;
    BlockingQueue<PubAck> pubAckChan;
    Options opts;
    io.nats.client.Connection nc;

    Timer ackTimer = new Timer(true);

    boolean ncOwned = false;

    ExecutorService exec = Executors.newCachedThreadPool();

    protected StreamingConnectionImpl() {

    }

    StreamingConnectionImpl(String stanClusterId, String clientId) {
        this(stanClusterId, clientId, null);
    }

    StreamingConnectionImpl(String stanClusterId, String clientId, Options opts) {
        this.clusterId = stanClusterId;
        this.clientId = clientId;

        if (opts == null) {
            this.opts = new Options.Builder().build();
        } else {
            this.opts = opts;
            // Check if the user has provided a connection as an option
            if (this.opts.getNatsConn() != null) {
                setNatsConnection(this.opts.getNatsConn());
            }
        }
    }

    // Connect will form a connection to the STAN subsystem.
    StreamingConnectionImpl connect() throws IOException, InterruptedException {
        boolean exThrown = false;
        io.nats.client.Connection nc = getNatsConnection();
        // Create a NATS connection if it doesn't exist
        if (nc == null) {
            nc = createNatsConnection();
            setNatsConnection(nc);
            ncOwned = true;
        } else if (!nc.isConnected()) {
            // Bail if the custom NATS connection is disconnected
            throw new IOException(NatsStreaming.ERR_BAD_CONNECTION);
        }

        try {
            // Create a heartbeat inbox
            hbInbox = nc.newInbox();
            hbCallback = new MessageHandler() {
                @Override
                public void onMessage(Message msg) {
                    processHeartBeat(msg);
                }
            };
            hbSubscription = nc.subscribe(hbInbox, hbCallback);

            // Send Request to discover the cluster
            String discoverSubject = String.format("%s.%s", opts.getDiscoverPrefix(), clusterId);
            ConnectRequest req = ConnectRequest.newBuilder().setClientID(clientId)
                    .setHeartbeatInbox(hbInbox).build();
            // logger.trace("Sending ConnectRequest:\n{}", req.toString().trim());
            byte[] bytes = req.toByteArray();
            Message reply = null;
            reply = nc.request(discoverSubject, bytes, opts.getConnectTimeout().toMillis());
            if (reply == null) {
                throw new IOException(ERR_CONNECTION_REQ_TIMEOUT);
            }
            ConnectResponse cr = ConnectResponse.parseFrom(reply.getData());
            if (!cr.getError().isEmpty()) {
                // This is already a properly formatted streaming error message
                // (StreamingConnectionImpl.SERVER_ERR_INVALID_CLIENT)
                throw new IOException(cr.getError());
            }
            logger.trace("Received ConnectResponse:\n{}", cr);

            // Capture cluster configuration endpoints to publish and
            // subscribe/unsubscribe.
            pubPrefix = cr.getPubPrefix();
            subRequests = cr.getSubRequests();
            unsubRequests = cr.getUnsubRequests();
            subCloseRequests = cr.getSubCloseRequests();
            closeRequests = cr.getCloseRequests();

            // Setup the ACK subscription
            ackSubject = String.format("%s.%s", NatsStreaming.DEFAULT_ACK_PREFIX, NUID.nextGlobal
                    ());
            ackSubscription = nc.subscribe(ackSubject, new MessageHandler() {
                public void onMessage(io.nats.client.Message msg) {
                    processAck(msg);
                }
            });
            ackSubscription.setPendingLimits(1024 ^ 2, 32 * 1024 ^ 2);
            pubAckMap = new HashMap<>();

            // Create Subscription map
            subMap = new HashMap<>();

            pubAckChan = new LinkedBlockingQueue<PubAck>(opts.getMaxPubAcksInFlight());
        } catch (IOException e) {
            exThrown = true;
            if (io.nats.client.Nats.ERR_TIMEOUT.equals(e.getMessage())) {
                IOException te = new IOException(ERR_CONNECTION_REQ_TIMEOUT, e);
                throw te;
            } else {
                throw e;
            }
        } finally {
            if (exThrown) {
                try {
                    close();
                } catch (Exception e2) {
                    /* NOOP -- can't do anything if close fails */
                }
            }
        }
        return this;
    }

    io.nats.client.Connection createNatsConnection() throws IOException {
        // Create a NATS connection if it doesn't exist
        io.nats.client.Connection nc = null;
        if (getNatsConnection() == null) {
            if (opts.getNatsUrl() != null) {
                nc = Nats.connect(opts.getNatsUrl());
            } else {
                nc = Nats.connect();
            }
            ncOwned = true;
        }
        return nc;
    }

    @Override
    public void close() throws IOException, InterruptedException {
        logger.trace("In STAN close()");
        io.nats.client.Connection nc = null;
        this.lock();
        try {
            // Capture for NATS calls below
            if (getNatsConnection() == null) {
                // We are already closed
                logger.debug("stan: NATS connection already closed");
                return;
            }

            // Capture for NATS calls below.
            nc = getNatsConnection();

            // if ncOwned, we close it at the end
            try {
                // Signals we are closed.
                setNatsConnection(null);

                // Now close ourselves.
                if (getAckSubscription() != null) {
                    try {
                        getAckSubscription().unsubscribe();
                    } catch (Exception e) {
                        logger.debug(
                                "stan: error unsubscribing from acks ('{}')", e.getMessage(), e);
                    }
                }

                if (getHbSubscription() != null) {
                    try {
                        getHbSubscription().unsubscribe();
                    } catch (Exception e) {
                        logger.debug("stan: error unsubscribing from heartbeats ('{}')",
                                e.getMessage(), e);
                    }
                }

                CloseRequest req = CloseRequest.newBuilder().setClientID(clientId).build();
                logger.trace("CLOSE request: [{}]", req);
                byte[] bytes = req.toByteArray();
                Message reply = null;
                reply = nc.request(closeRequests, bytes, opts.getConnectTimeout().toMillis());
                if (reply == null) {
                    throw new IOException(NatsStreaming.ERR_CLOSE_REQ_TIMEOUT);
                }
                logger.trace("CLOSE response: [{}]", reply);
                if (reply.getData() != null) {
                    CloseResponse cr = CloseResponse.parseFrom(reply.getData());

                    if (!cr.getError().isEmpty()) {
                        throw new IOException(cr.getError());
                    }
                }
            } finally {
                if (ncOwned) {
                    try {
                        nc.close();
                    } catch (Exception ignore) {
                        logger.debug("NATS connection was null in close()");
                    }
                }
            } // first finally
        } finally {
            this.unlock();
        }
    }

    protected AckClosure createAckClosure(AckHandler ah, BlockingQueue<String> ch) {
        return new AckClosure(ah, ch);
    }

    protected SubscriptionImpl createSubscription(String subject, String qgroup,
                                                  io.nats.streaming.MessageHandler cb,
                                                  StreamingConnectionImpl conn,
                                                  SubscriptionOptions opts) {
        return new SubscriptionImpl(subject, qgroup, cb, conn, opts);
    }

    protected void processHeartBeat(Message msg) {
        // No payload assumed, just reply.
        io.nats.client.Connection nc;
        this.rLock();
        nc = this.nc;
        this.rUnlock();
        if (nc != null) {
            try {
                nc.publish(msg.getReplyTo(), null);
                logger.debug("Sent heartbeat response");
            } catch (IOException e) {
                logger.warn("stan: error publishing heartbeat response: {}", e.getMessage());
                logger.debug("Full stack trace:", e);
            }
        }
    }

    BlockingQueue<String> createErrorChannel() {
        return new LinkedBlockingQueue<>();
    }

    // Publish will publish to the cluster and wait for an ACK.
    @Override
    public void publish(String subject, byte[] data) throws IOException, InterruptedException {
        final BlockingQueue<String> ch = createErrorChannel();
        publish(subject, data, null, ch);
        String err = null;
        try {
            err = ch.take();
            if (!err.isEmpty()) {
                throw new IOException(err);
            }
        } catch (InterruptedException e) {
            logger.debug("stan: publish interrupted");
            logger.debug("Full stack trace:", e);
            // Thread.currentThread().interrupt();
        }
    }

    /*
     * PublishAsync will publish to the cluster on pubPrefix+subject and asynchronously process the
     * ACK or error state. It will return the GUID for the message being sent.
     */
    @Override
    public String publish(String subject, byte[] data, AckHandler ah) throws IOException,
            InterruptedException {
        return publish(subject, data, ah, null);
    }

    String publish(String subject, byte[] data, AckHandler ah, BlockingQueue<String> ch)
            throws IOException, InterruptedException {
        String subj = null;
        String ackSubject = null;
        Duration ackTimeout;
        BlockingQueue<PubAck> pac;
        final AckClosure a;
        final PubMsg pe;
        String guid;
        byte[] bytes;

        a = createAckClosure(ah, ch);
        this.lock();
        try {
            if (getNatsConnection() == null) {
                throw new IllegalStateException(NatsStreaming.ERR_CONNECTION_CLOSED);
            }

            subj = pubPrefix + "." + subject;
            guid = NUID.nextGlobal();
            PubMsg.Builder pb =
                    PubMsg.newBuilder().setClientID(clientId).setGuid(guid).setSubject(subject);
            if (data != null) {
                pb = pb.setData(ByteString.copyFrom(data));
            }
            pe = pb.build();
            bytes = pe.toByteArray();

            // Map ack to guid
            pubAckMap.put(guid, a);
            // snapshot
            ackSubject = this.ackSubject;
            ackTimeout = opts.getAckTimeout();
            pac = pubAckChan;
        } finally {
            this.unlock();
        }

        // Use the buffered channel to control the number of outstanding acks.
        try {
            pac.put(PubAck.getDefaultInstance());
        } catch (InterruptedException e) {
            logger.warn("Publish operation interrupted", e);
            // Eat this because you can't really do anything with it
            // Thread.currentThread().interrupt();
        }

        try {
            nc.publish(subj, ackSubject, bytes, true);
        } catch (IOException e) {
            removeAck(guid);
            throw (e);
        }

        // Setup the timer for expiration.
        this.lock();
        try {
            a.ackTask = createAckTimerTask(guid);
            ackTimer.schedule(a.ackTask, ackTimeout.toMillis());
        } catch (Exception e) {
            throw e;
        } finally {
            this.unlock();
        }
        return guid;
    }

    @Override
    public Subscription subscribe(String subject, io.nats.streaming.MessageHandler cb)
            throws IOException, InterruptedException {
        return subscribe(subject, cb, null);
    }

    @Override
    public Subscription subscribe(String subject, io.nats.streaming.MessageHandler cb,
                                  SubscriptionOptions opts) throws IOException,
            InterruptedException {
        return subscribe(subject, null, cb, opts);
    }

    @Override
    public Subscription subscribe(String subject, String queue, io.nats.streaming.MessageHandler cb)
            throws IOException, InterruptedException {
        return subscribe(subject, queue, cb, null);
    }

    @Override
    public Subscription subscribe(String subject, String queue, io.nats.streaming.MessageHandler cb,
                                  SubscriptionOptions opts) throws IOException,
            InterruptedException {
        SubscriptionImpl sub = null;
        io.nats.client.Connection nc = null;
        this.lock();
        try {
            if (getNatsConnection() == null) {
                sub = null;
                throw new IllegalStateException(NatsStreaming.ERR_CONNECTION_CLOSED);
            }
            sub = createSubscription(subject, queue, cb, this, opts);

            // Register subscription.
            subMap.put(sub.inbox, sub);
            nc = getNatsConnection();
        } finally {
            this.unlock();
        }

        // Hold lock throughout.
        sub.wLock();
        try {
            // Listen for actual messages
            io.nats.client.Subscription nsub = nc.subscribe(sub.inbox, this);
            sub.inboxSub = nsub;

            // Create a subscription request
            // FIXME(dlc) add others.
            SubscriptionRequest sr = createSubscriptionRequest(sub);

            Message reply = null;
            // logger.trace("Sending SubscriptionRequest:\n{}", sr);
            reply = nc.request(subRequests, sr.toByteArray(), 2L, TimeUnit.SECONDS);
            if (reply == null) {
                sub.inboxSub.unsubscribe();
                throw new IOException(ERR_SUB_REQ_TIMEOUT);
            }

            SubscriptionResponse response = null;
            try {
                response = SubscriptionResponse.parseFrom(reply.getData());
            } catch (InvalidProtocolBufferException e) {
                sub.inboxSub.unsubscribe();
                throw e;
            }
            // logger.trace("Received SubscriptionResponse:\n{}", response);
            if (!response.getError().isEmpty()) {
                sub.inboxSub.unsubscribe();
                throw new IOException(response.getError());
            }
            sub.setAckInbox(response.getAckInbox());
        } finally {
            sub.wUnlock();
        }
        return sub;
    }

    protected SubscriptionRequest createSubscriptionRequest(SubscriptionImpl sub) {
        SubscriptionOptions subOpts = sub.getOptions();
        SubscriptionRequest.Builder srb = SubscriptionRequest.newBuilder();
        String clientId = sub.getConnection().getClientId();
        String queue = sub.getQueue();
        String subject = sub.getSubject();

        srb.setClientID(clientId).setSubject(subject).setQGroup(queue == null ? "" : queue)
                .setInbox(sub.getInbox()).setMaxInFlight(subOpts.getMaxInFlight())
                .setAckWaitInSecs((int) subOpts.getAckWait().getSeconds());

        switch (subOpts.getStartAt()) {
            case First:
                break;
            case LastReceived:
                break;
            case NewOnly:
                break;
            case SequenceStart:
                srb.setStartSequence(subOpts.getStartSequence());
                break;
            case TimeDeltaStart:
                long delta = ChronoUnit.NANOS.between(subOpts.getStartTime(), Instant.now());
                srb.setStartTimeDelta(delta);
                break;
            case UNRECOGNIZED:
            default:
                break;
        }
        srb.setStartPosition(subOpts.getStartAt());

        if (subOpts.getDurableName() != null) {
            srb.setDurableName(subOpts.getDurableName());
        }

        return srb.build();
    }

    // Process an ack from the STAN cluster
    protected void processAck(Message msg) {
        PubAck pa = null;
        Exception ex = null;
        try {
            pa = PubAck.parseFrom(msg.getData());
            // logger.trace("Received PubAck:\n{}", pa);
        } catch (InvalidProtocolBufferException e) {
            logger.error("stan: error unmarshaling PubAck");
            logger.debug("Full stack trace: ", e);
            return;
        }

        // Remove
        AckClosure ackClosure = removeAck(pa.getGuid());
        if (ackClosure != null) {
            // Capture error if it exists.
            String ackError = pa.getError();

            if (ackClosure.ah != null) {
                if (!ackError.isEmpty()) {
                    ex = new IOException(ackError);
                }
                // Perform the ackHandler callback
                ackClosure.ah.onAck(pa.getGuid(), ex);
            } else if (ackClosure.ch != null) {
                try {
                    ackClosure.ch.put(ackError);
                } catch (InterruptedException e) {
                    logger.debug("stan: processAck interrupted");
                }
            }
        }
    }

    TimerTask createAckTimerTask(String guid) {
        TimerTask task = new java.util.TimerTask() {
            public void run() {
                try {
                    processAckTimeout(guid);
                } catch (Exception e) {
                    // catch exception to prevent the timer to be closed
                    logger.error("stan: error encountered during processAckTimeout, will cancel this timer task", e);
                    // cancel this task
                    cancel();
                }
            }
        };
        return task;
    }

    protected void processAckTimeout(String guid) {
        AckClosure ackClosure = removeAck(guid);
        if (ackClosure == null) {
            return;
        }
        if (ackClosure.ah != null) {
            ackClosure.ah.onAck(guid, new TimeoutException(NatsStreaming.ERR_TIMEOUT));
        } else if (ackClosure.ch != null && !ackClosure.ch.offer(NatsStreaming.ERR_TIMEOUT)) {
                logger.warn("stan: processAckTimeout unable to write timeout error to ack channel");
        }
    }

    protected AckClosure removeAck(String guid) {
        AckClosure ackClosure = null;
        BlockingQueue<PubAck> pac = null;
        TimerTask timerTask = null;
        this.lock();
        try {
            ackClosure = pubAckMap.get(guid);
            if (ackClosure != null) {
                timerTask = ackClosure.ackTask;
                pubAckMap.remove(guid);
            }
            pac = pubAckChan;
        } finally {
            this.unlock();
        }

        // Cancel timer if needed
        if (timerTask != null) {
            timerTask.cancel();
        }

        // Remove from channel to unblock async publish
        if (ackClosure != null && pac.size() > 0) {
            try {
                // remove from queue to unblock publish
                pac.take();
            } catch (InterruptedException e) {
                logger.warn("stan: interrupted during removeAck for {}", guid);
                logger.debug("Full stack trace:", e);
                // Thread.currentThread().interrupt();
            }
        }

        return ackClosure;
    }

    @Override
    public void onMessage(io.nats.client.Message msg) {
        // For handling inbound NATS messages
        processMsg(msg);
    }

    protected io.nats.streaming.Message createStanMessage(MsgProto msgp) {
        return new io.nats.streaming.Message(msgp);
    }

    protected void processMsg(io.nats.client.Message raw) {
        io.nats.streaming.Message stanMsg = null;
        boolean isClosed = false;
        SubscriptionImpl sub = null;
        io.nats.client.Connection nc = null;

        try {
            // logger.trace("In processMsg, msg = {}", raw);
            MsgProto msgp = MsgProto.parseFrom(raw.getData());
            // logger.trace("processMsg received MsgProto:\n{}", msgp);
            stanMsg = createStanMessage(msgp);
        } catch (InvalidProtocolBufferException e) {
            logger.error("stan: error unmarshaling msg");
            logger.debug("msg: {}", raw);
            logger.debug("full stack trace:", e);
        }

        // Lookup the subscription
        lock();
        try {
            nc = getNatsConnection();
            isClosed = (nc == null);
            sub = (SubscriptionImpl) subMap.get(raw.getSubject());
        } catch (Exception e) {
            throw e;
        } finally {
            unlock();
        }

        // Check if sub is no longer valid or connection has been closed.
        if (sub == null || isClosed) {
            return;
        }

        // Store in msg for backlink
        stanMsg.setSubscription(sub);

        io.nats.streaming.MessageHandler cb = null;
        String ackSubject = null;
        boolean isManualAck = false;
        StreamingConnectionImpl subsc = null;

        sub.rLock();
        try {
            cb = sub.getMessageHandler();
            ackSubject = sub.getAckInbox();
            isManualAck = sub.getOptions().isManualAcks();
            subsc = sub.getConnection(); // Can be nil if sub has been unsubscribed
        } catch (Exception e) {
            throw e;
        } finally {
            sub.rUnlock();
        }

        // Perform the callback
        if (cb != null && subsc != null) {
            cb.onMessage(stanMsg);
        }

        // Process auto-ack
        if (!isManualAck) {
            Ack ack = Ack.newBuilder().setSubject(stanMsg.getSubject())
                    .setSequence(stanMsg.getSequence()).build();
            try {
                // logger.trace("processMsg publishing Ack for sequence: {}",
                // stanMsg.getSequence());
                nc.publish(ackSubject, ack.toByteArray());
                // logger.trace("processMsg published Ack:\n{}", ack);
            } catch (IOException e) {
                // FIXME(dlc) - Async error handler? Retry?
                // This really won't happen since the publish is executing in the NATS thread.
                logger.error("Exception while publishing auto-ack: {}", e.getMessage());
                logger.debug("Stack trace: ", e);
            }
        }
    }

    public String getClientId() {
        return this.clientId;
    }

    @Override
    public io.nats.client.Connection getNatsConnection() {
        return this.nc;
    }

    protected void setNatsConnection(io.nats.client.Connection nc) {
        this.nc = nc;
    }

    public String newInbox() {
        return nc.newInbox();
    }

    void lock() {
        mu.writeLock().lock();
    }

    void unlock() {
        mu.writeLock().unlock();
    }

    void rLock() {
        mu.readLock().lock();
    }

    void rUnlock() { mu.readLock().unlock(); }

    protected io.nats.client.Subscription getAckSubscription() {
        return this.ackSubscription;
    }

    protected io.nats.client.Subscription getHbSubscription() {
        return this.hbSubscription;
    }

    // test injection setter/getters
    void setPubAckChan(BlockingQueue<PubAck> ch) {
        this.pubAckChan = ch;
    }

    BlockingQueue<PubAck> getPubAckChan() {
        return pubAckChan;
    }

    void setPubAckMap(Map<String, AckClosure> map) {
        this.pubAckMap = map;
    }

    Map<String, AckClosure> getPubAckMap() {
        return pubAckMap;
    }

    void setSubMap(Map<String, Subscription> map) {
        this.subMap = map;
    }

    Map<String, Subscription> getSubMap() {
        return subMap;
    }

    class AckClosure {
        TimerTask ackTask;
        AckHandler ah;
        BlockingQueue<String> ch;

        AckClosure(final AckHandler ah, final BlockingQueue<String> ch) {
            this.ah = ah;
            this.ch = ch;
        }
    }
}
