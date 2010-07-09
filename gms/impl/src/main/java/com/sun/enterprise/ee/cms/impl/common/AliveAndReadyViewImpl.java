
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

import com.sun.enterprise.ee.cms.core.AliveAndReadyView;
import com.sun.enterprise.ee.cms.core.Signal;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.SortedSet;

public class AliveAndReadyViewImpl implements AliveAndReadyView {

    private Signal signal;
    private long signalTime;

    final private SortedSet<String> members;
    final private long viewId;
    final private long creationTime;

    public AliveAndReadyViewImpl(SortedSet<String> members, long viewId) {
        this.members = Collections.unmodifiableSortedSet(members);
        this.viewId = viewId;
        this.creationTime = System.currentTimeMillis();
        this.signal = null;
        this.signalTime = -1L;
    }


    // NOTE: specifically did not want to expose method setSignal(Signal) in AliveAndReadyView interface for end users.
    //       This method exists for implementation to use only and thus only occurs here to enforce that desire.
    /**
     * Terminates this view as being the current view.
     * @param signal
     * @throws NullPointerException if closeViewSignal is null.
     */
    public void setSignal(final Signal signal) {
        if (signal == null) {
            throw new NullPointerException("setSignal: parameter signal is not allowed to be set to null");
        }
        this.signal = signal;
        this.signalTime = System.currentTimeMillis();
    }

    /**
     *
     * @return signal that caused transition to this view.
     */
    public Signal getSignal() {
        return signal;
    }


    /**
     *
     * @return an unmodifiable list of members who were alive and ready.
     */
    public SortedSet<String> getMembers() {
        return members;
    }

    /**
     *
     * @return time that this signal notification first occurred. 
     */
    public long getSignalTime() {
        return signalTime;
    }

    public long getViewId() {
        return viewId;
    }

    public long getViewCreationTime() {
        return this.creationTime;
    }

    public long getViewDuration() {
        long duration;
        if (signal != null) {
            duration = signalTime - creationTime;
        } else {
            duration = System.currentTimeMillis() - creationTime;
        }
        return duration;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("AliveAndReadyView  ViewId:").append(viewId);
        if (signal == null) {
            sb.append(" View created at ").append(MessageFormat.format("{0,date} {0,time,full}", creationTime));
        } else {
            sb.append(" Signal:").append(signal.getClass().getSimpleName());
            sb.append(" Duration(ms):").append(getViewDuration());
            sb.append(" View terminated at ").append(MessageFormat.format("{0,date} {0,time,full}", signalTime));
        }
        if (members != null) {
            int size = members.size();
            if (size > 0) {
                sb.append(" Members[").append(members.size()).append("]:[");
                for (String member : members) {
                    sb.append(member).append(",");
                }
                sb.setCharAt(sb.length() -1, ']');
            }
        }

        return sb.toString();
    }
}