/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.cache.tier.sockets;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLException;

import org.apache.logging.log4j.Logger;

import org.apache.geode.CancelException;
import org.apache.geode.DataSerializer;
import org.apache.geode.InvalidDeltaException;
import org.apache.geode.statistics.StatisticDescriptor;
import org.apache.geode.statistics.Statistics;
import org.apache.geode.statistics.StatisticsFactory;
import org.apache.geode.statistics.StatisticsType;
import org.apache.geode.statistics.StatisticsTypeFactory;
import org.apache.geode.cache.EntryNotFoundException;
import org.apache.geode.cache.InterestResultPolicy;
import org.apache.geode.cache.Operation;
import org.apache.geode.cache.RegionDestroyedException;
import org.apache.geode.cache.client.ServerRefusedConnectionException;
import org.apache.geode.cache.client.internal.ClientUpdater;
import org.apache.geode.cache.client.internal.Endpoint;
import org.apache.geode.cache.client.internal.EndpointManager;
import org.apache.geode.cache.client.internal.GetEventValueOp;
import org.apache.geode.cache.client.internal.PoolImpl;
import org.apache.geode.cache.client.internal.QueueManager;
import org.apache.geode.cache.query.internal.cq.CqService;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.distributed.internal.DistributionConfig;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.distributed.internal.InternalDistributedSystem.DisconnectListener;
import org.apache.geode.distributed.internal.ServerLocation;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.distributed.internal.membership.MemberAttributes;
import org.apache.geode.internal.Assert;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.InternalInstantiator;
import org.apache.geode.internal.Version;
import org.apache.geode.internal.cache.ClientServerObserver;
import org.apache.geode.internal.cache.ClientServerObserverHolder;
import org.apache.geode.internal.cache.EntryEventImpl;
import org.apache.geode.internal.cache.EventID;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.internal.cache.tier.CachedRegionHelper;
import org.apache.geode.internal.cache.tier.ClientSideHandshake;
import org.apache.geode.internal.cache.tier.MessageType;
import org.apache.geode.internal.cache.versions.ConcurrentCacheModificationException;
import org.apache.geode.internal.cache.versions.VersionSource;
import org.apache.geode.internal.cache.versions.VersionTag;
import org.apache.geode.internal.i18n.LocalizedStrings;
import org.apache.geode.internal.logging.InternalLogWriter;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.internal.logging.LoggingThreadGroup;
import org.apache.geode.internal.logging.log4j.LocalizedMessage;
import org.apache.geode.internal.logging.log4j.LogMarker;
import org.apache.geode.internal.net.SocketCreator;
import org.apache.geode.internal.offheap.annotations.Released;
import org.apache.geode.internal.sequencelog.EntryLogger;
import org.apache.geode.internal.statistics.StatisticsTypeFactoryImpl;
import org.apache.geode.security.AuthenticationFailedException;
import org.apache.geode.security.AuthenticationRequiredException;
import org.apache.geode.security.GemFireSecurityException;

/**
 * {@code CacheClientUpdater} is a thread that processes update messages from a cache server and
 * {@linkplain org.apache.geode.cache.Region#localInvalidate(Object) invalidates} the local cache
 * based on the contents of those messages.
 *
 * @since GemFire 3.5
 */
public class CacheClientUpdater extends Thread implements ClientUpdater, DisconnectListener {

  private static final Logger logger = LogService.getLogger();

  private int DEFAULT_SOCKET_BUFFER_SIZE = 32768;

  /**
   * true if the constructor successfully created a connection. If false, the run method for this
   * thread immediately exits.
   */
  private final boolean connected;

  /**
   * System of which we are a part
   */
  private final InternalDistributedSystem system;

  /**
   * The socket by which we communicate with the server
   */
  private final Socket socket;

  /**
   * The output stream of the socket
   */
  private final OutputStream out;

  /**
   * The input stream of the socket
   */
  private final InputStream in;

  public ServerQueueStatus getServerQueueStatus() {
    return serverQueueStatus;
  }

  /**
   * server-side queue status at the time we connected to it
   */
  private ServerQueueStatus serverQueueStatus;

  /**
   * Failed updater from the endpoint previously known as the primary
   */
  private volatile ClientUpdater failedUpdater;

  /**
   * The buffer upon which we receive messages
   */
  private final ByteBuffer commBuffer;

  private boolean commBufferReleased; // TODO: fix synchronization

  private final CCUStats stats;

  /**
   * Cache for which we provide service TODO: lifecycle and synchronization need work
   */
  private /* final */ InternalCache cache;

  private /* final */ CachedRegionHelper cacheHelper;

  /**
   * Principle flag to signal thread's run loop to terminate
   */
  private final AtomicBoolean continueProcessing = new AtomicBoolean(true);

  /**
   * Is the client durable Used for bug 39010 fix
   */
  private final boolean isDurableClient;

  /**
   * Represents the server we are connected to
   */
  private final InternalDistributedMember serverId;

  /**
   * true if the EndPoint represented by this updater thread is primary
   */
  private final boolean isPrimary;

  /**
   * Added to avoid recording of the event if the concerned operation failed. See #43247
   */
  private boolean isOpCompleted;

  public static final String CLIENT_UPDATER_THREAD_NAME = "Cache Client Updater Thread ";

  /**
   * to enable test flag TODO: eliminate isUsedByTest
   */
  public static boolean isUsedByTest;

  /**
   * Indicates if full value was requested from server as a result of failure in applying delta
   * bytes. TODO: only used for test assertion
   */
  static boolean fullValueRequested = false;

  private final ServerLocation location;

  // TODO - remove these fields
  private QueueManager qManager = null;
  private EndpointManager eManager = null;
  private Endpoint endpoint = null;

  private static final long MAX_CACHE_WAIT =
      Long.getLong(DistributionConfig.GEMFIRE_PREFIX + "CacheClientUpdater.MAX_WAIT", 120); // seconds

  /**
   * Return true if cache appears
   *
   * @return true if cache appears
   */
  private boolean waitForCache() {
    InternalCache cache;
    long tilt = System.currentTimeMillis() + MAX_CACHE_WAIT * 1000;
    for (;;) {
      if (quitting()) {
        logger.warn(LocalizedMessage.create(
            LocalizedStrings.CacheClientUpdater_0_ABANDONED_WAIT_DUE_TO_CANCELLATION, this));
        return false;
      }
      if (!this.connected) {
        logger.warn(LocalizedMessage.create(
            LocalizedStrings.CacheClientUpdater_0_ABANDONED_WAIT_BECAUSE_IT_IS_NO_LONGER_CONNECTED,
            this));
        return false;
      }
      if (System.currentTimeMillis() > tilt) {
        logger.warn(LocalizedMessage.create(
            LocalizedStrings.CacheClientUpdater_0_WAIT_TIMED_OUT_MORE_THAN_1_SECONDS,
            new Object[] {this, MAX_CACHE_WAIT}));
        return false;
      }
      cache = GemFireCacheImpl.getInstance();
      if (cache != null && !cache.isClosed()) {
        break;
      }
      boolean interrupted = Thread.interrupted();
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ignore) {
        interrupted = true;
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    } // for
    this.cache = cache;
    this.cacheHelper = new CachedRegionHelper(cache);
    return true;
  }

  /**
   * Creates a new {@code CacheClientUpdater} with a given name that waits for a server to connect
   * on a given port.
   *
   * @param name descriptive name, used for our ThreadGroup
   * @param location the endpoint we represent
   * @param primary true if our endpoint is primary
   * @param ids the system we are distributing messages through
   *
   * @throws AuthenticationRequiredException when client is not configured to send credentials using
   *         security-* system properties but server expects credentials
   * @throws AuthenticationFailedException when authentication of the client fails
   * @throws ServerRefusedConnectionException when handshake fails for other reasons like using
   *         durable client ID that is already in use by another client or some server side
   *         exception while reading handshake/verifying credentials
   */
  public CacheClientUpdater(String name, ServerLocation location, boolean primary,
      DistributedSystem ids, ClientSideHandshake handshake, QueueManager qManager,
      EndpointManager eManager, Endpoint endpoint, int handshakeTimeout,
      SocketCreator socketCreator) throws AuthenticationRequiredException,
      AuthenticationFailedException, ServerRefusedConnectionException {

    super(LoggingThreadGroup.createThreadGroup("Client update thread"), name);
    this.setDaemon(true);
    this.system = (InternalDistributedSystem) ids;
    this.isDurableClient = handshake.getMembershipId().isDurable();
    this.isPrimary = primary;
    this.location = location;
    this.qManager = qManager;
    // this holds the connection which this threads reads
    this.eManager = eManager;
    this.endpoint = endpoint;
    this.stats = new CCUStats(this.system.getStatisticsFactory(), this.location);

    // Create the connection...
    final boolean isDebugEnabled = logger.isDebugEnabled();
    if (isDebugEnabled) {
      logger.debug("Creating asynchronous update connection");
    }

    boolean success = false;
    Socket mySock = null;
    InternalDistributedMember sid = null;
    ByteBuffer cb = null;
    OutputStream tmpOut = null;
    InputStream tmpIn = null;
    try {
      // Size of the server-to-client communication socket buffers
      int socketBufferSize =
          Integer.getInteger("BridgeServer.SOCKET_BUFFER_SIZE", DEFAULT_SOCKET_BUFFER_SIZE);

      mySock = socketCreator.connectForClient(location.getHostName(), location.getPort(),
          handshakeTimeout, socketBufferSize);
      mySock.setTcpNoDelay(true);
      mySock.setSendBufferSize(socketBufferSize);

      // Verify buffer sizes
      verifySocketBufferSize(socketBufferSize, mySock.getReceiveBufferSize(), "receive");
      verifySocketBufferSize(socketBufferSize, mySock.getSendBufferSize(), "send");

      // set the timeout for the handshake
      mySock.setSoTimeout(handshakeTimeout);
      tmpOut = mySock.getOutputStream();
      tmpIn = mySock.getInputStream();

      if (isDebugEnabled) {
        logger.debug(
            "Initialized server-to-client socket with send buffer size: {} bytes and receive buffer size: {} bytes",
            mySock.getSendBufferSize(), mySock.getReceiveBufferSize());
      }

      if (isDebugEnabled) {
        logger.debug(
            "Created connection from {}:{} to CacheClientNotifier on port {} for server-to-client communication",
            mySock.getInetAddress().getHostAddress(), mySock.getLocalPort(), mySock.getPort());
      }

      this.serverQueueStatus = handshake.handshakeWithSubscriptionFeed(mySock, this.isPrimary);
      if (serverQueueStatus.isPrimary() || serverQueueStatus.isNonRedundant()) {
        PoolImpl pool = (PoolImpl) this.qManager.getPool();
        if (!pool.getReadyForEventsCalled()) {
          pool.setPendingEventCount(serverQueueStatus.getServerQueueSize());
        }
      }

      int bufSize = 1024;
      try {
        bufSize = mySock.getSendBufferSize();
        if (bufSize < 1024) {
          bufSize = 1024;
        }
      } catch (SocketException ignore) {
      }
      cb = ServerConnection.allocateCommBuffer(bufSize, mySock);

      // create a "server" memberId we currently don't know much about the server.
      // Would be nice for it to send us its member id
      // TODO: change the serverId to use the endpoint's getMemberId() which returns a
      // DistributedMember (once gfecq branch is merged to trunk).
      MemberAttributes ma = new MemberAttributes(0, -1, ClusterDistributionManager.NORMAL_DM_TYPE,
          -1, null, null, null);
      sid =
          new InternalDistributedMember(mySock.getInetAddress(), mySock.getPort(), false, true, ma);

      success = true;
    } catch (ConnectException ignore) {
      if (!quitting()) {
        logger.warn(LocalizedMessage
            .create(LocalizedStrings.CacheClientUpdater_0_CONNECTION_WAS_REFUSED, this));
      }
    } catch (SSLException ex) {
      if (!quitting()) {
        getSecurityLogger().warning(LocalizedStrings.CacheClientUpdater_0_SSL_NEGOTIATION_FAILED_1,
            new Object[] {this, ex});
        throw new AuthenticationFailedException(
            LocalizedStrings.CacheClientUpdater_SSL_NEGOTIATION_FAILED_WITH_ENDPOINT_0
                .toLocalizedString(location),
            ex);
      }
    } catch (GemFireSecurityException ex) {
      if (!quitting()) {
        getSecurityLogger().warning(
            LocalizedStrings.CacheClientUpdater_0_SECURITY_EXCEPTION_WHEN_CREATING_SERVERTOCLIENT_COMMUNICATION_SOCKET_1,
            new Object[] {this, ex});
        throw ex;
      }
    } catch (IOException e) {
      if (!quitting()) {
        logger.warn(LocalizedMessage.create(
            LocalizedStrings.CacheClientUpdater_0_CAUGHT_FOLLOWING_EXECPTION_WHILE_ATTEMPTING_TO_CREATE_A_SERVER_TO_CLIENT_COMMUNICATION_SOCKET_AND_WILL_EXIT_1,
            new Object[] {this, e}), logger.isDebugEnabled() ? e : null);
      }
      eManager.serverCrashed(this.endpoint);
    } catch (ClassNotFoundException e) {
      if (!quitting()) {
        logger.warn(LocalizedMessage.create(LocalizedStrings.CacheClientUpdater_CLASS_NOT_FOUND,
            e.getMessage()));
      }
    } catch (ServerRefusedConnectionException e) {
      if (!quitting()) {
        logger.warn(LocalizedMessage.create(
            LocalizedStrings.CacheClientUpdater_0_CAUGHT_FOLLOWING_EXECPTION_WHILE_ATTEMPTING_TO_CREATE_A_SERVER_TO_CLIENT_COMMUNICATION_SOCKET_AND_WILL_EXIT_1,
            new Object[] {this, e}), logger.isDebugEnabled() ? e : null);
      }
      throw e;
    } finally {
      this.connected = success;
      if (mySock != null) {
        try {
          mySock.setSoTimeout(0);
        } catch (SocketException ignore) {
          // ignore: nothing we can do about this
        }
      }

      if (this.connected) {
        this.socket = mySock;
        this.out = tmpOut;
        this.in = tmpIn;
        this.serverId = sid;
        this.commBuffer = cb;

      } else {
        this.socket = null;
        this.serverId = null;
        this.commBuffer = null;
        this.out = null;
        this.in = null;

        if (mySock != null) {
          try {
            mySock.close();
          } catch (IOException ioe) {
            logger.warn(LocalizedMessage
                .create(LocalizedStrings.CacheClientUpdater_CLOSING_SOCKET_IN_0_FAILED, this), ioe);
          }
        }
      }
    }
  }

  private void releaseCommBuffer() {
    if (!this.commBufferReleased) {
      if (this.commBuffer != null) {
        synchronized (this.commBuffer) {
          if (!this.commBufferReleased) {
            this.commBufferReleased = true;
            ServerConnection.releaseCommBuffer(this.commBuffer);
          }
        }
      }
    }
  }

  public boolean isConnected() {
    return this.connected;
  }

  @Override
  public boolean isPrimary() {
    return this.isPrimary;
  }

  public InternalLogWriter getSecurityLogger() {
    return this.qManager.getSecurityLogger();
  }

  @Override
  public void setFailedUpdater(ClientUpdater failedUpdater) {
    this.failedUpdater = failedUpdater;
  }

  /**
   * Performs the work of the client update thread. Creates a {@code ServerSocket} and waits for the
   * server to connect to it.
   */
  @Override
  public void run() {
    EntryLogger.setSource(this.serverId, "RI");
    boolean addedListener = false;
    try {
      this.system.addDisconnectListener(this);
      addedListener = true;

      if (!waitForCache()) {
        logger.warn(
            LocalizedMessage.create(LocalizedStrings.CacheClientUpdater_0_NO_CACHE_EXITING, this));
        return;
      }
      processMessages();

    } catch (CancelException ignore) {
      // just bail

    } finally {
      if (addedListener) {
        this.system.removeDisconnectListener(this);
      }
      this.close();
      EntryLogger.clearSource();
    }
  }

  /**
   * Notifies this thread to stop processing
   */
  private void stopProcessing() {
    this.continueProcessing.set(false);
  }

  /**
   * Stops the updater. It will wait for a while for the thread to finish to try to prevent
   * duplicates. Note: this method is not named stop because this is a Thread which has a deprecated
   * stop method.
   */
  private void stopUpdater() {
    boolean isSelfDestroying = Thread.currentThread() == this;
    stopProcessing();

    // need to also close the socket for this interrupt to wakeup
    // the thread. This fixes bug 35691.

    if (this.isAlive()) {
      if (logger.isDebugEnabled()) {
        logger.debug("{}: Stopping {}", this.location, this);
      }

      if (!isSelfDestroying) {
        interrupt();
        try {
          if (this.socket != null) {
            this.socket.close();
          }
        } catch (IOException e) {
          if (logger.isDebugEnabled()) {
            logger.debug(e.getMessage(), e);
          }
        }
      } // !isSelfDestroying
    } // isAlive
  }

  /**
   * Signals the run thread to stop, closes underlying resources.
   */
  @Override
  public void close() {
    this.continueProcessing.set(false); // signals we are done.

    // Close the socket. This will also cause the underlying streams to fail.
    try {
      if (this.socket != null) {
        this.socket.close();
      }
    } catch (IOException ignore) {
      // ignore
    }

    this.stats.close();

    // close the helper
    if (this.cacheHelper != null) {
      this.cacheHelper.close();
    }
    releaseCommBuffer();
  }

  /**
   * Creates a cached {@link Message}object whose state is filled in with a message received from
   * the server.
   */
  private Message initializeMessage() {
    Message message = new Message(2, Version.CURRENT);
    message.setComms(this.socket, this.in, this.out, this.commBuffer, this.stats);
    return message;
  }

  /* refinement of method inherited from Thread */
  @Override
  public String toString() {
    return getName() + " (" + this.location.getHostName() + ':' + this.location.getPort() + ')';
  }

  /**
   * Handle a marker message
   *
   * @param clientMessage message containing the data
   */
  private void handleMarker(Message clientMessage) {
    try {
      final boolean isDebugEnabled = logger.isDebugEnabled();
      if (isDebugEnabled) {
        logger.debug("Received marker message of length ({} bytes)",
            clientMessage.getPayloadLength());
      }

      this.qManager.getState().processMarker();

      if (isDebugEnabled) {
        logger.debug("Processed marker message");
      }
    } catch (Exception e) {
      String message =
          LocalizedStrings.CacheClientUpdater_THE_FOLLOWING_EXCEPTION_OCCURRED_WHILE_ATTEMPTING_TO_HANDLE_A_MARKER
              .toLocalizedString();
      handleException(message, e);
    }
  }

  /**
   * Create or update an entry
   *
   * @param clientMessage message containing the data
   */
  private void handleUpdate(Message clientMessage) {
    String regionName = null;
    Object key = null;
    Part valuePart = null;
    final boolean isDebugEnabled = logger.isDebugEnabled();

    try {
      this.isOpCompleted = false;

      // Retrieve the data from the put message parts
      if (isDebugEnabled) {
        logger.debug("Received put message of length ({} bytes)", clientMessage.getPayloadLength());
      }

      int partCnt = 0;
      Part regionNamePart = clientMessage.getPart(partCnt++);
      Part keyPart = clientMessage.getPart(partCnt++);
      boolean isDeltaSent = (Boolean) clientMessage.getPart(partCnt++).getObject();
      valuePart = clientMessage.getPart(partCnt++);
      Part callbackArgumentPart = clientMessage.getPart(partCnt++);
      VersionTag versionTag = (VersionTag) clientMessage.getPart(partCnt++).getObject();
      if (versionTag != null) {
        versionTag.replaceNullIDs((InternalDistributedMember) this.endpoint.getMemberId());
      }
      Part isInterestListPassedPart = clientMessage.getPart(partCnt++);
      Part hasCqsPart = clientMessage.getPart(partCnt++);

      EventID eventId =
          (EventID) clientMessage.getPart(clientMessage.getNumberOfParts() - 1).getObject();

      boolean withInterest = (Boolean) isInterestListPassedPart.getObject();
      boolean withCQs = (Boolean) hasCqsPart.getObject();

      regionName = regionNamePart.getString();
      key = keyPart.getStringOrObject();
      Object callbackArgument = callbackArgumentPart.getObject();

      // Don't automatically deserialize the value.
      // Pass it onto the region as a byte[]. If it is a serialized
      // object, it will be stored as a CachedDeserializable and
      // deserialized only when requested.

      boolean isCreate = clientMessage.getMessageType() == MessageType.LOCAL_CREATE;

      if (isDebugEnabled) {
        logger.debug(
            "Putting entry for region: {} key: {} create: {}{} callbackArgument: {} withInterest={} withCQs={} eventID={} version={}",
            regionName, key, isCreate,
            valuePart.isObject()
                ? new StringBuilder(" value: ").append(deserialize(valuePart.getSerializedForm()))
                : "",
            callbackArgument, withInterest, withCQs, eventId, versionTag);
      }

      LocalRegion region = (LocalRegion) this.cacheHelper.getRegion(regionName);

      Object newValue = null;
      byte[] deltaBytes = null;
      Object fullValue = null;
      boolean isValueObject;

      if (!isDeltaSent) {
        // bug #42162 - must check for a serialized null here
        byte[] serializedForm = valuePart.getSerializedForm();

        if (isCreate && InternalDataSerializer.isSerializedNull(serializedForm)) {
          // newValue = null; newValue is already null
        } else {
          newValue = valuePart.getSerializedForm();
        }

        if (withCQs) {
          fullValue = valuePart.getObject();
        }

        isValueObject = valuePart.isObject();
      } else {
        deltaBytes = valuePart.getSerializedForm();
        isValueObject = true;
      }

      if (region == null) {
        if (isDebugEnabled && !quitting()) {
          logger.debug("{}: Region named {} does not exist", this, regionName);
        }

      } else if (region.hasServerProxy() && ServerResponseMatrix
          .checkForValidStateAfterNotification(region, key, clientMessage.getMessageType())
          && (withInterest || !withCQs)) {
        @Released
        EntryEventImpl newEvent = null;

        try {
          // Create an event and put the entry
          newEvent = EntryEventImpl.create(region,
              clientMessage.getMessageType() == MessageType.LOCAL_CREATE ? Operation.CREATE
                  : Operation.UPDATE,
              key, null /* newValue */, callbackArgument /* callbackArg */, true /* originRemote */,
              eventId.getDistributedMember());

          newEvent.setVersionTag(versionTag);
          newEvent.setFromServer(true);

          region.basicBridgeClientUpdate(eventId.getDistributedMember(), key, newValue, deltaBytes,
              isValueObject, callbackArgument,
              clientMessage.getMessageType() == MessageType.LOCAL_CREATE,
              this.qManager.getState().getProcessedMarker() || !this.isDurableClient, newEvent,
              eventId);

          this.isOpCompleted = true;

          // bug 45520 - ConcurrentCacheModificationException is not thrown and we must check this
          // flag
          if (withCQs && isDeltaSent) {
            fullValue = newEvent.getNewValue();
          }
        } catch (InvalidDeltaException ignore) {
          Part fullValuePart = requestFullValue(eventId, "Caught InvalidDeltaException.");
          region.getCachePerfStats().incDeltaFullValuesRequested();
          fullValue = newValue = fullValuePart.getObject(); // TODO: fix this line
          isValueObject = fullValuePart.isObject();

          region.basicBridgeClientUpdate(eventId.getDistributedMember(), key, newValue, null,
              isValueObject, callbackArgument,
              clientMessage.getMessageType() == MessageType.LOCAL_CREATE,
              this.qManager.getState().getProcessedMarker() || !this.isDurableClient, newEvent,
              eventId);

          this.isOpCompleted = true;
        } finally {
          if (newEvent != null)
            newEvent.release();
        }

        if (isDebugEnabled) {
          logger.debug("Put entry for region: {} key: {} callbackArgument: {}", regionName, key,
              callbackArgument);
        }
      }

      // Update CQs. CQs can exist without client region.
      if (withCQs) {
        Part numCqsPart = clientMessage.getPart(partCnt++);
        if (isDebugEnabled) {
          logger.debug("Received message has CQ Event. Number of cqs interested in the event : {}",
              numCqsPart.getInt() / 2);
        }
        partCnt = processCqs(clientMessage, partCnt, numCqsPart.getInt(),
            clientMessage.getMessageType(), key, fullValue, deltaBytes, eventId);
        this.isOpCompleted = true;
      }
    } catch (Exception e) {
      String message =
          LocalizedStrings.CacheClientUpdater_THE_FOLLOWING_EXCEPTION_OCCURRED_WHILE_ATTEMPTING_TO_PUT_ENTRY_REGION_0_KEY_1_VALUE_2
              .toLocalizedString(regionName, key, deserialize(valuePart.getSerializedForm()));
      handleException(message, e);
    }
  }

  private Part requestFullValue(EventID eventId, String reason) throws Exception {
    if (isUsedByTest) {
      fullValueRequested = true;
    }
    final boolean isDebugEnabled = logger.isDebugEnabled();
    if (isDebugEnabled) {
      logger.debug("{} Requesting full value...", reason);
    }
    Part result = (Part) GetEventValueOp.executeOnPrimary(this.qManager.getPool(), eventId, null);

    if (result == null) {
      // Just log a warning. Do not stop CCU thread.
      // TODO: throw a subclass of Exception
      throw new Exception("Could not retrieve full value for " + eventId);
    }

    if (isDebugEnabled) {
      logger.debug("Full value received.");
    }
    return result;
  }

  /**
   * Invalidate an entry
   *
   * @param clientMessage message describing the entry
   */
  private void handleInvalidate(Message clientMessage) {
    String regionName = null;
    Object key = null;
    final boolean isDebugEnabled = logger.isDebugEnabled();

    try {
      this.isOpCompleted = false;

      // Retrieve the data from the local-invalidate message parts
      if (isDebugEnabled) {
        logger.debug("Received invalidate message of length ({} bytes)",
            clientMessage.getPayloadLength());
      }

      int partCnt = 0;
      Part regionNamePart = clientMessage.getPart(partCnt++);
      Part keyPart = clientMessage.getPart(partCnt++);
      Part callbackArgumentPart = clientMessage.getPart(partCnt++);

      VersionTag versionTag = (VersionTag) clientMessage.getPart(partCnt++).getObject();
      if (versionTag != null) {
        versionTag.replaceNullIDs((InternalDistributedMember) this.endpoint.getMemberId());
      }

      Part isInterestListPassedPart = clientMessage.getPart(partCnt++);
      Part hasCqsPart = clientMessage.getPart(partCnt++);

      regionName = regionNamePart.getString();
      key = keyPart.getStringOrObject();

      Object callbackArgument = callbackArgumentPart.getObject();
      boolean withInterest = (Boolean) isInterestListPassedPart.getObject();
      boolean withCQs = (Boolean) hasCqsPart.getObject();

      if (isDebugEnabled) {
        logger.debug(
            "Invalidating entry for region: {} key: {} callbackArgument: {} withInterest={} withCQs={} version={}",
            regionName, key, callbackArgument, withInterest, withCQs, versionTag);
      }

      LocalRegion region = (LocalRegion) this.cacheHelper.getRegion(regionName);
      if (region == null) {
        if (isDebugEnabled && !quitting()) {
          logger.debug("Region named {} does not exist", regionName);
        }

      } else {
        if (region.hasServerProxy() && (withInterest || !withCQs)) {
          try {
            Part eid = clientMessage.getPart(clientMessage.getNumberOfParts() - 1);
            EventID eventId = (EventID) eid.getObject();

            try {
              region.basicBridgeClientInvalidate(eventId.getDistributedMember(), key,
                  callbackArgument,
                  this.qManager.getState().getProcessedMarker() || !this.isDurableClient, eventId,
                  versionTag);
            } catch (ConcurrentCacheModificationException ignore) {
              // allow CQs to be processed
            }

            this.isOpCompleted = true;
            // fix for 36615
            this.qManager.getState().incrementInvalidatedStats();

            if (isDebugEnabled) {
              logger.debug("Invalidated entry for region: {} key: {} callbackArgument: {}",
                  regionName, key, callbackArgument);
            }
          } catch (EntryNotFoundException ignore) {
            if (isDebugEnabled && !quitting()) {
              logger.debug("Already invalidated entry for region: {} key: {} callbackArgument: {}",
                  regionName, key, callbackArgument);
            }
            this.isOpCompleted = true;
          }
        }
      }

      if (withCQs) {
        // The client may have been registered to receive invalidates for
        // create and updates operations. Get the actual region operation.
        Part regionOpType = clientMessage.getPart(partCnt++);
        Part numCqsPart = clientMessage.getPart(partCnt++);
        if (isDebugEnabled) {
          logger.debug("Received message has CQ Event. Number of cqs interested in the event : {}",
              numCqsPart.getInt() / 2);
        }
        partCnt = processCqs(clientMessage, partCnt, numCqsPart.getInt(), regionOpType.getInt(),
            key, null);
        this.isOpCompleted = true;
      }
    } catch (Exception e) {
      final String message =
          LocalizedStrings.CacheClientUpdater_THE_FOLLOWING_EXCEPTION_OCCURRED_WHILE_ATTEMPTING_TO_INVALIDATE_ENTRY_REGION_0_KEY_1
              .toLocalizedString(regionName, key);
      handleException(message, e);
    }
  }

  /**
   * locally destroy an entry
   *
   * @param clientMessage message describing the entry
   */
  private void handleDestroy(Message clientMessage) {
    String regionName = null;
    Object key = null;
    final boolean isDebugEnabled = logger.isDebugEnabled();

    try {
      this.isOpCompleted = false;
      // Retrieve the data from the local-destroy message parts
      if (isDebugEnabled) {
        logger.debug("Received destroy message of length ({} bytes)",
            clientMessage.getPayloadLength());
      }

      int partCnt = 0;
      Part regionNamePart = clientMessage.getPart(partCnt++);
      Part keyPart = clientMessage.getPart(partCnt++);
      Part callbackArgumentPart = clientMessage.getPart(partCnt++);

      VersionTag versionTag = (VersionTag) clientMessage.getPart(partCnt++).getObject();
      if (versionTag != null) {
        versionTag.replaceNullIDs((InternalDistributedMember) this.endpoint.getMemberId());
      }

      regionName = regionNamePart.getString();
      key = keyPart.getStringOrObject();

      Part isInterestListPassedPart = clientMessage.getPart(partCnt++);
      Part hasCqsPart = clientMessage.getPart(partCnt++);

      boolean withInterest = ((Boolean) isInterestListPassedPart.getObject()).booleanValue();
      boolean withCQs = ((Boolean) hasCqsPart.getObject()).booleanValue();

      Object callbackArgument = callbackArgumentPart.getObject();
      if (isDebugEnabled) {
        logger.debug(
            "Destroying entry for region: {} key: {} callbackArgument: {} withInterest={} withCQs={} version={}",
            regionName, key, callbackArgument, withInterest, withCQs, versionTag);
      }

      LocalRegion region = (LocalRegion) this.cacheHelper.getRegion(regionName);
      if (region == null) {
        if (isDebugEnabled && !quitting()) {
          logger.debug("Region named {} does not exist", regionName);
        }

      } else if (region.hasServerProxy() && (withInterest || !withCQs)) {
        EventID eventId = null;
        try {
          Part eid = clientMessage.getPart(clientMessage.getNumberOfParts() - 1);
          eventId = (EventID) eid.getObject();

          try {
            region.basicBridgeClientDestroy(eventId.getDistributedMember(), key, callbackArgument,
                this.qManager.getState().getProcessedMarker() || !this.isDurableClient, eventId,
                versionTag);
          } catch (ConcurrentCacheModificationException ignore) {
            // allow CQs to be processed
          }

          this.isOpCompleted = true;
          if (isDebugEnabled) {
            logger.debug("Destroyed entry for region: {} key: {} callbackArgument: {}", regionName,
                key, callbackArgument);
          }
        } catch (EntryNotFoundException ignore) {
          if (isDebugEnabled && !quitting()) {
            logger.debug(
                "Already destroyed entry for region: {} key: {} callbackArgument: {} eventId={}",
                regionName, key, callbackArgument, eventId.expensiveToString());
          }
          this.isOpCompleted = true;
        }
      }

      if (withCQs) {
        Part numCqsPart = clientMessage.getPart(partCnt++);
        if (isDebugEnabled) {
          logger.debug("Received message has CQ Event. Number of cqs interested in the event : {}",
              numCqsPart.getInt() / 2);
        }
        partCnt = processCqs(clientMessage, partCnt, numCqsPart.getInt(),
            clientMessage.getMessageType(), key, null);
        this.isOpCompleted = true;
      }
    } catch (Exception e) {
      String message =
          LocalizedStrings.CacheClientUpdater_THE_FOLLOWING_EXCEPTION_OCCURRED_WHILE_ATTEMPTING_TO_DESTROY_ENTRY_REGION_0_KEY_1
              .toLocalizedString(regionName, key);
      handleException(message, e);
    }
  }

  /**
   * Locally destroy a region
   *
   * @param clientMessage message describing the region
   */
  private void handleDestroyRegion(Message clientMessage) {
    String regionName = null;
    final boolean isDebugEnabled = logger.isDebugEnabled();

    try {
      // Retrieve the data from the local-destroy-region message parts
      if (isDebugEnabled) {
        logger.debug("Received destroy region message of length ({} bytes)",
            clientMessage.getPayloadLength());
      }
      int partCnt = 0;
      Part regionNamePart = clientMessage.getPart(partCnt++);
      Part callbackArgumentPart = clientMessage.getPart(partCnt++);
      regionName = regionNamePart.getString();
      Object callbackArgument = callbackArgumentPart.getObject();

      Part hasCqsPart = clientMessage.getPart(partCnt++);

      if (isDebugEnabled) {
        logger.debug("Destroying region: {} callbackArgument: {}", regionName, callbackArgument);
      }

      // Handle CQs if any on this region.
      if ((Boolean) hasCqsPart.getObject()) {
        Part numCqsPart = clientMessage.getPart(partCnt++);
        if (isDebugEnabled) {
          logger.debug("Received message has CQ Event. Number of cqs interested in the event : {}",
              numCqsPart.getInt() / 2);
        }
        // TODO: partCnt is unused -- does processCqs have side effects
        partCnt = processCqs(clientMessage, partCnt, numCqsPart.getInt(),
            clientMessage.getMessageType(), null, null);
      }

      // Confirm that the region exists
      LocalRegion region = (LocalRegion) this.cacheHelper.getRegion(regionName);
      if (region == null) {
        if (isDebugEnabled && !quitting()) {
          logger.debug("Region named {} does not exist", regionName);
        }
        return;
      }

      // Verify that the region in question should respond to this message
      if (region.hasServerProxy()) {
        // Locally destroy the region
        region.localDestroyRegion(callbackArgument);

        if (isDebugEnabled) {
          logger.debug("Destroyed region: {} callbackArgument: {}", regionName, callbackArgument);
        }
      }
    } catch (RegionDestroyedException ignore) { // already destroyed
      if (isDebugEnabled) {
        logger.debug("region already destroyed: {}", regionName);
      }
    } catch (Exception e) {
      String message =
          LocalizedStrings.CacheClientUpdater_CAUGHT_AN_EXCEPTION_WHILE_ATTEMPTING_TO_DESTROY_REGION_0
              .toLocalizedString(regionName);
      handleException(message, e);
    }
  }

  /**
   * Locally clear a region
   *
   * @param clientMessage message describing the region to clear
   */
  private void handleClearRegion(Message clientMessage) {
    String regionName = null;
    final boolean isDebugEnabled = logger.isDebugEnabled();

    try {
      // Retrieve the data from the clear-region message parts
      if (isDebugEnabled) {
        logger.debug("{}: Received clear region message of length ({} bytes)", this,
            clientMessage.getPayloadLength());
      }

      int partCnt = 0;
      Part regionNamePart = clientMessage.getPart(partCnt++);
      Part callbackArgumentPart = clientMessage.getPart(partCnt++);

      Part hasCqsPart = clientMessage.getPart(partCnt++);

      regionName = regionNamePart.getString();
      Object callbackArgument = callbackArgumentPart.getObject();
      if (isDebugEnabled) {
        logger.debug("Clearing region: {} callbackArgument: {}", regionName, callbackArgument);
      }

      if ((Boolean) hasCqsPart.getObject()) {
        Part numCqsPart = clientMessage.getPart(partCnt++);
        if (isDebugEnabled) {
          logger.debug("Received message has CQ Event. Number of cqs interested in the event : {}",
              numCqsPart.getInt() / 2);
        }
        partCnt = processCqs(clientMessage, partCnt, numCqsPart.getInt(),
            clientMessage.getMessageType(), null, null);
      }

      // Confirm that the region exists
      LocalRegion region = (LocalRegion) this.cacheHelper.getRegion(regionName);
      if (region == null) {
        if (isDebugEnabled && !quitting()) {
          logger.debug("Region named {} does not exist", regionName);
        }
        return;
      }

      // Verify that the region in question should respond to this
      // message
      if (region.hasServerProxy()) {
        // Locally clear the region
        region.basicBridgeClientClear(callbackArgument,
            this.qManager.getState().getProcessedMarker() || !this.isDurableClient);

        if (isDebugEnabled) {
          logger.debug("Cleared region: {} callbackArgument: {}", regionName, callbackArgument);
        }
      }
    } catch (Exception e) {
      String message =
          LocalizedStrings.CacheClientUpdater_CAUGHT_THE_FOLLOWING_EXCEPTION_WHILE_ATTEMPTING_TO_CLEAR_REGION_0
              .toLocalizedString(regionName);
      handleException(message, e);
    }
  }

  /**
   * Locally invalidate a region NOTE: Added as part of bug#38048. The code only takes care of CQ
   * processing. Support needs to be added for local region invalidate.
   *
   * @param clientMessage message describing the region to clear
   */
  private void handleInvalidateRegion(Message clientMessage) {
    String regionName = null;
    final boolean isDebugEnabled = logger.isDebugEnabled();

    try {
      // Retrieve the data from the invalidate-region message parts
      if (isDebugEnabled) {
        logger.debug("{}: Received invalidate region message of length ({} bytes)", this,
            clientMessage.getPayloadLength());
      }

      int partCnt = 0;
      Part regionNamePart = clientMessage.getPart(partCnt++);
      partCnt++; // Part callbackArgumentPart = m.getPart(partCnt++);

      Part hasCqsPart = clientMessage.getPart(partCnt++);

      regionName = regionNamePart.getString();

      if ((Boolean) hasCqsPart.getObject()) {
        Part numCqsPart = clientMessage.getPart(partCnt++);
        if (isDebugEnabled) {
          logger.debug("Received message has CQ Event. Number of cqs interested in the event : {}",
              numCqsPart.getInt() / 2);
        }
        // TODO: partCnt is unused
        partCnt = processCqs(clientMessage, partCnt, numCqsPart.getInt(),
            clientMessage.getMessageType(), null, null);
      }

      // Confirm that the region exists
      LocalRegion region = (LocalRegion) this.cacheHelper.getRegion(regionName);
      if (region == null) {
        if (isDebugEnabled && !quitting()) {
          logger.debug("Region named {} does not exist", regionName);
        }
      }

    } catch (Exception e) {
      String message =
          LocalizedStrings.CacheClientUpdater_CAUGHT_THE_FOLLOWING_EXCEPTION_WHILE_ATTEMPTING_TO_INVALIDATE_REGION_0
              .toLocalizedString(regionName);
      handleException(message, e);
    }
  }

  /**
   * Register instantiators locally
   *
   * @param clientMessage message describing the new instantiators
   * @param eventId eventId of the instantiators
   */
  private void handleRegisterInstantiator(Message clientMessage, EventID eventId) {
    String instantiatorClassName = null;
    final boolean isDebugEnabled = logger.isDebugEnabled();

    try {
      int noOfParts = clientMessage.getNumberOfParts();
      if (isDebugEnabled) {
        logger.debug("{}: Received register instantiators message of parts {}", getName(),
            noOfParts);
      }

      Assert.assertTrue((noOfParts - 1) % 3 == 0);
      for (int i = 0; i < noOfParts - 1; i += 3) {
        instantiatorClassName =
            (String) CacheServerHelper.deserialize(clientMessage.getPart(i).getSerializedForm());
        String instantiatedClassName = (String) CacheServerHelper
            .deserialize(clientMessage.getPart(i + 1).getSerializedForm());
        int id = clientMessage.getPart(i + 2).getInt();
        InternalInstantiator.register(instantiatorClassName, instantiatedClassName, id, false,
            eventId, null);
        // distribute is false because we don't want to propagate this to servers recursively
      }

      // CALLBACK TESTING PURPOSE ONLY
      if (PoolImpl.IS_INSTANTIATOR_CALLBACK) {
        ClientServerObserver clientServerObserver = ClientServerObserverHolder.getInstance();
        clientServerObserver.afterReceivingFromServer(eventId);
      }

    } catch (Exception e) {
      if (isDebugEnabled) {
        logger.debug("{}: Caught following exception while attempting to read Instantiator : {}",
            this, instantiatorClassName, e);
      }
    }
  }

  private void handleRegisterDataSerializer(Message msg, EventID eventId) {
    Class dataSerializerClass = null;
    final boolean isDebugEnabled = logger.isDebugEnabled();

    try {
      int noOfParts = msg.getNumberOfParts();
      if (isDebugEnabled) {
        logger.debug("{}: Received register dataserializer message of parts {}", getName(),
            noOfParts);
      }

      for (int i = 0; i < noOfParts - 1;) {
        try {
          String dataSerializerClassName =
              (String) CacheServerHelper.deserialize(msg.getPart(i).getSerializedForm());
          int id = msg.getPart(i + 1).getInt();
          InternalDataSerializer.register(dataSerializerClassName, false, eventId, null, id);
          // distribute is false because we don't want to propagate this to servers recursively

          int numOfClasses = msg.getPart(i + 2).getInt();
          int j = 0;
          for (; j < numOfClasses; j++) {
            String className =
                (String) CacheServerHelper.deserialize(msg.getPart(i + 3 + j).getSerializedForm());
            InternalDataSerializer.updateSupportedClassesMap(dataSerializerClassName, className);
          }

          i += 3 + j;
        } catch (ClassNotFoundException e) {
          if (isDebugEnabled) {
            logger.debug(
                "{}: Caught following exception while attempting to read DataSerializer : {}", this,
                dataSerializerClass, e);
          }
        }
      }

      // CALLBACK TESTING PURPOSE ONLY
      if (PoolImpl.IS_INSTANTIATOR_CALLBACK) {
        ClientServerObserver bo = ClientServerObserverHolder.getInstance();
        bo.afterReceivingFromServer(eventId);
      }

    } catch (Exception e) {
      if (isDebugEnabled) {
        logger.debug("{}: Caught following exception while attempting to read DataSerializer : {}",
            this, dataSerializerClass, e);
      }
    }
  }

  /**
   * Processes message to invoke CQ listeners.
   */
  private int processCqs(Message clientMessage, int startMessagePart, int numCqParts,
      int messageType, Object key, Object value) {
    return processCqs(clientMessage, startMessagePart, numCqParts, messageType, key, value, null,
        null);
  }

  private int processCqs(Message clientMessage, int startMessagePart, int numCqParts,
      int messageType, Object key, Object value, byte[] delta, EventID eventId) {
    HashMap cqs = new HashMap();
    final boolean isDebugEnabled = logger.isDebugEnabled();

    for (int cqCnt = 0; cqCnt < numCqParts;) {
      StringBuilder sb = null;
      if (isDebugEnabled) {
        sb = new StringBuilder(100);
        sb.append("found these queries: ");
      }
      try {
        // Get CQ Name.
        Part cqNamePart = clientMessage.getPart(startMessagePart + cqCnt++);
        // Get CQ Op.
        Part cqOpPart = clientMessage.getPart(startMessagePart + cqCnt++);
        cqs.put(cqNamePart.getString(), cqOpPart.getInt());

        if (sb != null) {
          sb.append(cqNamePart.getString()).append(" op=").append(cqOpPart.getInt()).append("  ");
        }
      } catch (Exception ignore) {
        logger.warn(LocalizedMessage.create(
            LocalizedStrings.CacheClientUpdater_ERROR_WHILE_PROCESSING_THE_CQ_MESSAGE_PROBLEM_WITH_READING_MESSAGE_FOR_CQ_0,
            cqCnt));
      }
      if (isDebugEnabled) {
        logger.debug(sb);
      }
    }

    CqService cqService = this.cache.getCqService();
    try {
      cqService.dispatchCqListeners(cqs, messageType, key, value, delta, this.qManager, eventId);
    } catch (Exception ex) {
      logger.warn(LocalizedMessage.create(
          LocalizedStrings.CacheClientUpdater_FAILED_TO_INVOKE_CQ_DISPATCHER_ERROR___0,
          ex.getMessage()));
      if (isDebugEnabled) {
        logger.debug("Failed to invoke CQ Dispatcher.", ex);
      }
    }

    return startMessagePart + numCqParts;
  }

  private void handleRegisterInterest(Message clientMessage) {
    String regionName = null;
    Object key = null;
    final boolean isDebugEnabled = logger.isDebugEnabled();

    try {
      // Retrieve the data from the add interest message parts
      if (isDebugEnabled) {
        logger.debug("{}: Received add interest message of length ({} bytes)", this,
            clientMessage.getPayloadLength());
      }

      int partCnt = 0;
      Part regionNamePart = clientMessage.getPart(partCnt++);
      Part keyPart = clientMessage.getPart(partCnt++);
      Part interestTypePart = clientMessage.getPart(partCnt++);
      Part interestResultPolicyPart = clientMessage.getPart(partCnt++);
      Part isDurablePart = clientMessage.getPart(partCnt++);
      Part receiveUpdatesAsInvalidatesPart = clientMessage.getPart(partCnt++);

      regionName = regionNamePart.getString();
      key = keyPart.getStringOrObject();
      int interestType = (Integer) interestTypePart.getObject();
      byte interestResultPolicy = (Byte) interestResultPolicyPart.getObject();
      boolean isDurable = (Boolean) isDurablePart.getObject();
      boolean receiveUpdatesAsInvalidates = (Boolean) receiveUpdatesAsInvalidatesPart.getObject();

      // Confirm that region exists
      LocalRegion region = (LocalRegion) this.cacheHelper.getRegion(regionName);
      if (region == null) {
        if (isDebugEnabled && !quitting()) {
          logger.debug("{}: Region named {} does not exist", this, regionName);
        }
        return;
      }

      // Verify that the region in question should respond to this message
      if (!region.hasServerProxy()) {
        return;
      }

      if (key instanceof List) {
        region.getServerProxy().addListInterest((List) key,
            InterestResultPolicy.fromOrdinal(interestResultPolicy), isDurable,
            receiveUpdatesAsInvalidates);
      } else {
        region.getServerProxy().addSingleInterest(key, interestType,
            InterestResultPolicy.fromOrdinal(interestResultPolicy), isDurable,
            receiveUpdatesAsInvalidates);
      }
    } catch (Exception e) {
      String message =
          ": The following exception occurred while attempting to add interest (region: "
              + regionName + " key: " + key + "): ";
      handleException(message, e);
    }
  }

  private void handleUnregisterInterest(Message clientMessage) {
    String regionName = null;
    Object key = null;
    final boolean isDebugEnabled = logger.isDebugEnabled();

    try {
      // Retrieve the data from the remove interest message parts
      if (isDebugEnabled) {
        logger.debug("{}: Received remove interest message of length ({} bytes)", this,
            clientMessage.getPayloadLength());
      }

      int partCnt = 0;
      Part regionNamePart = clientMessage.getPart(partCnt++);
      Part keyPart = clientMessage.getPart(partCnt++);
      Part interestTypePart = clientMessage.getPart(partCnt++);
      Part isDurablePart = clientMessage.getPart(partCnt++);
      Part receiveUpdatesAsInvalidatesPart = clientMessage.getPart(partCnt++);
      // Not reading the eventId part

      regionName = regionNamePart.getString();
      key = keyPart.getStringOrObject();
      int interestType = (Integer) interestTypePart.getObject();
      boolean isDurable = (Boolean) isDurablePart.getObject();
      boolean receiveUpdatesAsInvalidates = (Boolean) receiveUpdatesAsInvalidatesPart.getObject();

      // Confirm that region exists
      LocalRegion region = (LocalRegion) this.cacheHelper.getRegion(regionName);
      if (region == null) {
        if (isDebugEnabled) {
          logger.debug("{}: Region named {} does not exist", this, regionName);
        }
        return;
      }

      // Verify that the region in question should respond to this message
      if (!region.hasServerProxy()) {
        return;
      }

      if (key instanceof List) {
        region.getServerProxy().removeListInterest((List) key, isDurable,
            receiveUpdatesAsInvalidates);
      } else {
        region.getServerProxy().removeSingleInterest(key, interestType, isDurable,
            receiveUpdatesAsInvalidates);
      }
    } catch (Exception e) {
      String message =
          ": The following exception occurred while attempting to add interest (region: "
              + regionName + " key: " + key + "): ";
      handleException(message, e);
    }
  }

  private void handleTombstoneOperation(Message clientMessage) {
    String regionName = "unknown";

    try { // not sure why this isn't done by the caller
      int partIdx = 0;

      // see ClientTombstoneMessage.getGFE70Message
      regionName = clientMessage.getPart(partIdx++).getString();
      int op = clientMessage.getPart(partIdx++).getInt();
      LocalRegion region = (LocalRegion) this.cacheHelper.getRegion(regionName);

      if (region == null) {
        if (!quitting()) {
          if (logger.isDebugEnabled()) {
            logger.debug("{}: Region named {} does not exist", this, regionName);
          }
        }
        return;
      }

      if (logger.isDebugEnabled()) {
        logger.debug("{}: Received tombstone operation for region {} with operation={}", this,
            region, op);
      }

      if (!region.getConcurrencyChecksEnabled()) {
        return;
      }

      switch (op) {
        case 0:
          Map<VersionSource, Long> regionGCVersions =
              (Map<VersionSource, Long>) clientMessage.getPart(partIdx++).getObject();
          EventID eventID = (EventID) clientMessage.getPart(partIdx++).getObject();
          region.expireTombstones(regionGCVersions, eventID, null);
          break;

        case 1:
          Set<Object> removedKeys = (Set<Object>) clientMessage.getPart(partIdx++).getObject();
          region.expireTombstoneKeys(removedKeys);
          break;

        default:
          throw new IllegalArgumentException("unknown operation type " + op);
      }
    } catch (Exception e) {
      handleException(": exception while removing tombstones from " + regionName, e);
    }
  }

  /**
   * Indicate whether the updater or the system is trying to terminate
   *
   * @return true if we are trying to stop
   */
  private boolean quitting() {
    if (isInterrupted()) {
      // Any time an interrupt is thrown at this thread, regard it as a request to terminate
      return true;
    }
    if (!this.continueProcessing.get()) {
      // de facto flag indicating we are to stop
      return true;
    }
    if (this.cache != null && this.cache.getCancelCriterion().isCancelInProgress()) {
      // System is cancelling
      return true;
    }

    // The pool stuff is really sick, so it's possible for us to have a distributed
    // system that is not the same as our cache. Check it just in case...
    if (this.system.getCancelCriterion().isCancelInProgress()) {
      return true;
    }

    // All clear on this end, boss.
    return false;
  }

  private void waitForFailedUpdater() {
    boolean gotInterrupted = false;
    try {
      if (this.failedUpdater != null) {
        logger.info(LocalizedMessage.create(
            LocalizedStrings.CacheClientUpdater__0_IS_WAITING_FOR_1_TO_COMPLETE,
            new Object[] {this, this.failedUpdater}));
        while (this.failedUpdater.isAlive()) {
          if (quitting()) {
            return;
          }
          this.failedUpdater.join(5000);
        }
      }
    } catch (InterruptedException ignore) {
      gotInterrupted = true;
      // just bail, because I have not done anything yet
    } finally {
      if (!gotInterrupted && this.failedUpdater != null) {
        logger.info(LocalizedMessage.create(
            LocalizedStrings.CacheClientUpdater_0_HAS_COMPLETED_WAITING_FOR_1,
            new Object[] {this, this.failedUpdater}));
        this.failedUpdater = null;
      }
    }
  }

  /**
   * Processes messages received from the server.
   *
   * Only certain types of messages are handled.
   *
   * TODO: Method 'processMessages' is too complex to analyze by data flow algorithm
   *
   * @see MessageType#CLIENT_MARKER
   * @see MessageType#LOCAL_CREATE
   * @see MessageType#LOCAL_UPDATE
   * @see MessageType#LOCAL_INVALIDATE
   * @see MessageType#LOCAL_DESTROY
   * @see MessageType#LOCAL_DESTROY_REGION
   * @see MessageType#CLEAR_REGION
   * @see ClientUpdateMessage
   */
  private void processMessages() {
    final boolean isDebugEnabled = logger.isDebugEnabled();

    final int headerReadTimeout = (int) Math.round(serverQueueStatus.getPingInterval()
        * qManager.getPool().getSubscriptionTimeoutMultiplier() * 1.25);

    try {
      Message clientMessage = initializeMessage();

      if (quitting()) {
        if (isDebugEnabled) {
          logger.debug("processMessages quitting early because we have stopped");
        }
        // our caller calls close which will notify all waiters for our init
        return;
      }

      logger.info(LocalizedMessage
          .create(LocalizedStrings.CacheClientUpdater_0_READY_TO_PROCESS_MESSAGES, this));

      while (this.continueProcessing.get()) {
        if (quitting()) {
          if (isDebugEnabled) {
            logger.debug("termination detected");
          }
          // our caller calls close which will notify all waiters for our init
          return;
        }

        // the endpoint died while this thread was sleeping.
        if (this.endpoint.isClosed()) {
          if (isDebugEnabled) {
            logger.debug("endpoint died");
          }
          this.continueProcessing.set(false);// = false;
          break;
        }

        try {
          // Read the message
          clientMessage.receiveWithHeaderReadTimeout(headerReadTimeout);

          // Wait for the previously failed cache client updater
          // to finish. This will avoid out of order messages.
          waitForFailedUpdater();
          this.cache.waitForRegisterInterestsInProgress();
          if (quitting()) {
            if (isDebugEnabled) {
              logger.debug("processMessages quitting before processing message");
            }
            break;
          }

          // If the message is a ping, ignore it
          if (clientMessage.getMessageType() == MessageType.SERVER_TO_CLIENT_PING) {
            if (isDebugEnabled) {
              logger.debug("{}: Received ping", this);
            }
            continue;
          }

          boolean isDeltaSent = false;
          boolean isCreateOrUpdate = clientMessage.getMessageType() == MessageType.LOCAL_CREATE
              || clientMessage.getMessageType() == MessageType.LOCAL_UPDATE;
          if (isCreateOrUpdate) {
            isDeltaSent = (Boolean) clientMessage.getPart(2).getObject();
          }

          // extract the eventId and verify if it is a duplicate event
          // if it is a duplicate event, ignore
          // @since GemFire 5.1
          int numberOfParts = clientMessage.getNumberOfParts();
          Part eid = clientMessage.getPart(numberOfParts - 1);

          // TODO the message handling methods also deserialized the eventID - inefficient
          EventID eventId = (EventID) eid.getObject();

          // no need to verify if the instantiator msg is duplicate or not
          if (clientMessage.getMessageType() != MessageType.REGISTER_INSTANTIATORS
              && clientMessage.getMessageType() != MessageType.REGISTER_DATASERIALIZERS) {
            if (this.qManager.getState().verifyIfDuplicate(eventId,
                !(this.isDurableClient || isDeltaSent))) {
              continue;
            }
          }

          if (logger.isTraceEnabled(LogMarker.BRIDGE_SERVER_VERBOSE)) {
            logger.trace(LogMarker.BRIDGE_SERVER_VERBOSE, "Processing event with id {}",
                eventId.expensiveToString());
          }

          this.isOpCompleted = true;

          // Process the message
          switch (clientMessage.getMessageType()) {
            case MessageType.LOCAL_CREATE:
            case MessageType.LOCAL_UPDATE:
              handleUpdate(clientMessage);
              break;
            case MessageType.LOCAL_INVALIDATE:
              handleInvalidate(clientMessage);
              break;
            case MessageType.LOCAL_DESTROY:
              handleDestroy(clientMessage);
              break;
            case MessageType.LOCAL_DESTROY_REGION:
              handleDestroyRegion(clientMessage);
              break;
            case MessageType.CLEAR_REGION:
              handleClearRegion(clientMessage);
              break;
            case MessageType.REGISTER_INSTANTIATORS:
              handleRegisterInstantiator(clientMessage, eventId);
              break;
            case MessageType.REGISTER_DATASERIALIZERS:
              handleRegisterDataSerializer(clientMessage, eventId);
              break;
            case MessageType.CLIENT_MARKER:
              handleMarker(clientMessage);
              break;
            case MessageType.INVALIDATE_REGION:
              handleInvalidateRegion(clientMessage);
              break;
            case MessageType.CLIENT_REGISTER_INTEREST:
              handleRegisterInterest(clientMessage);
              break;
            case MessageType.CLIENT_UNREGISTER_INTEREST:
              handleUnregisterInterest(clientMessage);
              break;
            case MessageType.TOMBSTONE_OPERATION:
              handleTombstoneOperation(clientMessage);
              break;
            default:
              logger.warn(LocalizedMessage.create(
                  LocalizedStrings.CacheClientUpdater_0_RECEIVED_AN_UNSUPPORTED_MESSAGE_TYPE_1,
                  new Object[] {this, MessageType.getString(clientMessage.getMessageType())}));
              break;
          }

          if (this.isOpCompleted && (this.isDurableClient || isDeltaSent)) {
            this.qManager.getState().verifyIfDuplicate(eventId, true);
          }

          // TODO we should maintain the client's "live" view of the server
          // but we don't because the server health monitor needs traffic
          // originating from the client
          // and by updating the last update stat, the ServerMonitor is less
          // likely to send pings...
          // and the ClientHealthMonitor will cause a disconnect

        } catch (InterruptedIOException ignore) {
          // Per Sun's support web site, this exception seems to be peculiar
          // to Solaris, and may eventually not even be generated there.
          //
          // When this exception is thrown, the thread has been interrupted, but
          // isInterrupted() is false. (How very odd!)
          //
          // We regard it the same as an InterruptedException

          this.continueProcessing.set(false);
          if (isDebugEnabled) {
            logger.debug("InterruptedIOException");
          }

        } catch (IOException e) {
          // Either the server went away, or we caught a closing condition.
          if (!quitting()) {
            // Server departed; print a message.
            ClientServerObserver clientServerObserver = ClientServerObserverHolder.getInstance();
            clientServerObserver.beforeFailoverByCacheClientUpdater(this.location);
            this.eManager.serverCrashed(this.endpoint);
            if (isDebugEnabled) {
              logger.debug("Caught the following exception and will exit", e);
            }
          } // !quitting

          // In any event, terminate this thread.
          this.continueProcessing.set(false);
          if (isDebugEnabled) {
            logger.debug("terminated due to IOException");
          }

        } catch (Exception e) {
          if (!quitting()) {
            ClientServerObserver clientServerObserver = ClientServerObserverHolder.getInstance();
            clientServerObserver.beforeFailoverByCacheClientUpdater(this.location);
            this.eManager.serverCrashed(this.endpoint);
            String message = ": Caught the following exception and will exit: ";
            handleException(message, e);
          }

          // In any event, terminate this thread.
          this.continueProcessing.set(false);// = false; // force termination
          if (isDebugEnabled) {
            logger.debug("CCU terminated due to Exception");
          }

        } finally {
          clientMessage.clear();
        }
      } // while

    } finally {
      if (isDebugEnabled) {
        logger.debug("has stopped and cleaning the helper ..");
      }
      close(); // added to fix some race conditions associated with 38382
      // this will make sure that if this thread dies without starting QueueMgr then it will start..
      // 1. above we ignore InterruptedIOException and this thread dies without informing QueueMgr
      // 2. if there is some other race condition with continueProcessing flag
      this.qManager.checkEndpoint(this, this.endpoint);
    }
  }

  /**
   * Conditionally print a warning describing the failure
   * <p>
   * Signals run thread to stop. Messages are not printed if the thread or the distributed system
   * has already been instructed to terminate.
   *
   * @param message contextual string for the failure
   * @param exception underlying exception
   */
  private void handleException(String message, Exception exception) {
    boolean unexpected = !quitting();

    // If this was a surprise, print a warning.
    if (unexpected && !(exception instanceof CancelException)) {
      logger.warn(LocalizedMessage.create(LocalizedStrings.CacheClientUpdater_0__1__2,
          new Object[] {this, message, exception}), exception);
    }
    // We can't shutdown the client updater just because of an exception.
    // Let the caller decide if we should continue running or not.
  }

  /**
   * Return an object from serialization. Only used in debug logging.
   *
   * @param serializedBytes the serialized form
   * @return the deserialized object
   */
  private Object deserialize(byte[] serializedBytes) {
    Object deserializedObject = serializedBytes;
    // This is a debugging method so ignore all exceptions like ClassNotFoundException
    try {
      DataInputStream dis = new DataInputStream(new ByteArrayInputStream(serializedBytes));
      deserializedObject = DataSerializer.readObject(dis);
    } catch (ClassNotFoundException | IOException ignore) {
    }
    return deserializedObject;
  }

  /**
   * @return the local port of our {@link #socket}
   */
  protected int getLocalPort() {
    return this.socket.getLocalPort();
  }

  @Override
  public void onDisconnect(InternalDistributedSystem sys) {
    stopUpdater();
  }

  private void verifySocketBufferSize(int requestedBufferSize, int actualBufferSize, String type) {
    if (actualBufferSize < requestedBufferSize) {
      logger.info(LocalizedMessage.create(
          LocalizedStrings.Connection_SOCKET_0_IS_1_INSTEAD_OF_THE_REQUESTED_2,
          new Object[] {type + " buffer size", actualBufferSize, requestedBufferSize}));
    }
  }

  @Override
  public boolean isProcessing() {
    return this.continueProcessing.get();
  }
}
