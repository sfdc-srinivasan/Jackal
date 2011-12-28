/** 
 * (C) Copyright 2011 Hal Hildebrand, All Rights Reserved
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.hellblazer.jackal.partition.test.node;

import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.smartfrog.services.anubis.partition.PartitionNotification;
import org.smartfrog.services.anubis.partition.test.msg.GetStatsMsg;
import org.smartfrog.services.anubis.partition.test.msg.GetThreadsMsg;
import org.smartfrog.services.anubis.partition.test.msg.PartitionMsg;
import org.smartfrog.services.anubis.partition.test.msg.SetIgnoringMsg;
import org.smartfrog.services.anubis.partition.test.msg.SetTimingMsg;
import org.smartfrog.services.anubis.partition.views.View;
import org.smartfrog.services.anubis.partition.wire.Wire;
import org.smartfrog.services.anubis.partition.wire.WireMsg;
import org.smartfrog.services.anubis.partition.wire.msg.untimed.SerializedMsg;
import org.smartfrog.services.anubis.partition.wire.security.WireSecurity;

import com.hellblazer.partition.comms.AbstractMessageHandler;
import com.hellblazer.pinkie.SocketChannelHandler;

/**
 * 
 * @author hhildebrand
 * 
 */
public class ControllerConnection extends AbstractMessageHandler implements
                PartitionNotification {

    private static final Logger log = Logger.getLogger(ControllerConnection.class.getCanonicalName());

    private final Controller    controller;

    public ControllerConnection(Controller controller, WireSecurity wireSecurity) {
        super(wireSecurity);
        this.controller = controller;

    }

    @Override
    public void accept(SocketChannelHandler handler) {
        this.handler = handler;
        this.handler.selectForRead();
        controller.updateStatus(this);
        controller.updateTiming(this);
    }

    @Override
    public void closing() {
        controller.closing(this);
    }

    @Override
    public void connect(SocketChannelHandler handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void objectNotification(Object o, int i, long l) {
    }

    /**
     * partition notification interface
     * 
     * @param view
     * @param leader
     */
    @Override
    public void partitionNotification(View view, int leader) {
        try {
            sendObject(new PartitionMsg(view, leader));
        } catch (Exception ex) {
            if (log.isLoggable(Level.WARNING)) {
                log.log(Level.WARNING, "", ex);
            }
        }
    }

    public synchronized void send(WireMsg tm) {
        if (handler == null) {
            return;
        }
        byte[] bytesToSend = null;
        try {
            bytesToSend = wireSecurity.toWireForm(tm);
        } catch (Exception ex) {
            log.log(Level.SEVERE,
                    String.format("failed to marshall timed message: %s - not sent",
                                  tm), ex);
            return;
        }

        sendObject(bytesToSend);
    }

    public void sendObject(Object obj) {
        try {
            send(new SerializedMsg(obj));
        } catch (Exception ex) {
            if (log.isLoggable(Level.WARNING)) {
                log.log(Level.WARNING, "", ex);
            }
        }
    }

    /* (non-Javadoc)
     * @see com.hellblazer.partition.comms.AbstractMessageHandler#deliverObject(java.nio.ByteBuffer)
     */
    @Override
    protected void deliverObject(ByteBuffer readBuffer) {
        SerializedMsg msg = null;
        try {
            msg = (SerializedMsg) Wire.fromWire(readBuffer.array());
        } catch (Exception ex) {
            if (log.isLoggable(Level.WARNING)) {
                log.log(Level.WARNING, "Cannot deserialize message bytes", ex);
            }
            return;
        }

        Object obj = msg.getObject();

        if (log.isLoggable(Level.FINER)) {
            log.finer(String.format("Delivering: %s", obj));
        }

        if (obj instanceof SetTimingMsg) {
            SetTimingMsg m = (SetTimingMsg) obj;
            controller.setTiming(m.interval, m.timeout);
        } else if (obj instanceof GetStatsMsg) {
            controller.updateStats(this);
        } else if (obj instanceof SetIgnoringMsg) {
            controller.setIgnoring(((SetIgnoringMsg) obj).ignoring);
        } else if (obj instanceof GetThreadsMsg) {
            controller.updateThreads(this);
        } else {
            log.log(Level.SEVERE,
                    "Unrecognised object received in test connection at node"
                                    + obj, new Exception());
        }
    }

    /* (non-Javadoc)
     * @see com.hellblazer.partition.comms.AbstractMessageHandler#getLog()
     */
    @Override
    protected Logger getLog() {
        return log;
    }
}