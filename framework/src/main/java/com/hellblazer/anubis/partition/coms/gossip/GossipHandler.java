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
package com.hellblazer.anubis.partition.coms.gossip;

import static java.lang.String.format;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.hellblazer.anubis.basiccomms.nio.AbstractCommunicationsHandler;
import com.hellblazer.anubis.basiccomms.nio.ServerChannelHandler;

/**
 * The communications handler imlementing the gossip wire protocol
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 * 
 */
public class GossipHandler extends AbstractCommunicationsHandler implements
        GossipMessages {
    private static final Logger log = Logger.getLogger(GossipHandler.class.getCanonicalName());

    private final Gossip        gossip;

    public GossipHandler(Gossip gossip, ServerChannelHandler handler,
                         SocketChannel channel) {
        super(handler, channel);
        this.gossip = gossip;
    }

    @Override
    public void gossip(List<Digest> digests) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + digests.size()
                                                * DIGEST_BYTE_SIZE);
        buffer.put(GOSSIP);
        buffer.putInt(digests.size());
        for (Digest digest : digests) {
            digest.writeTo(buffer);
        }
        send(buffer.array());
        selectForRead();
    }

    @Override
    public void reply(List<Digest> digests, List<HeartbeatState> states) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + 4 + digests.size()
                                                * DIGEST_BYTE_SIZE
                                                + states.size()
                                                * HEARTBEAT_STATE_BYTE_SIZE);
        buffer.put(REPLY);
        buffer.putInt(digests.size());
        buffer.putInt(states.size());
        for (Digest digest : digests) {
            digest.writeTo(buffer);
        }
        for (HeartbeatState state : states) {
            state.writeTo(buffer);
        }
        send(buffer.array());
        selectForRead();
    }

    @Override
    public void update(List<HeartbeatState> deltaState) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + deltaState.size()
                                                * HEARTBEAT_STATE_BYTE_SIZE);
        buffer.put(UPDATE);
        buffer.putInt(deltaState.size());
        for (HeartbeatState state : deltaState) {
            state.writeTo(buffer);
        }
        send(buffer.array());
    }

    @Override
    protected void closing() {
    }

    @Override
    protected void deliver(byte[] msg) {
        ByteBuffer buffer = ByteBuffer.wrap(msg);
        byte msgType = buffer.get();
        switch (msgType) {
            case GOSSIP: {
                handleGossip(buffer);
                break;
            }
            case REPLY: {
                handleReply(buffer);
                break;
            }
            case UPDATE: {
                handleUpdate(buffer);
                break;
            }
            default: {
                if (log.isLoggable(Level.FINEST)) {
                    log.finest(format("invalid message type: %s from: %s",
                                      msgType, this));
                }
            }
        }
    }

    protected void handleGossip(ByteBuffer msg) {
        int count = msg.getInt();
        List<Digest> digests = new ArrayList<Digest>(count);
        for (int i = 0; i < count; i++) {
            Digest digest;
            try {
                digest = new Digest(msg);
            } catch (Throwable e) {
                if (log.isLoggable(Level.WARNING)) {
                    log.log(Level.WARNING,
                            "Cannot deserialize digest. Ignoring the digest.",
                            e);
                }
                continue;
            }
            digests.add(digest);
        }
        if (log.isLoggable(Level.FINEST)) {
            log.finest(format("Gossip digests from %s are : %s", this, digests));
        }
        gossip.gossip(digests, this);
    }

    protected void handleReply(ByteBuffer msg) {
        int digestCount = msg.getInt();
        int stateCount = msg.getInt();
        List<Digest> digests = new ArrayList<Digest>(digestCount);
        List<HeartbeatState> remoteStates = new ArrayList<HeartbeatState>(
                                                                          stateCount);
        for (int i = 0; i < digestCount; i++) {
            Digest digest;
            try {
                digest = new Digest(msg);
            } catch (Throwable e) {
                if (log.isLoggable(Level.WARNING)) {
                    log.log(Level.WARNING,
                            "Cannot deserialize digest. Ignoring the digest.",
                            e);
                }
                continue;
            }
            digests.add(digest);
        }

        for (int i = 0; i < stateCount; i++) {
            HeartbeatState state;
            try {
                state = new HeartbeatState(msg);
            } catch (Throwable e) {
                if (log.isLoggable(Level.WARNING)) {
                    log.log(Level.WARNING,
                            "Cannot deserialize heartbeat state. Ignoring the state.",
                            e);
                }
                continue;
            }
            remoteStates.add(state);
        }
        if (log.isLoggable(Level.FINEST)) {
            log.finest(format("Received reply from %s", this));
        }
        gossip.reply(digests, remoteStates, this);
    }

    protected void handleUpdate(ByteBuffer msg) {
        int stateCount = msg.getInt();
        List<HeartbeatState> remoteStates = new ArrayList<HeartbeatState>(
                                                                          stateCount);

        for (int i = 0; i < stateCount; i++) {
            HeartbeatState state;
            try {
                state = new HeartbeatState(msg);
            } catch (Throwable e) {
                if (log.isLoggable(Level.WARNING)) {
                    log.log(Level.WARNING,
                            "Cannot deserialize heartbeat state. Ignoring the state.",
                            e);
                }
                continue;
            }
            remoteStates.add(state);
        }
        if (log.isLoggable(Level.FINEST)) {
            log.finest(format("Received an update from %s", this));
        }
        gossip.update(remoteStates);
    }

}
