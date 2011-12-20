/** 
 * (C) Copyright 2010 Hal Hildebrand, All Rights Reserved
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
package com.hellblazer.partition.comms;

import static java.lang.String.format;
import static org.smartfrog.services.anubis.partition.wire.WireSizes.MAGIC_NUMBER;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.smartfrog.services.anubis.partition.wire.security.WireSecurity;

import com.hellblazer.jackal.util.HexDump;
import com.hellblazer.partition.comms.MessageHandler.State;
import com.hellblazer.pinkie.CommunicationsHandler;
import com.hellblazer.pinkie.SocketChannelHandler;

/**
 * 
 * @author hhildebrand
 * 
 */
public abstract class AbstractMessageHandler implements CommunicationsHandler {

    protected static String toHex(byte[] data) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length * 4);
        PrintStream stream = new PrintStream(baos);
        HexDump.hexdump(stream, data, 0, data.length);
        stream.close();
        return baos.toString();
    }

    private volatile ByteBuffer               currentWrite;
    private ByteBuffer                        readBuffer;
    private final ByteBuffer                  rxHeader   = ByteBuffer.allocate(8);
    private final ReentrantLock               writeLock  = new ReentrantLock();
    private final ByteBuffer                  wxHeader   = ByteBuffer.allocate(8);
    protected volatile SocketChannelHandler   handler;
    protected volatile State                  readState  = State.HEADER;
    protected final WireSecurity              wireSecurity;
    protected final BlockingDeque<ByteBuffer> writes     = new LinkedBlockingDeque<ByteBuffer>();
    protected volatile State                  writeState = State.INITIAL;

    public AbstractMessageHandler(WireSecurity wireSecurity) {
        this.wireSecurity = wireSecurity;
    }

    @Override
    public void readReady() {
        if (getLog().isLoggable(Level.FINEST)) {
            getLog().finest(format("Socket read ready [%s]", this));
        }
        switch (readState) {
            case CLOSED:
                return;
            case HEADER: {
                if (!read(rxHeader)) {
                    return;
                }
                if (rxHeader.hasRemaining()) {
                    break;
                }
                rxHeader.clear();
                int readMagic = rxHeader.getInt();
                if (getLog().isLoggable(Level.FINEST)) {
                    getLog().finest("Read magic number: " + readMagic);
                }
                if (readMagic == MAGIC_NUMBER) {
                    if (getLog().isLoggable(Level.FINEST)) {
                        getLog().finest("RxHeader magic-number fits");
                    }
                    // get the object size and create a new buffer for it
                    int objectSize = rxHeader.getInt();
                    if (getLog().isLoggable(Level.FINEST)) {
                        getLog().finest("read objectSize: " + objectSize);
                    }
                    readBuffer = ByteBuffer.wrap(new byte[objectSize]);
                    readState = State.BODY;
                } else {
                    getLog().severe("%  CANNOT FIND MAGIC_NUMBER:  "
                                                    + readMagic + " instead");
                    readState = State.ERROR;
                    shutdown();
                    return;
                }
                // Fall through to BODY state intended.
            }
            case BODY: {
                if (!read(readBuffer)) {
                    return;
                }
                if (!readBuffer.hasRemaining()) {
                    rxHeader.clear();
                    deliverObject(readBuffer);
                    readBuffer = null;
                    readState = State.HEADER;
                }
                break;
            }
            default: {
                throw new IllegalStateException("Illegal read state "
                                                + readState);
            }
        }

        handler.selectForRead();
    }

    public void shutdown() {
        handler.close();
    }

    @Override
    public void writeReady() {
        ReentrantLock myLock = writeLock;
        if (!myLock.tryLock()) {
            return;
        }
        try {
            if (getLog().isLoggable(Level.FINEST)) {
                getLog().finest(format("Socket write ready [%s]", this));
            }
            switch (writeState) {
                case CLOSED:
                    return;
                case INITIAL: {
                    currentWrite = writes.pollFirst();
                    if (currentWrite == null) {
                        return;
                    }
                    wxHeader.clear();
                    wxHeader.putInt(0, MAGIC_NUMBER);
                    wxHeader.putInt(4, currentWrite.remaining());
                    writeState = State.HEADER;
                }
                case HEADER: {
                    if (!write(wxHeader)) {
                        return;
                    }
                    if (wxHeader.hasRemaining()) {
                        break;
                    }
                    writeState = State.BODY;
                    // fallthrough to body intentional
                }
                case BODY: {
                    if (!write(currentWrite)) {
                        return;
                    }
                    if (!currentWrite.hasRemaining()) {
                        writeState = State.INITIAL;
                        if (writes.isEmpty()) {
                            return;
                        }
                    }
                    break;
                }
                case ERROR: {
                    return;
                }
                default: {
                    throw new IllegalStateException("Illegal write state: "
                                                    + writeState);
                }
            }
            handler.selectForWrite();
        } finally {
            myLock.unlock();
        }
    }

    private boolean isClose(IOException ioe) {
        return "Broken pipe".equals(ioe.getMessage())
               || "Connection reset by peer".equals(ioe.getMessage());
    }

    private boolean read(ByteBuffer buffer) {
        try {
            if (handler.getChannel().read(buffer) < 0) {
                writeState = readState = State.CLOSED;
                shutdown();
                return false;
            }
        } catch (IOException ioe) {
            if (getLog().isLoggable(Level.FINE)) {
                getLog().log(Level.FINE,
                             format("Failed to read socket channel [%s]", this),
                             ioe);
            }
            readState = State.ERROR;
            shutdown();
            return false;
        } catch (NotYetConnectedException nycex) {
            if (getLog().isLoggable(Level.WARNING)) {
                getLog().log(Level.WARNING,
                             "Attempt to read a socket channel before it is connected",
                             nycex);
            }
            readState = State.ERROR;
            return false;
        }
        return true;
    }

    private boolean write(ByteBuffer buffer) {
        try {
            if (handler.getChannel().write(buffer) < 0) {
                close();
                return false;
            }
        } catch (ClosedChannelException e) {
            if (getLog().isLoggable(Level.FINER)) {
                getLog().log(Level.FINER,
                             format("shutting down handler due to other side closing [%s]",
                                    this), e);
            }
            error();
            return false;
        } catch (IOException ioe) {
            if (getLog().isLoggable(Level.WARNING) && !isClose(ioe)) {
                getLog().log(Level.WARNING, "shutting down handler", ioe);
            }
            error();
            return false;
        }
        return true;
    }

    protected void close() {
        writeState = readState = State.CLOSED;
        handler.close();
    }

    abstract protected void deliverObject(ByteBuffer readBuffer);

    protected void error() {
        writeState = State.ERROR;
        shutdown();
    }

    abstract protected Logger getLog();

    protected void sendObject(byte[] bytes) {
        if (getLog().isLoggable(Level.FINER)) {
            getLog().finer(format("sendObject being called [%s]", this));
        }
        writes.add(ByteBuffer.wrap(bytes));
        handler.selectForWrite();
    }

}