/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2018 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://oss.oracle.com/licenses/CDDL+GPL-1.1
 * or LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at LICENSE.txt.
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

package org.shoal.adapter.store.commands;

import org.shoal.ha.cache.api.ShoalCacheLoggerConstants;
import org.shoal.ha.cache.impl.command.Command;
import org.shoal.ha.cache.impl.command.ReplicationCommandOpcode;
import org.shoal.ha.cache.impl.util.CommandResponse;
import org.shoal.ha.cache.impl.util.ResponseMediator;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Mahesh Kannan
 */
public class RemoveExpiredResultCommand<K, V>
    extends Command<String, V> {

    protected static final Logger _logger = Logger.getLogger(ShoalCacheLoggerConstants.CACHE_REMOVE_COMMAND);

    private String target;

    private long tokenId;

    private int result = 0;

    public RemoveExpiredResultCommand(String target, long tokenId, int result) {
        super(ReplicationCommandOpcode.REMOVE_EXPIRED_RESULT);
        this.target = target;
        this.tokenId = tokenId;
        this.result = result;

        super.setKey("RemExpResp:" + tokenId);
    }

    public boolean beforeTransmit() {
        setTargetName(target);
        return target != null;
    }

    private void writeObject(ObjectOutputStream ros) throws IOException {
        ros.writeLong(tokenId);
        ros.writeInt(result);
    }

    private void readObject(ObjectInputStream ris)
        throws IOException, ClassNotFoundException {
        tokenId = ris.readLong();
        result = ris.readInt();
    }

    @Override
    public void execute(String initiator) {
        ResponseMediator respMed = getDataStoreContext().getResponseMediator();
        CommandResponse resp = respMed.getCommandResponse(tokenId);
        if (resp != null) {
            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, dsc.getInstanceName() + "For tokenId = " + tokenId + " received remove_expired_response value=" + result);
            }

            int pendingUpdates = 0;
            synchronized (resp) {
                Integer existingValue = (Integer) resp.getTransientResult();
                Integer newResult = new Integer(existingValue.intValue() + result);
                resp.setTransientResult(newResult);
                pendingUpdates = resp.decrementAndGetExpectedUpdateCount();
            }

            if (pendingUpdates == 0) {
                resp.setResult(resp.getTransientResult());
            }
        } else {
            _logger.log(Level.FINE, "RemoveExpiredResult: TOKEN already removed for tokenId = " + tokenId);
        }
    }

    @Override
    protected boolean isArtificialKey() {
        return true;
    }

    public String toString() {
        return getName() + "(result=" + result + ")";
    }
}