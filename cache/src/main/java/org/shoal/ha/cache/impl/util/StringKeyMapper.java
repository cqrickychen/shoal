/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2010 Sun Microsystems, Inc. All rights reserved.
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

package org.shoal.ha.cache.impl.util;

import org.shoal.ha.group.GroupMemberEventListener;
import org.shoal.ha.mapper.KeyMapper;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author Mahesh Kannan
 */
public class StringKeyMapper<K>
        implements KeyMapper<K>, GroupMemberEventListener {

    private String myName;

    private String groupName;

    private boolean includeMe;

    private ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private ReentrantReadWriteLock.ReadLock rLock;

    private ReentrantReadWriteLock.WriteLock wLock;

    private volatile TreeSet<String> currentMemberSet = new TreeSet<String>();

    private volatile TreeSet<String> currentMemberSetMinusMe = new TreeSet<String>();

    private volatile String[] currentView = new String[0];

    private volatile String[] previousView = new String[0];

    private volatile String[] currentViewMinusMe = new String[0];

    private volatile String currentViewCausedBy = "";

    private static final String UNMAPPED = "UNMAPPED__" + StringKeyMapper.class.getName();

    private boolean debug;

    public StringKeyMapper(String myName, String groupName) {
        this.myName = myName;
        this.groupName = groupName;
        this.currentViewCausedBy = myName;
        rLock = rwLock.readLock();
        wLock = rwLock.writeLock();

        registerInstance(myName);
    }

    @Override
    public String getMappedInstance(String groupName, K key1) {
        rLock.lock();
        try {
            if (currentView.length == 0) {
                return UNMAPPED;
            }
            int hc = Math.abs(getHashCode(key1));
            String mappedInstance = currentView[hc % (currentView.length)];

            //If the mapped instance is the current instance, then
            // both active and replica lives in the same instance!!

            if (mappedInstance.equals(myName)) {
                if (currentViewMinusMe.length == 0) {
                    return UNMAPPED;
                }
                mappedInstance = currentViewMinusMe[hc % currentViewMinusMe.length];
                //System.out.println("Mapping(*) Key: " + key1 + " => " + hc + ";   " + mappedInstance);
            } else {
                //System.out.println("Mapping Key: " + key1 + " => " + hc + ";   " + mappedInstance);
            }

            return mappedInstance;
        } finally {
            rLock.unlock();
        }
    }

    public String findReplicaInstance(String groupName, K key1) {
        rLock.lock();
        try {
            if (currentViewCausedBy.equals(myName)) {
                return getMappedInstance(groupName, key1);
            }
            if (previousView.length == 0) {
                return UNMAPPED;
            }
            int hc = Math.abs(getHashCode(key1));
            String mappedInstance = previousView[hc % (previousView.length)];

            if (debug) {
                printMemberStates();
            }
            if (mappedInstance.equals(currentViewCausedBy)) {
                mappedInstance = currentView[hc % currentView.length];
                if (debug) {
                    System.out.println("REPLICA(*) Key: " + key1 + " => " + hc + ";   " + mappedInstance);
                }
            } else {
                if (debug) {
                    System.out.println("REPLICA Key: " + key1 + " => " + hc + ";   " + mappedInstance);
                }
            }

            return mappedInstance;
        } finally {
            rLock.unlock();
        }
    }

//    @Override
//    public String[] getMappedInstances(String groupName, K key, int count) {
//        return new String[]{getMappedInstance(groupName, key)};
//    }

    @Override
    public void memberReady(String instanceName, String groupName) {
        if (this.groupName.equals(groupName)) {
            registerInstance(instanceName);
        }
    }

    @Override
    public void memberLeft(String instanceName, String groupName, boolean isShutdown) {
        if (this.groupName.equals(groupName)) {
            removeInstance(instanceName);
        }
    }

    private int getHashCode(K val) {
        int hc = val.hashCode();
        /*
        try {
            String hcStr = "_" + val.hashCode() + "_";
            MessageDigest dig = MessageDigest.getInstance("MD5");
            dig.update(hcStr.getBytes());
            dig.update(val.getBytes());
            dig.update(hcStr.getBytes());
            BigInteger bi = new BigInteger(dig.digest());
            hc = bi.intValue();
            return hc;
        } catch (NoSuchAlgorithmException nsaEx) {
            hc = val.hashCode();
        }
        */
        return hc;
    }

    public void registerInstance(String inst) {
        wLock.lock();
        try {
            if (!currentMemberSet.contains(inst)) {
                previousView = currentView;
                currentMemberSet.add(inst);
                currentView = currentMemberSet.toArray(new String[0]);
                currentViewCausedBy = inst;

                if ((!currentMemberSetMinusMe.contains(inst)) && (!inst.equals(myName))) {
                    currentMemberSetMinusMe.add(inst);
                    currentViewMinusMe = currentMemberSetMinusMe.toArray(new String[0]);
                }
            }
            //printMemberStates();
        } finally {
            wLock.unlock();
        }
    }

    public synchronized void removeInstance(String inst) {
        wLock.lock();
        try {
            if (currentMemberSet.contains(inst)) {
                previousView = currentView;
                currentViewCausedBy = inst;
                currentMemberSet.remove(inst);
                currentView = currentMemberSet.toArray(new String[0]);

                if ((currentMemberSetMinusMe.contains(inst)) && (!inst.equals(myName))) {
                    currentMemberSetMinusMe.remove(inst);
                    currentViewMinusMe = currentMemberSetMinusMe.toArray(new String[0]);
                }
            }
            //printMemberStates();
        } finally {
            wLock.unlock();
        }
    }

    public void setDebug(boolean val) {
        debug = val;
    }

    public void printMemberStates() {
        System.out.print("StringKeyMapper:: Members[");
        for (String st : currentView) {
            System.out.print("<" + st + "> ");
        }
        System.out.println("]");        System.out.print("StringKeyMapper:: PreviousMembers[");
        for (String st : previousView) {
            System.out.print("<" + st + "> ");
        }
        System.out.println("];  currentViewCausedBy: " + currentViewCausedBy);
    }

    private static class ReplicaState
            implements Comparable<ReplicaState> {
        String name;
        boolean active;

        ReplicaState(String name, boolean b) {
            this.name = name;
            active = b;
        }

        @Override
        public int compareTo(ReplicaState s) {
            return name.compareTo(s.name);
        }

        public int hashCode() {
            return name.hashCode();
        }

        public boolean equals(Object other) {
            ReplicaState st = (ReplicaState) other;
            return st.name.equals(name);
        }

        public String toString() {
            return "<" + name + ":" + active + ">";
        }
    }

}