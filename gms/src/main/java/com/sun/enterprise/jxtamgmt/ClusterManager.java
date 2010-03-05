/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
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

package com.sun.enterprise.jxtamgmt;

import com.sun.enterprise.ee.cms.core.MemberNotInViewException;
import com.sun.enterprise.ee.cms.core.GroupManagementService;
import com.sun.enterprise.ee.cms.impl.jxta.CustomTagNames;
import com.sun.enterprise.ee.cms.core.GMSConstants;
import static com.sun.enterprise.jxtamgmt.JxtaUtil.getObjectFromByteArray;
import net.jxta.document.*;
import net.jxta.endpoint.*;
import net.jxta.exception.PeerGroupException;
import net.jxta.id.ID;
import net.jxta.impl.endpoint.tcp.TcpTransport;
import net.jxta.impl.pipe.BlockingWireOutputPipe;
import net.jxta.peer.PeerID;
import net.jxta.peergroup.PeerGroup;
import net.jxta.pipe.*;
import net.jxta.protocol.PipeAdvertisement;
import net.jxta.protocol.RouteAdvertisement;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The ClusterManager is the entry point for using the JxtaClusterManagement module
 * which provides group communications and membership semantics on top of JXTA.
 */
public class ClusterManager implements PipeMsgListener {
    private static final Logger LOG = JxtaUtil.getLogger(ClusterManager.class.getName());
    private MasterNode masterNode = null;
    private ClusterViewManager clusterViewManager = null;
    private HealthMonitor healthMonitor = null;
    private NetworkManager netManager = null;
    private String groupName = null;
    private String instanceName = null;
    private String bindInterfaceAddress = null;
    private volatile boolean started = false;
    private volatile boolean stopped = true;
    private boolean loopbackMessages = false;
    private final Object closeLock = new Object();
    private SystemAdvertisement systemAdv = null;

    private static final String NODEADV = "NAD";
    private transient Map<String, String> identityMap;
    private transient Map<PeerID, RouteAdvertisement> routeCache = new ConcurrentHashMap<PeerID, RouteAdvertisement>();
    private PipeAdvertisement pipeAdv;
    private PipeService pipeService;
    private MessageElement sysAdvElement = null;
    private InputPipe inputPipe;
    private OutputPipe outputPipe;
    private static final String NAMESPACE = "CLUSTER_MANAGER";
    private PeerID myID;
    private static final String APPMESSAGE = "APPMESSAGE";
    private List<ClusterMessageListener> cmListeners;
    private volatile boolean stopping = false;
    private transient Map<ID, OutputPipe> pipeCache = new ConcurrentHashMap<ID, OutputPipe>();

    final Object MASTERBYFORCELOCK = new Object();
     final private String memberType;

    /**
     * The ClusterManager is created using the instanceName,
     * and a Properties object that contains a set of parameters that the
     * employing application would like to set for its purposes, namely,
     * configuration parameters such as failure detection timeout, retries,
     * address and port on which to communicate with the group, group and
     * instance IDs,etc. The set of allowed constants to be used as keys in the
     * Properties object are specified in JxtaConfigConstants enum.
     *
     * @param groupName        Name of Group to which this process/peer seeks
     *                         membership
     * @param instanceName     A token identifying this instance/process
     * @param identityMap      Additional identity tokens can be specified through
     *                         this Map object. These become a part of the
     *                         SystemAdvertisement allowing the peer/system to
     *                         be identified by the application layer based on their
     *                         own notion of identity
     * @param props            a Properties object containing parameters that are
     *                         allowed to be specified by employing application
     *                         //TODO: specify that INFRA IDs and address/port are
     *                         composite keys in essence but addresses/ports could
     *                         be shared across ids with a performance penalty.
     *                         //TODO: provide an API to send messages, synchronously or asynchronously
     * @param viewListeners    listeners interested in group view change events
     * @param messageListeners listeners interested in receiving messages.
     */
    public ClusterManager(final String groupName,
                          final String instanceName,
                          final Map<String, String> identityMap,
                          final Map props,
                          final List<ClusterViewEventListener> viewListeners,
                          final List<ClusterMessageListener> messageListeners) {
        this.memberType = (String)identityMap.get(CustomTagNames.MEMBER_TYPE.toString());
        this.groupName = groupName;
        this.instanceName = instanceName;
        this.loopbackMessages = isLoopBackEnabled(props);
        //TODO: ability to specify additional rendezvous and also bootstrap a default rendezvous
        //TODO: revisit and document auto composition of transports
        this.netManager = new NetworkManager(groupName, instanceName, props);
        this.identityMap = identityMap;
        try {
            netManager.start();
        } catch (PeerGroupException pge) {
            LOG.log(Level.SEVERE, pge.getLocalizedMessage());
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getLocalizedMessage());
        }
        NetworkManagerRegistry.add(groupName, netManager);
        if(props !=null && !props.isEmpty()){
            this.bindInterfaceAddress = (String)props.get(JxtaConfigConstants.BIND_INTERFACE_ADDRESS.toString());
        }
        systemAdv = createSystemAdv(netManager.getNetPeerGroup(), instanceName, identityMap, bindInterfaceAddress);
        LOG.log(Level.FINER, "Instance ID :" + getSystemAdvertisement().getID());
        if (isWatchdog()) {
            this.clusterViewManager = null;
            this.masterNode = null;
        } else {
            this.clusterViewManager = new ClusterViewManager(getSystemAdvertisement(), this, viewListeners);
            this.masterNode = new MasterNode(this, getDiscoveryTimeout(props), 1);
        }

        this.healthMonitor = new HealthMonitor(this,
                getFailureDetectionTimeout(props),
                getFailureDetectionRetries(props),
                getVerifyFailureTimeout(props),
                getFailureDetectionTcpRetransmitTimeout(props),
                getFailureDetectionTcpRetransmitPort(props));

        pipeService = netManager.getNetPeerGroup().getPipeService();
        myID = netManager.getNetPeerGroup().getPeerID();
        try {
            // create the pipe advertisement, to be used in creating the pipe
            pipeAdv = createPipeAdv();
            //create output
            outputPipe = pipeService.createOutputPipe(pipeAdv, 100);
        } catch (IOException io) {
            LOG.log(Level.FINE, "Failed to create master outputPipe", io);
        }
        cmListeners = messageListeners;
        sysAdvElement = new TextDocumentMessageElement(NODEADV,
                (XMLDocument) getSystemAdvertisement()
                        .getDocument(MimeMediaType.XMLUTF8), null);
    }

     public boolean isWatchdog() {
         return GroupManagementService.MemberType.WATCHDOG.toString().equals(memberType);
     }

    private boolean isLoopBackEnabled(final Map props) {
        boolean loopback = false;
        if (props != null && !props.isEmpty()) {
            Object lp = props.get(JxtaConfigConstants.LOOPBACK.toString());
            if (lp != null) {
                loopback = Boolean.parseBoolean((String) lp);
            }
        }
        return loopback;
    }

    private long getDiscoveryTimeout(Map props) {
        long discTimeout = 5000;
        if (props != null && !props.isEmpty()) {
            Object dt = props.get(JxtaConfigConstants.DISCOVERY_TIMEOUT.toString());
            if (dt != null) {
                discTimeout = Long.parseLong((String) dt);
            }
        }
        return discTimeout;
    }

    private long getFailureDetectionTimeout(Map props) {
        long failTimeout = 3000;
        if (props != null && !props.isEmpty()) {
            Object ft = props.get(JxtaConfigConstants.FAILURE_DETECTION_TIMEOUT.toString());
            if (ft != null) {
                failTimeout = Long.parseLong((String) ft);
            }
        }
        return failTimeout;
    }

    private int getFailureDetectionRetries(Map props) {
        int failRetry = 3;

        if (props != null && !props.isEmpty()) {
            Object fr = props.get(JxtaConfigConstants.FAILURE_DETECTION_RETRIES.toString());
            if (fr != null) {
                failRetry = Integer.parseInt((String) fr);
            }
        }
        return failRetry;
    }

    private long getFailureDetectionTcpRetransmitTimeout(Map props) {
        long failTcpTimeout = 10000;   // sailfin requirement to discover network outage under 30 seconds.
                                       // fix for sailfin 626.
                                       // HealthMonitor.isConnected() is called twice and must time out twice, using 20 seconds.
                                       // indoubt detection and failure verification takes 8-10 seconds.
        if (props != null && !props.isEmpty()) {
            Object ft = props.get(JxtaConfigConstants.FAILURE_DETECTION_TCP_RETRANSMIT_TIMEOUT.toString());
            if (ft != null) {
                failTcpTimeout = Long.parseLong((String) ft);
            }
        }
        return failTcpTimeout;
    }

    private int getFailureDetectionTcpRetransmitPort(Map props) {
        int failTcpPort = 9000;
        if (props != null && !props.isEmpty()) {
            Object ft = props.get(JxtaConfigConstants.FAILURE_DETECTION_TCP_RETRANSMIT_PORT.toString());
            if (ft != null) {
                failTcpPort = Integer.parseInt((String) ft);
            }
        }
        return failTcpPort;
    }

    private long getVerifyFailureTimeout(Map props) {
        long verifyTimeout = 2000;
        if (props != null && !props.isEmpty()) {
            Object vt = props.get(JxtaConfigConstants.FAILURE_VERIFICATION_TIMEOUT.toString());
            if (vt != null) {
                verifyTimeout = Long.parseLong((String) vt);
            }
        }
        return verifyTimeout;
    }

    public void addClusteMessageListener(final ClusterMessageListener listener) {
        cmListeners.add(listener);
    }

    public void removeClusterViewEventListener(
            final ClusterMessageListener listener) {
        cmListeners.remove(listener);
    }

    /**
     * @param argv none defined
     */
    public static void main(final String[] argv) {
        JxtaUtil.setupLogHandler();
        LOG.setLevel(Level.FINEST);
        final String name = System.getProperty("INAME", "instanceName");
        final String groupName = System.getProperty("GNAME", "groupName");
        LOG.log(Level.FINER, "Instance Name :" + name);
        final Map props = getPropsForTest();
        final Map<String, String> idMap = getIdMap();
        final List<ClusterViewEventListener> vListeners =
                new ArrayList<ClusterViewEventListener>();
        final List<ClusterMessageListener> mListeners =
                new ArrayList<ClusterMessageListener>();
        vListeners.add(
                new ClusterViewEventListener() {
                    public void clusterViewEvent(
                            final ClusterViewEvent event,
                            final ClusterView view) {
                        LOG.log(Level.INFO, "event.message", new Object[]{event.getEvent().toString()});
                        LOG.log(Level.INFO, "peer.involved", new Object[]{event.getAdvertisement().toString()});
                        LOG.log(Level.INFO, "view.message", new Object[]{view.getPeerNamesInView().toString()});
                    }
                });
        mListeners.add(
                new ClusterMessageListener() {
                    public void handleClusterMessage(
                            final SystemAdvertisement id, final Object message) {
                        LOG.log(Level.INFO, id.getName());
                        LOG.log(Level.INFO, message.toString());
                    }
                }
        );
        final ClusterManager manager = new ClusterManager(groupName,
                name,
                idMap,
                props,
                vListeners,
                mListeners);
        manager.start();
        manager.waitForClose();
    }

    //TODO: NOT YET IMPLEMENTED
    private static Map<String, String> getIdMap() {
        return new HashMap<String, String>();
    }

    //TODO: NOT YET IMPLEMENTED
    private static Map getPropsForTest() {
        return new HashMap();
    }


    /**
     * Stops the ClusterManager and all it's services
     *
     * @param isClusterShutdown true if this peer is shutting down as part of cluster wide shutdown
     */
    public synchronized void stop(final boolean isClusterShutdown) {
        if (!stopped) {
            stopping = true;
            healthMonitor.stop(isClusterShutdown);
            outputPipe.close();
            inputPipe.close();
            pipeCache.clear();
            if (!isWatchdog()) {
                masterNode.stop();
            }
            netManager.stop();
            NetworkManagerRegistry.remove(groupName);
            stopped = true;
            synchronized (closeLock) {
                closeLock.notify();
            }
        }
    }

    /**
     * Starts the ClusterManager and all it's services
     */
    public synchronized void start() {
        if (!started) {
            try {
                inputPipe = pipeService.createInputPipe(pipeAdv, this);
            } catch (IOException ioe) {
                LOG.log(Level.SEVERE, "Failed to create service input pipe: " + ioe);
            }
            if (!isWatchdog()) {
                masterNode.start();
            }
            healthMonitor.start();
            started = true;
            stopped = false;
        }
    }

    /**
     * Returns the NetworkManager instance
     *
     * @return The networkManager value
     */
    public NetworkManager getNetworkManager() {
        return netManager;
    }

    /**
     * Returns the MasterNode instance
     *
     * @return The masterNode value
     */
    public MasterNode getMasterNode() {
        return masterNode;
    }

    /**
     * Returns the HealthMonitor instance.
     *
     * @return The healthMonitor value
     */
    public HealthMonitor getHealthMonitor() {
        return healthMonitor;
    }

    /**
     * Returns the ClusterViewManager object.  All modules use this common object which
     * represents a synchronized view of a set of AppServers
     *
     * @return The clusterViewManager object
     */
    public ClusterViewManager getClusterViewManager() {
        return clusterViewManager;
    }

    /**
     * Gets the foundation Peer Group of the ClusterManager
     *
     * @return The netPeerGroup value
     */
    public PeerGroup getNetPeerGroup() {
        return netManager.getNetPeerGroup();
    }

    /**
     * Gets the instance name
     *
     * @return The instance name
     */
    public String getInstanceName() {
        return instanceName;
    }

    public boolean isMaster() {
        return clusterViewManager.isMaster() && masterNode.isMasterAssigned();
    }

    /**
     * Ensures the ClusterManager continues to run.
     */
    private void waitForClose() {
        try {
            LOG.log(Level.FINER, "Waiting for close");
            synchronized (closeLock) {
                closeLock.wait();
            }
            stop(false);
            LOG.log(Level.FINER, "Good Bye");
        } catch (InterruptedException e) {
            LOG.log(Level.WARNING, e.getLocalizedMessage());
        }
    }

    /**
     * in the event of a failure or planned shutdown, remove the
     * pipe from the pipeCache
     */
    public void removePipeFromCache(ID token) {
        pipeCache.remove(token);
    }

    /**
     * Send a message to a specific node or the group. In the case where the id
     * is null the message is sent to the entire group
     *
     * @param peerid the node ID
     * @param msg    the message to send
     * @return boolean <code>true</code> if the message has been sent otherwise
     * <code>false</code>. <code>false</code>. is commonly returned for
     * non-error related congestion, meaning that you should be able to send
     * the message after waiting some amount of time.
     * @throws java.io.IOException if an io error occurs
     */
    public boolean send(final ID peerid, final Serializable msg) throws IOException, MemberNotInViewException {
        boolean sent = false;
        if (!stopping) {
            final Message message = new Message();
            message.addMessageElement(NAMESPACE, sysAdvElement);
            final ByteArrayMessageElement bame =
                    new ByteArrayMessageElement(APPMESSAGE,
                    MimeMediaType.AOS,
                    JxtaUtil.createByteArrayFromObject(msg),
                    null);
            message.addMessageElement(NAMESPACE, bame);

            if (peerid != null) {
                //check if the peerid is part of the cluster view
                if (getClusterViewManager().containsKey(peerid)) {
                    LOG.fine("ClusterManager.send : Cluster View contains " + peerid.toString());

                    OutputPipe output = pipeCache.get(peerid);
                    RouteAdvertisement route = null;
                    final int MAX_RETRIES = 2;
                    IOException lastOne = null;
                    for (int createOutputPipeAttempts = 0; output == null && createOutputPipeAttempts < MAX_RETRIES; createOutputPipeAttempts++) {
                        route = getCachedRoute((PeerID) peerid);
                        if (route != null) {
                            try {
                                output = new BlockingWireOutputPipe(getNetPeerGroup(), pipeAdv, (PeerID) peerid, route);
                            } catch (IOException ioe) {
                                lastOne = ioe;
                            }
                        }
                        if (output == null) {
                            // Unicast datagram
                            // create a op pipe to the destination peer
                            try {
                                output = pipeService.createOutputPipe(pipeAdv, Collections.singleton(peerid), 1);
                                if (LOG.isLoggable(Level.FINE) && output != null) {
                                    LOG.fine("ClusterManager.send : adding output to cache without route creation : " + peerid);
                                }
                            } catch (IOException ioe) {
                                lastOne = ioe;
                            }
                        }
                    }
                    if (output != null) {
                        pipeCache.put(peerid, output);
                        sent = JxtaUtil.send(output, message);
                        if (!sent) {
                            LOG.warning("ClusterManager.send: message " + message + " not sent to " + peerid + " OutputPipe.send attempted resend " +
                                    + JxtaUtil.MAX_SEND_RETRIES + " and they all returned false.");
                        }
                    } else {
                        LOG.log(Level.WARNING, "ClusterManager.send : sending of message " + message + " failed. Unable to create an OutputPipe for " + peerid +
                                    " route = " + route, lastOne);
                        return sent;
                    }
                } else {
                    LOG.fine("ClusterManager.send : Cluster View does not contain " + peerid.toString() + " hence will not send message.");
                    throw new MemberNotInViewException("Member " + peerid +
                            " is not in the View anymore. Hence not performing sendMessage operation");
                }
            } else {
                // multicast
                LOG.log(Level.FINER, "Broadcasting Message");
                sent = JxtaUtil.send(outputPipe, message);
                if (!sent) {
                    LOG.warning("ClusterManager.send: broadcast of message " + message + " failed." + " OutputPipe.send attempted resend " +
                            + JxtaUtil.MAX_SEND_RETRIES + " and they all returned false.");
                }
            }
        }
        //JxtaUtil.printMessageStats(message, true);
        return sent;
    }

    /**
     * Returns a pipe advertisement for Cluster messaging of propagate type
     *
     * @return a pipe advertisement for Cluster messaging
     */
    private PipeAdvertisement createPipeAdv() {
        final PipeAdvertisement pipeAdv;
        // create the pipe advertisement, to be used in creating the pipe
        pipeAdv = (PipeAdvertisement)
                AdvertisementFactory.newAdvertisement(
                        PipeAdvertisement.getAdvertisementType());
        pipeAdv.setPipeID(getNetworkManager().getAppServicePipeID());
        pipeAdv.setType(PipeService.PropagateType);
        return pipeAdv;
    }

    /**
     * {@inheritDoc}
     */
    public void pipeMsgEvent(final PipeMsgEvent event) {
        if (started && !stopping) {
            final Message msg;
            MessageElement msgElement;
            // grab the message from the event
            try {
                msg = event.getMessage();
                if (msg == null) {
                    LOG.log(Level.WARNING, "Received a null message");
                    return;
                }
                //JxtaUtil.printMessageStats(msg, true);
                LOG.log(Level.FINEST, "ClusterManager:Received a AppMessage ");
                msgElement = msg.getMessageElement(NAMESPACE, NODEADV);
                if (msgElement == null) {
                    // no need to go any further
                    LOG.log(Level.WARNING, "Received an unknown message");
                    return;
                }

                final SystemAdvertisement adv;
                final StructuredDocument asDoc = StructuredDocumentFactory.newStructuredDocument(msgElement.getMimeType(), msgElement.getStream());
                adv = new SystemAdvertisement(asDoc);
                final PeerID srcPeerID = (PeerID) adv.getID();
                if (!loopbackMessages) {
                    if (srcPeerID.equals(myID)) {
                        LOG.log(Level.FINEST, "CLUSTERMANAGER:Discarding loopback message");
                        return;
                    }
                }

                msgElement = msg.getMessageElement(NAMESPACE, APPMESSAGE);
                if (msgElement != null) {
                    final Object appMessage = getObjectFromByteArray(msgElement);
                    if (appMessage != null) {
                        LOG.log(Level.FINEST, "ClusterManager: Notifying APPMessage Listeners of " +
                                appMessage.toString() + "and adv = " + adv.getName());
                        notifyMessageListeners(adv, appMessage);
                    }
                }
            } catch (Throwable e) {
                LOG.log(Level.WARNING, e.getLocalizedMessage());
            }
        }
    }

    private void notifyMessageListeners(final SystemAdvertisement senderSystemAdvertisement, final Object appMessage) {
        for (ClusterMessageListener listener : cmListeners) {
            listener.handleClusterMessage(senderSystemAdvertisement, appMessage);
        }
    }

    public SystemAdvertisement getSystemAdvertisementForMember(final ID id) {
        return clusterViewManager.get(id);
    }

    /**
     * Gets the systemAdvertisement attribute of the JXTAPlatform object
     *
     * @return The systemAdvertisement value
     */
    public SystemAdvertisement getSystemAdvertisement() {
        if (systemAdv == null) {
            systemAdv = createSystemAdv(netManager.getNetPeerGroup(), instanceName, identityMap, bindInterfaceAddress);
        }
        return systemAdv;
    }

    public PeerID getNodeID() {
        return myID;
    }

    /**
     * Given a peergroup and a SystemAdvertisement is returned
     *
     * @param group      peer group, used to obtain peer id
     * @param name       host name
     * @param customTags A Map object. These are additional system identifiers
     *                   that the application layer can provide for its own
     *                   identification.
     * @return SystemAdvertisement object
     */
    private static synchronized SystemAdvertisement createSystemAdv(final PeerGroup group,
                                                                    final String name,
                                                                    final Map<String, String> customTags,
                                                                    final String bindInterfaceAddress) {
        if (group == null) {
            throw new IllegalArgumentException("Group can not be null");
        }
        if (name == null) {
            throw new IllegalArgumentException("instance name can not be null");
        }
        final SystemAdvertisement sysAdv = new SystemAdvertisement();
        sysAdv.setID(group.getPeerID());
        sysAdv.setName(name);
        setBindInterfaceAddress(sysAdv, bindInterfaceAddress, group);
        sysAdv.setOSName(System.getProperty("os.name"));
        sysAdv.setOSVersion(System.getProperty("os.version"));
        sysAdv.setOSArch(System.getProperty("os.arch"));
        sysAdv.setHWArch(System.getProperty("HOSTTYPE", System.getProperty("os.arch")));
        sysAdv.setHWVendor(System.getProperty("java.vm.vendor"));
        sysAdv.setCustomTags(customTags);
        return sysAdv;
    }

    static private void setBindInterfaceAddress(SystemAdvertisement sysAdv, String bindInterfaceAddress, PeerGroup group) {
        EndpointAddress bindInterfaceEndpointAddress = null;
        if (bindInterfaceAddress != null && !bindInterfaceAddress.equals("")) {
            final String TCP_SCHEME = "tcp://";
            final String PORT = ":4000";  // necessary to add a port but its value is ignored.
            String bindInterfaceAddressURI = TCP_SCHEME + bindInterfaceAddress + PORT;
            try {
                bindInterfaceEndpointAddress = new EndpointAddress(bindInterfaceAddressURI);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "invalid bindInterfaceEndpointAddress URI=" + bindInterfaceAddressURI + " computed from property " +
                                       JxtaConfigConstants.BIND_INTERFACE_ADDRESS.toString() +
                                       " value=" + bindInterfaceAddress, e);
            }
        }
        if (bindInterfaceEndpointAddress != null) {
            if (LOG.isLoggable(Level.CONFIG)) {
                LOG.config("Configured bindInterfaceEndpointAddress URI " + bindInterfaceEndpointAddress.toString() +
                           " using property " + JxtaConfigConstants.BIND_INTERFACE_ADDRESS.toString() +
                           " value=" + bindInterfaceAddress);
            }
            sysAdv.addEndpointAddress(bindInterfaceEndpointAddress);
        } else {
            // lookup all public addresses
            TcpTransport tcpTransport = (TcpTransport) group.getEndpointService().getMessageTransport("tcp");
            Iterator it = tcpTransport.getPublicAddresses();
            while (it.hasNext()) {
                sysAdv.addEndpointAddress((EndpointAddress) it.next());
            }
        }
    }

    public String getNodeState(final ID peerID, long threshold, long timeout) {
        return getHealthMonitor().getMemberState((PeerID) peerID, threshold, timeout);
    }

    /**
     * Returns name encoded ID
     *
     * @param name to name to encode
     * @return name encoded ID
     */
    public ID getID(final String name) {
        return netManager.getPeerID(name);
    }

    boolean isStopping() {
        return stopping;
    }

    public void takeOverMasterRole() {
        masterNode.takeOverMasterRole();
        //wait until the new Master gets forcefully appointed
        //before processing any further requests.
        waitFor(2000);
    }

    public void setClusterStopping() {
        masterNode.setClusterStopping();
    }

    public void waitFor(long msec) {
        try {
            synchronized (MASTERBYFORCELOCK) {
                MASTERBYFORCELOCK.wait(msec);
            }
        } catch (InterruptedException intr) {
            Thread.interrupted();
            LOG.log(Level.FINER, "Thread interrupted", intr);
        }
    }

    public void notifyNewMaster() {
        synchronized (MASTERBYFORCELOCK) {
            MASTERBYFORCELOCK.notify();
        }
    }

    public void reportJoinedAndReadyState() {
        healthMonitor.reportJoinedAndReadyState();
    }

    /**
     * Caches a route for an instance
     *
     * @param route the route advertisement
     */
    public void cacheRoute(RouteAdvertisement route) {
        routeCache.put(route.getDestPeerID(), route);
    }

    /**
     * returns the cached route if any, null otherwise
     *
     * @param peerid the instance id
     * @return the cached route if any, null otherwise
     */
    public RouteAdvertisement getCachedRoute(PeerID peerid) {
        return routeCache.get(peerid);
    }

    /**
     * in the event of a failure or planned shutdown, remove the
     * route from the routeCache
     */
    void removeRouteFromCache(ID token) {
        routeCache.remove(token);
    }

    void clearAllCaches() {
        routeCache.clear();
        pipeCache.clear();
    }

    public void groupStartup(GMSConstants.groupStartupState startupState, List<String> memberTokens) {
        getMasterNode().groupStartup(startupState, memberTokens);
    }

    public boolean isGroupStartup() {
        return getMasterNode().isGroupStartup();
    }

    public String getGroupName() {
        return groupName;
    }

    public boolean isDiscoveryInProgress() {
        return masterNode.isDiscoveryInProgress();
    }

}
