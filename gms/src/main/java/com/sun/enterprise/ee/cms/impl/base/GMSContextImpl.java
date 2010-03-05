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

package com.sun.enterprise.ee.cms.impl.base;

import com.sun.enterprise.ee.cms.core.DistributedStateCache;
import com.sun.enterprise.ee.cms.core.GMSConstants;
import com.sun.enterprise.ee.cms.core.GMSException;
import com.sun.enterprise.ee.cms.core.GroupHandle;
import com.sun.enterprise.ee.cms.core.GroupManagementService;
import com.sun.enterprise.ee.cms.impl.common.GMSContextBase;
import com.sun.enterprise.ee.cms.impl.common.ShutdownHelper;
import com.sun.enterprise.ee.cms.spi.GMSMessage;
import com.sun.enterprise.ee.cms.spi.GroupCommunicationProvider;
import static com.sun.enterprise.ee.cms.core.GroupManagementService.MemberType.WATCHDOG;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Level;

/**
 * @author Shreedhar Ganapathy
 *         Date: Jun 26, 2006
 * @version $Revision$
 */
public class GMSContextImpl extends GMSContextBase {
    private ArrayBlockingQueue<EventPacket> viewQueue;
    private static final int MAX_VIEWS_IN_QUEUE = 200;
    private ArrayBlockingQueue<MessagePacket> messageQueue;
    private static final int MAX_MSGS_IN_QUEUE = 500;
    private ViewWindowImpl viewWindow;
    private GroupCommunicationProvider groupCommunicationProvider;
    private DistributedStateCache distributedStateCache;
    private GroupHandle gh;
    private Properties configProperties;
    private boolean isGroupShutdown = false;  //remember if this context has left the group during a group shutdown.
    private boolean isGroupStartup = false;
    private Thread viewWindowThread = null;
    private Thread messageWindowThread = null;

    public GMSContextImpl(final String serverToken, final String groupName,
                      final GroupManagementService.MemberType memberType,
                      final Properties configProperties) {
        super(serverToken, groupName, memberType);
        this.configProperties = configProperties;
        groupCommunicationProvider =
                new GroupCommunicationProviderImpl(groupName);

        if (isWatchdog()) {
            // lower overhead by not having view management for WATCHDOG.
            viewQueue = null;
            viewWindow = null;
        } else {
            viewQueue = new ArrayBlockingQueue<EventPacket>(MAX_VIEWS_IN_QUEUE,
                    Boolean.TRUE);
            viewWindow = new ViewWindowImpl(groupName, viewQueue);
        }
        messageQueue = new ArrayBlockingQueue<MessagePacket>(MAX_MSGS_IN_QUEUE, Boolean.TRUE);
        gh = new GroupHandleImpl(groupName, serverToken);
        //TODO: consider untying the Dist State Cache creation from GMSContext.
        // It should be driven independent of GMSContext through a factory as
        // other impls of this interface can exist
        createDistributedStateCache();
        logger.log(Level.FINE,  "gms.init");
    }

    protected void createDistributedStateCache() {
        if (isWatchdog()) {
            distributedStateCache = null;
        } else {
            distributedStateCache = DistributedStateCacheImpl.getInstance(groupName);
        }
    }

    /**
     * returns Group handle
     *
     * @return Group handle
     */
    public GroupHandle getGroupHandle() {
        return gh;
    }

    public DistributedStateCache getDistributedStateCache() {
        // Never create a distributed state cache for a WATCHDOG.
        if (distributedStateCache == null && !isWatchdog()) {
            createDistributedStateCache();
        }
        return distributedStateCache;
    }

    public void join() throws GMSException {
        viewWindowThread = isWatchdog() ? null : new Thread(viewWindow, "ViewWindowThread:" + groupName);
        MessageWindow messageWindow = new MessageWindow(groupName, messageQueue);

        messageWindowThread = new Thread(messageWindow, "MessageWindowThread:" + groupName);
        messageWindowThread.start();

        if (viewWindowThread != null) {
            viewWindowThread.start();
        }

        final Map<String, String> idMap = new HashMap<String, String>();
        idMap.put(CustomTagNames.MEMBER_TYPE.toString(), memberType);
        idMap.put(CustomTagNames.GROUP_NAME.toString(), groupName);
        idMap.put(CustomTagNames.START_TIME.toString(), startTime.toString());

        groupCommunicationProvider.initializeGroupCommunicationProvider(
                serverToken, groupName, idMap, configProperties);
        groupCommunicationProvider.join();
    }

    public void leave(final GMSConstants.shutdownType shutdownType) {
        if(shutdownHelper.isGroupBeingShutdown(groupName)){
            logger.log(Level.INFO, "shutdown.groupshutdown", new Object[] {groupName});
            groupCommunicationProvider.leave(true);
            isGroupShutdown = true;
            shutdownHelper.removeFromGroupShutdownList(groupName);
        }
        else {
            logger.log(Level.INFO, "shutdown.instanceshutdown", new Object[] {groupName});
            groupCommunicationProvider.leave(false);
        }
        shuttingDown = true;
        if( viewWindowThread != null ) {
            viewWindowThread.interrupt();
        }
        if( messageWindowThread != null ) {
            messageWindowThread.interrupt();
        }
        if( router != null ) {
            router.shutdown();
        }
    }

    public long getStartTime() {
        return startTime;
    }

    public void announceGroupShutdown(final String groupName,
                                      final GMSConstants.shutdownState shutdownState) {
        // if not groupleader, seize it before shutting down all other members of the group.
        if (!this.getGroupCommunicationProvider().isGroupLeader()) {
            logger.log(Level.INFO, "Assuming group leadership to shutdown group: " + groupName);
            assumeGroupLeadership();
        }
        groupCommunicationProvider.
                announceClusterShutdown(
                        new GMSMessage(GMSConstants.shutdownType.GROUP_SHUTDOWN.toString(), null,
                                groupName, null));
    }

     public void announceGroupStartup(final String groupName,
                                      final GMSConstants.groupStartupState startupState,
                                     final List<String> memberTokens) {
       groupCommunicationProvider.
                announceGroupStartup(groupName, startupState, memberTokens);
    }

    public boolean addToSuspectList(final String token) {
        boolean retval = false;
        synchronized (suspectList) {
            if (!suspectList.contains(token)) {
                suspectList.add(token);
                retval = true;
            }
        }
        return retval;
    }

    public void removeFromSuspectList(final String token) {
        synchronized (suspectList) {
            if (suspectList.contains(token)) {
                suspectList.remove(token);
            }
        }
    }

    public boolean isSuspected(final String token) {
        boolean retval = false;
        synchronized (suspectList) {
            if (suspectList.contains(token)) {
                retval = true;
            }
        }
        return retval;
    }

    public List<String> getSuspectList() {
        final List<String> retval;
        synchronized (suspectList) {
            retval = new ArrayList<String>(suspectList);
        }
        return retval;
    }

    public ShutdownHelper getShutdownHelper() {
        return shutdownHelper;
    }

    ArrayBlockingQueue<EventPacket> getViewQueue() {
        return viewQueue;
    }

    ArrayBlockingQueue<MessagePacket> getMessageQueue() {
        return messageQueue;
    }

    public GroupCommunicationProvider getGroupCommunicationProvider() {
        return groupCommunicationProvider;
    }

    public com.sun.enterprise.ee.cms.impl.common.ViewWindow getViewWindow() {
        return viewWindow;
    }

    public void assumeGroupLeadership() {
        groupCommunicationProvider.assumeGroupLeadership();
    }

    public boolean isGroupBeingShutdown(String groupName) {
        return isGroupShutdown || getShutdownHelper().isGroupBeingShutdown(groupName);
    }

    public boolean isGroupStartup() {
        return isGroupStartup;
    }

    public void  setGroupStartup(boolean value) {
        isGroupStartup = value;
    }

    public boolean isWatchdog() {
        return this.getMemberType() == WATCHDOG;
    }
}