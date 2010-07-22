/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2010 Sun Microsystems, Inc. All rights reserved.
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
package com.sun.enterprise.ee.cms.impl.common;

import com.sun.enterprise.ee.cms.core.*;
import com.sun.enterprise.ee.cms.impl.client.FailureNotificationActionFactoryImpl;
import com.sun.enterprise.ee.cms.impl.client.JoinedAndReadyNotificationActionFactoryImpl;
import com.sun.enterprise.ee.cms.impl.client.PlannedShutdownActionFactoryImpl;
import com.sun.enterprise.ee.cms.logging.GMSLogDomain;
import com.sun.enterprise.ee.cms.spi.MemberStates;

import java.util.LinkedList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AliveAndReadyViewWindow {
    protected static final Logger LOG = Logger.getLogger(GMSLogDomain.GMS_LOGGER + ".ready");

    static final long MIN_VIEW_DURATION = 1000;  // 1 second
    private long MAX_CLUSTER_STARTTIME_DURATION_MS = 10000; // todo: revisit this constant of 10 seconds for cluster startup.

    static final int MAX_ALIVE_AND_READY_VIEWS = 5;
    private final List<AliveAndReadyView> aliveAndReadyView = new LinkedList<AliveAndReadyView>();
    private long viewId = 0;

    private JoinedAndReadyNotificationActionFactoryImpl joinedAndReadyActionFactory = null;
    private FailureNotificationActionFactoryImpl failureActionFactory = null;
    private PlannedShutdownActionFactoryImpl plannedShutdownFactory = null;

    final private JoinedAndReadyCallBack jrcallback;
    final private LeaveCallBack leaveCallback;

    private long simulatedStartClusterTime;
    private AtomicBoolean isSimulatedStartCluster = new AtomicBoolean(false);
    private final String currentInstanceName;

    // map from JoinedAndReady memberName to its DAS ready members
    private ConcurrentHashMap<String, SortedSet<String>> joinedAndReadySignalReadyList= new ConcurrentHashMap<String, SortedSet<String>>();

    // set to Level.INFO to aid debugging.
    static private final Level TRACE_LEVEL = Level.FINE;

    public AliveAndReadyViewWindow(GMSContext ctx) {
        Router router = ctx.getRouter();
        currentInstanceName = ctx.getServerIdentityToken();

        jrcallback = new JoinedAndReadyCallBack(ctx.getGroupHandle(), aliveAndReadyView);
        joinedAndReadyActionFactory = new JoinedAndReadyNotificationActionFactoryImpl(jrcallback);

        leaveCallback = new LeaveCallBack(ctx.getGroupHandle(), aliveAndReadyView);
        failureActionFactory = new FailureNotificationActionFactoryImpl(leaveCallback);
        plannedShutdownFactory = new PlannedShutdownActionFactoryImpl(leaveCallback);

        router.addSystemDestination(joinedAndReadyActionFactory);
        router.addSystemDestination(failureActionFactory);
        router.addSystemDestination(plannedShutdownFactory);

        // initialize with a null initial previous and current views
        aliveAndReadyView.add(new AliveAndReadyViewImpl(new TreeSet<String>(), viewId++));
        aliveAndReadyView.add(new AliveAndReadyViewImpl(new TreeSet<String>(), viewId++));
    }

    // junit testing only - only scope to package access
    AliveAndReadyViewWindow() {
        jrcallback = new JoinedAndReadyCallBack(null, aliveAndReadyView);
        leaveCallback = new LeaveCallBack(null, aliveAndReadyView);
        currentInstanceName = null;
        
         // initialize with a null initial previous and current views
        aliveAndReadyView.add(new AliveAndReadyViewImpl(new TreeSet<String>(), viewId++));
        aliveAndReadyView.add(new AliveAndReadyViewImpl(new TreeSet<String>(), viewId++));
    }

    public void setStartClusterMaxDuration(long durationInMs) {
        MAX_CLUSTER_STARTTIME_DURATION_MS = durationInMs;
    }

    // junit testing only - only scope to package access
    void junitProcessNotification(Signal signal) {
        if (signal instanceof JoinedAndReadyNotificationSignal) {
            jrcallback.processNotification(signal);
        } else if (signal instanceof PlannedShutdownSignal || signal instanceof FailureNotificationSignal) {
            leaveCallback.processNotification(signal);
        }
    }


    public AliveAndReadyView getPreviousView() {
        AliveAndReadyView result = null;
        synchronized (aliveAndReadyView) {
            int size = aliveAndReadyView.size();
            assert(size > 2);
            if (size >= 2) {
                result = aliveAndReadyView.get(size - 2);
            } else if (size == 1) {
                result = aliveAndReadyView.get(0);
                if (LOG.isLoggable(TRACE_LEVEL)) {
                    LOG.log(TRACE_LEVEL, "getPreviousAliveAndReadyView() called and only a current view", result);
                }
            }
        }
        // return current view when previous join and ready had a short duration and looks like it was part of startup.
        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "getPreviousAliveAndReadyView: returning " + result);
        }

        return result;
    }

    public AliveAndReadyView getCurrentView() {
        AliveAndReadyView result = null;
        synchronized (aliveAndReadyView) {
            int length = aliveAndReadyView.size();
            if (length > 0) {
                result = aliveAndReadyView.get(length - 1);
            }
        }  // return current view when previous join and ready had a short duration and looks like it was part of startup.
        if (LOG.isLoggable(TRACE_LEVEL)) {
            LOG.log(TRACE_LEVEL, "getCurrentAliveAndReadyView: returning " + result);
        }
        return result;
    }

    private abstract class CommonCallBack implements CallBack {
        final protected List<AliveAndReadyView> aliveAndReadyView;
        final protected GroupHandle gh;

        public CommonCallBack(GroupHandle gh, List<AliveAndReadyView> aliveAndReadyViews) {
            this.aliveAndReadyView = aliveAndReadyViews;
            this.gh = gh;
        }

        public void add(Signal signal, SortedSet<String> members) {
            // complete the current view with signal indicating transition that makes this the previous view.
            AliveAndReadyViewImpl current = (AliveAndReadyViewImpl)getCurrentView();
            if (current != null) {
                current.setSignal(signal);
            }
            
            // create a new current view
            AliveAndReadyView arview = new AliveAndReadyViewImpl(members, viewId++);
            aliveAndReadyView.add(arview);
            if (aliveAndReadyView.size() > MAX_ALIVE_AND_READY_VIEWS) {
                aliveAndReadyView.remove(0);
            }
        }
    }

    private class LeaveCallBack extends CommonCallBack {

        public LeaveCallBack(GroupHandle gh, List<AliveAndReadyView> aliveAndReadyView) {
            super(gh, aliveAndReadyView);
        }

        public void processNotification(Signal signal) {
            if (signal instanceof PlannedShutdownSignal ||
                signal instanceof FailureNotificationSignal) {
                synchronized (aliveAndReadyView) {

                    // only consider CORE members.
                    AliveAndReadyView current = getCurrentView();
                    if (current != null && current.getMembers().contains(signal.getMemberToken())) {
                        SortedSet<String> currentMembers = new TreeSet<String>(current.getMembers());
                        boolean result = currentMembers.remove(signal.getMemberToken());
                        assert (result);
                        add(signal, currentMembers);
                    }
                }
            }
        }
    }

    private class JoinedAndReadyCallBack extends CommonCallBack {

        public JoinedAndReadyCallBack(GroupHandle gh, List<AliveAndReadyView> aliveAndReadyView) {
            super(gh, aliveAndReadyView);
        }

        public void processNotification(Signal signal) {
            if (signal instanceof JoinedAndReadyNotificationSignal) {
                final JoinedAndReadyNotificationSignal jrns = (JoinedAndReadyNotificationSignal) signal;
                final RejoinSubevent rejoin = jrns.getRejoinSubevent();
                SortedSet<String> dasReadyMembers = joinedAndReadySignalReadyList.remove(jrns.getMemberToken());
                synchronized (aliveAndReadyView) {
                    AliveAndReadyView current = getCurrentView();
                    SortedSet<String> currentMembers = new TreeSet<String>(current.getMembers());
                    for (String member : jrns.getCurrentCoreMembers()) {
                        if (dasReadyMembers != null && dasReadyMembers.contains(member)) {
                            if (currentMembers.add(member)) {
                                if (LOG.isLoggable(TRACE_LEVEL)) {
                                    LOG.log(TRACE_LEVEL, "das ready member: " + member + " added");
                                }
                            }
                        } else if (jrns.getMemberToken().equals(member)) {
                            currentMembers.add(member);
                        } else if (gh != null) {
                            // last check.  see if received ready heartbeat.
                            MemberStates state = gh.getMemberState(member, 10000, 0);
                            switch (state) {
                                case READY:
                                case ALIVEANDREADY:
                                    currentMembers.add(member);
                                    break;
                                default:
                            }
                        }
                    }
                    add(signal, currentMembers);
                } // end synchronized aliveAndReadyView
            }
        }
    }

    public void put(String joinedAndReadyMember, SortedSet<String> readyMembers) {
        if (LOG.isLoggable(TRACE_LEVEL)) {
            LOG.log(TRACE_LEVEL, "put joinedAndReadySignal member:" + joinedAndReadyMember + " ready members:" + readyMembers);
        }
        SortedSet<String> result = joinedAndReadySignalReadyList.put(joinedAndReadyMember, readyMembers);
        assert(result == null);
    }
}
