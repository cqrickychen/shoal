/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.enterprise.mgmt.transport.grizzly.grizzly2;

import com.sun.enterprise.mgmt.transport.grizzly.PongMessageListener;
import com.sun.enterprise.mgmt.transport.grizzly.PingMessageListener;
import com.sun.enterprise.mgmt.transport.MessageEvent;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import com.sun.enterprise.mgmt.transport.NetworkUtility;
import java.util.concurrent.ThreadPoolExecutor;
import com.sun.enterprise.mgmt.transport.BlockingIOMulticastSender;
import com.sun.enterprise.mgmt.transport.VirtualMulticastSender;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;
import com.sun.enterprise.mgmt.transport.grizzly.GrizzlyPeerID;
import com.sun.enterprise.mgmt.transport.MessageImpl;
import com.sun.enterprise.mgmt.transport.Message;
import com.sun.enterprise.mgmt.transport.MessageSender;
import com.sun.enterprise.mgmt.transport.MulticastMessageSender;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.Grizzly;
import com.sun.enterprise.ee.cms.impl.base.GMSThreadFactory;
import com.sun.enterprise.ee.cms.impl.base.PeerID;
import com.sun.enterprise.ee.cms.impl.base.Utility;
import com.sun.enterprise.ee.cms.impl.common.GMSContext;
import com.sun.enterprise.ee.cms.impl.common.GMSContextFactory;
import com.sun.enterprise.ee.cms.impl.common.GMSMonitor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.sun.enterprise.mgmt.transport.grizzly.GrizzlyConfigConstants.*;

/**
 * @author Bongjae Chang
 */
public class GrizzlyNetworkManager extends com.sun.enterprise.mgmt.transport.grizzly.GrizzlyNetworkManager {

    private int maxPoolSize;
    private int corePoolSize;
    private long keepAliveTime; // ms
    private int poolQueueSize;
    private String virtualUriList;
    private ExecutorService multicastSenderThreadPool = null;
    private TCPNIOTransport tcpNioTransport;
    private ConnectionCache tcpNioConnectionCache;
    
    public GrizzlyNetworkManager() {
    }

    public void localConfigure(final Map properties) {
        maxPoolSize = Utility.getIntProperty(MAX_POOLSIZE.toString(), 50, properties);
        corePoolSize = Utility.getIntProperty(CORE_POOLSIZE.toString(), 20, properties);
        keepAliveTime = Utility.getLongProperty(KEEP_ALIVE_TIME.toString(), 60 * 1000, properties);
        poolQueueSize = Utility.getIntProperty(POOL_QUEUE_SIZE.toString(), 1024 * 4, properties);
        virtualUriList = Utility.getStringProperty(VIRTUAL_MULTICAST_URI_LIST.toString(), null, properties);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void initialize(final String groupName, final String instanceName, final Map properties) throws IOException {
        super.initialize(groupName, instanceName, properties);
        this.instanceName = instanceName;
        this.groupName = groupName;
        System.out.println("Grizzly 2.0 NetworkManager");
        configure(properties);
        localConfigure(properties);
        GMSContext ctx = GMSContextFactory.getGMSContext(groupName);
        if (ctx != null) {
            GMSMonitor monitor = ctx.getGMSMonitor();
            if (monitor != null) {
                monitor.setSendWriteTimeout(this.sendWriteTimeout);
            }
        }

        // moved setting of localPeerId.

        InetAddress localInetAddress = null;
        if (host != null) {
            localInetAddress = InetAddress.getByName(host);
        }
        final TCPNIOTransportBuilder tcpTransportBuilder =
                TCPNIOTransportBuilder.newInstance();

        final ThreadPoolConfig threadPoolConfig =
                tcpTransportBuilder.getWorkerThreadPoolConfig();

        if (threadPoolConfig != null) {
            threadPoolConfig.setPoolName("GMS-GrizzlyControllerThreadPool-Group-" + groupName)
                    .setCorePoolSize(corePoolSize)
                    .setMaxPoolSize(maxPoolSize)
                    .setQueue(new ArrayBlockingQueue<Runnable>(poolQueueSize))
                    .setQueueLimit(poolQueueSize)
                    .setKeepAliveTime(keepAliveTime, TimeUnit.MILLISECONDS)
                    .setPriority(Thread.NORM_PRIORITY);
        }

        final TCPNIOTransport transport = tcpTransportBuilder.build();

//        final CacheableConnectorHandlerPool cacheableHandlerPool =
//                new CacheableConnectorHandlerPool( controller, highWaterMark,
//                numberToReclaim, maxParallelSendConnections,
//                new ConnectionFinder<ConnectorHandler>() {
//
//            @Override
//            public ConnectorHandler find(ContactInfo<ConnectorHandler> cinfo,
//                    Collection<ConnectorHandler> idleConnections,
//                    Collection<ConnectorHandler> busyConnections)
//                    throws IOException {
//
//                if (!idleConnections.isEmpty()) {
//                    return null;
//                }
//
//                return cinfo.createConnection();
//            }
//        });
//
//        controller.setConnectorHandlerPool( cacheableHandlerPool );

        tcpPort = bind(transport, localInetAddress, tcpStartPort, tcpEndPort);

//        tcpSelectorHandler = new ReusableTCPSelectorHandler();
//        tcpSelectorHandler.setPortRange(new PortRange());
//        tcpSelectorHandler.setSelectionKeyHandler( new GrizzlyCacheableSelectionKeyHandler( highWaterMark, numberToReclaim, this ) );
//        tcpSelectorHandler.setInet( localInetAddress );
//
//        controller.addSelectorHandler( tcpSelectorHandler );

//        MulticastSelectorHandler multicastSelectorHandler = new MulticastSelectorHandler();
//        multicastSelectorHandler.setPort( multicastPort );
//        multicastSelectorHandler.setSelectionKeyHandler( new GrizzlyCacheableSelectionKeyHandler( highWaterMark, numberToReclaim, this ) );
//        if( GrizzlyUtil.isSupportNIOMulticast() ) {
//            multicastSelectorHandler.setMulticastAddress( multicastAddress );
//            multicastSelectorHandler.setNetworkInterface( networkInterfaceName );
//            multicastSelectorHandler.setInet( localInetAddress );
//            controller.addSelectorHandler( multicastSelectorHandler );
//        }

        ProtocolChainInstanceHandler pciHandler = new DefaultProtocolChainInstanceHandler() {

            @Override
            public ProtocolChain poll() {
                ProtocolChain protocolChain = protocolChains.poll();
                if (protocolChain == null) {
                    protocolChain = new DefaultProtocolChain();
                    protocolChain.addFilter(GrizzlyMessageProtocolParser.createParserProtocolFilter(null));
                    protocolChain.addFilter(new GrizzlyMessageDispatcherFilter(GrizzlyNetworkManager.this));
                }
                return protocolChain;
            }
        };
        controller.setProtocolChainInstanceHandler(pciHandler);
        SelectorFactory.setMaxSelectors(writeSelectorPoolSize);

        tcpNioTransport = transport;
        tcpNioConnectionCache = new ConnectionCache(tcpNioTransport,
                highWaterMark, maxParallelSendConnections, numberToReclaim);
    }

//    private final CountDownLatch controllerGate = new CountDownLatch( 1 );
//    private boolean controllerGateIsReady = false;
//    private Throwable controllerGateStartupException = null;
    @Override
    @SuppressWarnings("unchecked")
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        super.start();

//        ControllerStateListener controllerStateListener = new ControllerStateListener() {
//
//            public void onStarted() {
//            }
//
//            public void onReady() {
//                if( LOG.isLoggable( Level.FINER ) )
//                    LOG.log( Level.FINER, "GrizzlyNetworkManager is ready" );
//                controllerGateIsReady = true;
//                controllerGate.countDown();
//            }
//
//            public void onStopped() {
//                controllerGate.countDown();
//            }
//
//            @Override
//            public void onException(Throwable e) {
//                if (controllerGate.getCount() > 0) {
//                    getLogger().log(Level.SEVERE, "Exception during " +
//                            "starting the controller", e);
//                    controllerGate.countDown();
//                    controllerGateStartupException = e;
//                } else {
//                    getLogger().log(Level.SEVERE, "Exception during " +
//                            "controller processing", e);
//                }
//            }
//        };
//        controller.addStateListener( controllerStateListener );
//        new Thread( controller ).start();
//        try {
//            controllerGate.await( startTimeout, TimeUnit.MILLISECONDS );
//        } catch( InterruptedException e ) {
//            e.printStackTrace();
//        }
        final long transportStartTime = System.currentTimeMillis();

        tcpNioTransport.start();

        final long durationInMillis = System.currentTimeMillis() - transportStartTime;

        // do not continue if controller did not start.
//        if (!controller.isStarted() || !controllerGateIsReady) {
//            if (controllerGateStartupException != null) {
//                throw new IllegalStateException("Grizzly Controller was not started and ready after " + durationInMillis + " ms",
//                        controllerGateStartupException);
//            } else {
//                throw new IllegalStateException("Grizzly Controller was not started and ready after " + durationInMillis + " ms");
//
//            }
//        } else if (controllerGateIsReady) {
//            // todo: make this FINE in future.
        getLogger().log(Level.CONFIG,
                "Grizzly controller listening on {0}:{1}. Transport started in {2} ms",
                new Object[]{host, tcpPort, durationInMillis});
//        }

//        tcpPort = tcpSelectorHandler.getPort();

        if (localPeerID == null) {
            String uniqueHost = host;
            if (uniqueHost == null) {
                // prefer IPv4
                InetAddress firstInetAddress = NetworkUtility.getFirstInetAddress(false);
                if (firstInetAddress == null) {
                    firstInetAddress = NetworkUtility.getFirstInetAddress(true);
                }
                if (firstInetAddress == null) {
                    throw new IOException("can not find a first InetAddress");
                }
                uniqueHost = firstInetAddress.getHostAddress();
            }
            if (uniqueHost == null) {
                throw new IOException("can not find an unique host");
            }
            localPeerID = new PeerID<GrizzlyPeerID>(new GrizzlyPeerID(uniqueHost,
                    tcpPort, multicastAddress, multicastPort), groupName, instanceName);
            peerIDMap.put(instanceName, localPeerID);
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "local peer id = {0}", localPeerID);
            }
        }

        tcpSender = new GrizzlyTCPMessageSender(tcpNioTransport,
                tcpNioConnectionCache, localPeerID);
//        GrizzlyUDPConnectorWrapper udpConnectorWrapper = new GrizzlyUDPConnectorWrapper(controller,
//                sendWriteTimeout,
//                host,
//                multicastPort,
//                multicastAddress,
//                localPeerID);
//        udpSender = udpConnectorWrapper;
        udpSender = null;
        
        List<PeerID> virtualPeerIdList = getVirtualPeerIDList(virtualUriList);
        if (virtualPeerIdList != null && !virtualPeerIdList.isEmpty()) {
            final boolean FAIRNESS = true;
            ThreadFactory tf = new GMSThreadFactory("GMS-mcastSenderThreadPool-thread");

            multicastSenderThreadPool = new ThreadPoolExecutor(
                    10, 10, 60 * 1000, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(1024, FAIRNESS), tf);
            multicastSender = new VirtualMulticastSender(host,
                    multicastAddress,
                    multicastPort,
                    networkInterfaceName,
                    multicastPacketSize,
                    localPeerID,
                    multicastSenderThreadPool,
                    this,
                    multicastTimeToLive,
                    virtualPeerIdList);
        } else {
//            if( GrizzlyUtil.isSupportNIOMulticast() ) {
//                multicastSender = udpConnectorWrapper;
//            } else {
            final boolean FAIRNESS = true;
            ThreadFactory tf = new GMSThreadFactory("GMS-McastMsgProcessor-Group-" + groupName + "-thread");
            multicastSenderThreadPool = new ThreadPoolExecutor(
                    10, 10, 60 * 1000, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(1024, FAIRNESS), tf);
            multicastSender = new BlockingIOMulticastSender(host,
                    multicastAddress,
                    multicastPort,
                    networkInterfaceName,
                    multicastPacketSize,
                    localPeerID,
                    multicastSenderThreadPool,
                    multicastTimeToLive,
                    this);
//            }
        }
        if (tcpSender != null) {
            tcpSender.start();
        }
        if (udpSender != null) {
            udpSender.start();
        }
        if (multicastSender != null) {
            multicastSender.start();
        }
        addMessageListener(new PingMessageListener());
        addMessageListener(new PongMessageListener());
        running = true;

    }

    @Override
    public List<PeerID> getVirtualPeerIDList(String virtualUriList) {
        if (virtualUriList == null) {
            return null;
        }
        LOG.log(Level.CONFIG, "VIRTUAL_MULTICAST_URI_LIST = {0}", virtualUriList);
        List<PeerID> virtualPeerIdList = new ArrayList<PeerID>();
        //if this object has multiple addresses that are comma separated
        if (virtualUriList.indexOf(",") > 0) {
            String addresses[] = virtualUriList.split(",");
            if (addresses.length > 0) {
                List<String> virtualUriStringList = Arrays.asList(addresses);
                for (String uriString : virtualUriStringList) {
                    try {
                        PeerID peerID = getPeerIDFromURI(uriString);
                        if (peerID != null) {
                            virtualPeerIdList.add(peerID);
                            LOG.log(Level.CONFIG,
                                    "VIRTUAL_MULTICAST_URI = {0}, Converted PeerID = {1}",
                                    new Object[]{uriString, peerID});
                        }
                    } catch (URISyntaxException use) {
                        if (LOG.isLoggable(Level.CONFIG)) {
                            LOG.log(Level.CONFIG,
                                    "failed to parse the virtual multicast uri("
                                    + uriString + ")", use);
                        }
                    }
                }
            }
        } else {
            //this object has only one address in it, so add it to the list
            try {
                PeerID peerID = getPeerIDFromURI(virtualUriList);
                if (peerID != null) {
                    virtualPeerIdList.add(peerID);
                    LOG.log(Level.CONFIG,
                            "VIRTUAL_MULTICAST_URI = {0}, Converted PeerID = {1}",
                            new Object[]{virtualUriList, peerID});
                }
            } catch (URISyntaxException use) {
                if (LOG.isLoggable(Level.CONFIG)) {
                    LOG.log(Level.CONFIG, "failed to parse the virtual multicast uri(" + virtualUriList + ")", use);
                }
            }
        }
        return virtualPeerIdList;
    }

    private PeerID<GrizzlyPeerID> getPeerIDFromURI(String uri) throws URISyntaxException {
        if (uri == null) {
            return null;
        }
        URI virtualUri = new URI(uri);
        return new PeerID<GrizzlyPeerID>(new GrizzlyPeerID(virtualUri.getHost(),
                virtualUri.getPort(),
                multicastAddress,
                multicastPort),
                localPeerID.getGroupName(),
                // the instance name is not meaningless in this case
                "Unknown");
    }

    @Override
    public synchronized void stop() throws IOException {
        if (!running) {
            return;
        }
        running = false;
        super.stop();
        if (tcpSender != null) {
            tcpSender.stop();
        }
        if (udpSender != null) {
            udpSender.stop();
        }
        if (multicastSender != null) {
            multicastSender.stop();
        }
        if (multicastSenderThreadPool != null) {
            multicastSenderThreadPool.shutdown();
        }
        peerIDMap.clear();
//        selectionKeyMap.clear();
        pingMessageLockMap.clear();
//        controller.stop();
        tcpNioConnectionCache.close();
        tcpNioTransport.stop();
//        execService.shutdown();
    }

    @Override
    public void beforeDispatchingMessage(MessageEvent messageEvent, Map piggyback) {
        if (messageEvent == null) {
            return;
        }
        SelectionKey selectionKey = null;
        if (piggyback != null) {
            Object value = piggyback.get(MESSAGE_SELECTION_KEY_TAG);
            if (value instanceof SelectionKey) {
                selectionKey = (SelectionKey) value;
            }
        }
        addRemotePeer(messageEvent.getSourcePeerID(), selectionKey);
    }

    @Override
    public void afterDispatchingMessage(MessageEvent messageEvent, Map piggyback) {
    }

    @Override
    @SuppressWarnings("unchecked")
    public void addRemotePeer(PeerID peerID) {
        if (peerID == null) {
            return;
        }
        if (peerID.equals(localPeerID)) {
            return; // lookback
        }
        String instanceName = peerID.getInstanceName();
        if (instanceName != null && peerID.getUniqueID() instanceof GrizzlyPeerID) {
//            PeerID<GrizzlyPeerID> previous = peerIDMap.get(instanceName);
//            if (previous != null) {
//                if (previous.getUniqueID().getTcpPort() != ((GrizzlyPeerID) peerID.getUniqueID()).tcpPort) {
//                    LOG.log(Level.WARNING, "addRemotePeer: assertion failure: no mapping should have existed for member:"
//                            + instanceName + " existingID=" + previous + " adding peerid=" + peerID, new Exception("stack trace"));
//                }
//            }
            PeerID<GrizzlyPeerID> previous = peerIDMap.put(instanceName, peerID);
            if (previous == null) {
                Level debugLevel = Level.FINEST;
                if (LOG.isLoggable(debugLevel)) {
                    LOG.log(debugLevel, "addRemotePeer: " + instanceName + " peerId:" + peerID, new Exception("stack trace"));
                }
            }
        }
    }

    public void removeRemotePeer(String instanceName) {
        for (Map.Entry<SelectionKey, String> entry : selectionKeyMap.entrySet()) {
            if (entry.getValue().equals(instanceName)) {
                if (getLogger().isLoggable(Level.FINE)) {
                    getLogger().log(Level.FINE, "remove selection key for instance name: " + entry.getValue() + " selectionKey:" + entry.getKey());
                }
                tcpSelectorHandler.getSelectionKeyHandler().cancel(entry.getKey());
                selectionKeyMap.remove(entry.getKey());
            }
        }
    }

    public void removeRemotePeer(SelectionKey selectionKey) {
        if (selectionKey == null) {
            return;
        }
        selectionKeyMap.remove(selectionKey);

        // Bug Fix. DO NOT REMOVE member name to peerid mapping when selection key is being removed.
        // THIS HAPPENS TOO FREQUENTLY.  Only remove this mapping when member fails or planned shutdown.\
        // This method was getting called by GrizzlyCacheableSelectionKeyHandler.cancel(SelectionKey).

        // use following line instead of remove call above if uncommenting the rest
//        String instanceName = selectionKeyMap.remove( selectionKey );
//      if( instanceName != null ) {
//          Level level = Level.FINEST;
//          if (LOG.isLoggable(level)) {
//              LOG.log(level, "removeRemotePeer selectionKey=" + selectionKey + " instanceName=" + instanceName,
//                      new Exception("stack trace"));
//          }
//          peerIDMap.remove( instanceName );
//      }
    }

    @Override
    public boolean send(final PeerID peerID, final Message message) throws IOException {
        if (!running) {
            throw new IOException("network manager is not running");
        }
        MessageSender sender = tcpSender;
        if (sender == null) {
            throw new IOException("message sender is not initialized");
        }
        return sender.send(peerID, message);
    }

    @Override
    public boolean broadcast(final Message message) throws IOException {
        if (!running) {
            throw new IOException("network manager is not running");
        }
        MulticastMessageSender sender = multicastSender;
        if (sender == null) {
            throw new IOException("multicast message sender is not initialized");
        }
        return sender.broadcast(message);
    }

    @Override
    public PeerID getPeerID(final String instanceName) {
        PeerID peerID = null;
        if (instanceName != null) {
            peerID = peerIDMap.get(instanceName);
        }
        if (peerID == null) {
            peerID = PeerID.NULL_PEER_ID;
            if (this.instanceName.equals(instanceName)) {
                LOG.log(Level.FINE, "grizzly.netmgr.localPeerId.null", new Object[]{instanceName});
                LOG.log(Level.FINE, "stack trace", new Exception("stack trace"));
            }
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "getPeerID({0}) returning null peerIDMap={1}",
                        new Object[]{instanceName, peerIDMap});
            }
        }
        return peerID;
    }

    @Override
    public void removePeerID(final PeerID peerID) {
        if (peerID == null) {
            return;
        }
        String instanceName = peerID.getInstanceName();
        if (instanceName == null) {
            return;
        }
        Level debugLevel = Level.FINE;
        if (LOG.isLoggable(debugLevel)) {
            LOG.log(debugLevel, "removePeerID peerid=" + peerID, new Exception("stack trace"));
        }
        peerIDMap.remove(instanceName);
        removeRemotePeer(instanceName);
    }

    @Override
    public boolean isConnected(final PeerID peerID) {
        boolean isConnected = false;
        if (peerID != null) {
            try {
                send(peerID, new MessageImpl(Message.TYPE_PING_MESSAGE));
                CountDownLatch latch = new CountDownLatch(1);
                CountDownLatch oldLatch = pingMessageLockMap.putIfAbsent(peerID, latch);
                if (oldLatch != null) {
                    latch = oldLatch;
                }
                try {
                    isConnected = latch.await(failTcpTimeout, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                }
            } catch (Throwable ie) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "isConnected( " + peerID + " ) = " + isConnected, ie);
                }
                return isConnected;
            } finally {
                pingMessageLockMap.remove(peerID);
            }
            return isConnected;
        } else {
            return isConnected;
        }
    }

    @Override
    public CountDownLatch getPingMessageLock(PeerID peerID) {
        if (peerID != null) {
            return pingMessageLockMap.get(peerID);
        } else {
            return null;
        }
    }

    @Override
    public MessageSender getMessageSender(int transport) {
        if (running) {
            MessageSender sender;
            switch (transport) {
                case TCP_TRANSPORT:
                    sender = tcpSender;
                    break;
                case UDP_TRANSPORT:
                    sender = udpSender;
                    break;
                default:
                    sender = tcpSender;
                    break;
            }
            return sender;
        } else {
            return null;
        }
    }

    @Override
    public MulticastMessageSender getMulticastMessageSender() {
        if (running) {
            return multicastSender;
        } else {
            return null;
        }
    }

    @Override
    protected Logger getGrizzlyLogger() {
        return Grizzly.logger(GrizzlyNetworkManager.class);
    }

    private static int bind(final TCPNIOTransport transport,
            final InetAddress inetAddress, final int tcpStartPort,
            final int tcpEndPort) throws IOException {

        IOException lastIOException = null;

        final int delta = sgn(tcpEndPort - tcpStartPort);
        int port = tcpStartPort - delta;

        do {
            port += delta;
            try {
                transport.bind(new InetSocketAddress(inetAddress, port));
                return port;
            } catch (IOException e) {
                lastIOException = e;
            }
        } while (port != tcpEndPort);

        throw lastIOException;
    }

    private static int sgn(int v) {
        if (v > 0) {
            return 1;
        } else if (v < 0) {
            return -1;
        }

        return 0;
    }
}