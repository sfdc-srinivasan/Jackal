/** (C) Copyright 1998-2005 Hewlett-Packard Development Company, LP

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 2.1 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

For more information: www.smartfrog.org

*/
package org.smartfrog.services.anubis.partition.comms.nonblocking;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.smartfrog.services.anubis.partition.comms.Connection;
import org.smartfrog.services.anubis.partition.comms.IOConnection;
import org.smartfrog.services.anubis.partition.comms.MessageConnection;
import org.smartfrog.services.anubis.partition.comms.multicast.HeartbeatConnection;
import org.smartfrog.services.anubis.partition.protocols.partitionmanager.ConnectionSet;
import org.smartfrog.services.anubis.partition.util.Identity;
import org.smartfrog.services.anubis.partition.wire.WireMsg;
import org.smartfrog.services.anubis.partition.wire.WireSizes;
import org.smartfrog.services.anubis.partition.wire.msg.HeartbeatMsg;
import org.smartfrog.services.anubis.partition.wire.msg.TimedMsg;
import org.smartfrog.services.anubis.partition.wire.security.WireSecurity;
import org.smartfrog.services.anubis.partition.wire.security.WireSecurityException;

public class MessageNioHandler implements SendingListener, IOConnection, WireSizes {

    public final int SENDING_DONE = 0;
    public final int SENDING_PENDING = 1;
    public final int SENDING_REFUSED = 2;

    private SocketChannel sc = null;
    private boolean writingOK = false;
    private ByteBuffer[] fullObject = null;
    private ByteBuffer rxHeader = null;
    private ByteBuffer rxObject = null;
    private boolean rxHeaderAlreadyRead = false;
    private int objectSize = -1;
    private Vector deadKeys = null;
    private Vector writePendingKeys = null;
    private Selector selector = null;
    private SendingListener sendingListener = null;
    private ByteBuffer[] dataToWrite = null;
    private boolean writePending = false;

    // fields to replace MessageConectionImpl
    private boolean ignoring = false;
    private Identity me = null;
    private MessageConnection messageConnection = null;
    private NonBlockingConnectionInitiator mci = null;
    private ConnectionSet connectionSet = null;
    private boolean announceTerm = true;
    private boolean open = false;
    private boolean sendingDoneOK = false;
    private WireSecurity wireSecurity = null;
    private long                    sendCount         = INITIAL_MSG_ORDER;
    private long                    receiveCount      = INITIAL_MSG_ORDER;

    private RxQueue rxQueue = null;

    private final static boolean debug = false;
    private final static boolean dumbReset = true;

    private Logger log = Logger.getLogger(this.getClass().toString());


    /**
     * Each socketChannel has a MessageNioHandler whose main job is to read data from the channel and recover serialized objects
     * from it and to write serialized objects onto it.  A serialized object can be recovered by many calls from the selector thread
     * and serializing an object onto the socketChannel can occur in many chunks as well if the channel is busy.
     */
    public MessageNioHandler(Selector selector, SocketChannel sc, Vector deadKeys, Vector writePendingKeys, RxQueue rxQueue, WireSecurity sec){
       	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: Constructing a new MessageNioHandler");
    this.wireSecurity = sec;
	this.sc = sc;
	this.deadKeys = deadKeys;
	this.writePendingKeys = writePendingKeys;
	this.selector = selector;
	this.rxQueue = rxQueue;
	// data is sent in packets made of a header and data itself
	// the first ByteBuffer is the header
        fullObject = new ByteBuffer[2];
	fullObject[0] = ByteBuffer.allocateDirect(HEADER_SIZE);
	fullObject[0].putInt(MAGIC_NUMBER);
	// set up the Rx buffers
	rxHeader = ByteBuffer.allocateDirect(8);

    }

    public RxQueue getRxQueue(){
	return rxQueue;
    }

    public void setConnected(boolean conValue){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: Setting open to "+conValue);
	open = conValue;
    }


    /**
     * method called by selector thread when there is data to read on the socketChannel
     * @param key the selctionKey for the given socketChannel
     * @return the serialized object when it is fully read - null it the read is only partial
     */
    public ByteBuffer newDataToRead(SelectionKey key){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: newDataToread being called");
	SocketChannel sockChan = (SocketChannel)key.channel();
	int readAmount = -1;
	if (!rxHeaderAlreadyRead){
	    try{
		readAmount = sockChan.read(rxHeader);
		if (readAmount == -1){
		    cleanup(key);
		    return null;
		}
	    }
	    catch(IOException ioe){
                if( log.isLoggable(Level.WARNING) ) {
                    log.log(Level.WARNING, "Failed to read socket channel", ioe);
                }
		cleanup(key);
		return null;
	    }
            catch(NotYetConnectedException nycex) {
                if( log.isLoggable(Level.WARNING) ) {
                    log.log(Level.WARNING, "Attempt to read a socket channel before it is connected", nycex);
                }
                return null;
            }


	    // check if the buffer has been fully filled
	    if (rxHeader.remaining() == 0){
		if( debug && log.isLoggable(Level.FINER) )
		    log.finer("MNH: RxHeader buffer is full");
		// check magic number and object length
		rxHeaderAlreadyRead = true;
		rxHeader.flip();
		int readMagic = rxHeader.getInt();
		if( debug && log.isLoggable(Level.FINER) )
		    log.finer("MNH: Read magic number: "+readMagic);
		if (readMagic == MAGIC_NUMBER){
		    if( debug && log.isLoggable(Level.FINER) )
			log.finer("MNH: RxHeader magic-number fits");
		    // get the object size and create a new buffer for it
		    objectSize = rxHeader.getInt();
		    if( debug && log.isLoggable(Level.FINER) )
			log.finer("MNH: read objectSize: "+objectSize);

                    rxObject = ByteBuffer.wrap(new byte[objectSize]);
		}
		else{
		    log.finer("MNH: %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
		    log.finer("MNH: %  CANNOT FIND MAGIC_NUMBER:  "+readMagic+" instead");
		    log.finer("MNH: %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
		}
	    }
	    else
		return null;

	}

	if (objectSize != -1){
	    if( debug && log.isLoggable(Level.FINER) )
		log.finer("MNH: Trying to read the object itself now...");
	    try{
		readAmount = sockChan.read(rxObject);
		if( debug && log.isLoggable(Level.FINER) )
		    log.finer("MNH: This time round we have read:                      -->"+readAmount);
		if (readAmount == -1){
		    cleanup(key);
		    return null;
		}
	    }
	    catch(IOException ioe){
		cleanup(key);
                if( debug && log.isLoggable(Level.WARNING) )
                    log.log(Level.WARNING, "MNH: IOException reading the object", ioe);
		return null;
	    }

	    // check if object buffer has been fully filled - i.e. has the object arrived in full
	    if (rxObject.remaining() == 0){
		if( debug && log.isLoggable(Level.FINER) )
		    log.finer("MNH: RxObject is all here: "+readAmount);
		// read the object then since it is all here
		ByteBuffer returnedObject = rxObject;
		resetReadingVars();
		return returnedObject;
	    }
	    else
		return null;
	}
	else
	    return null;
    }



    public void readyForWriting(){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: readyForWriting being called");
	synchronized(this){
	    writingOK = true;
	}
    }

    public boolean isReadyForWriting(){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: IsReadyForWriting being called");
	return writingOK;
    }

    public boolean isWritePending(){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: isWritePending being called");
	return writePending;
    }


    /**
     * asynchronous write method: the object is mapped onto a ByteBuffer and returns after the first write operation
     * on the socketChannel.  If that first write was enough to send the whole packet then SENDING_DONE is returned
     * otherwise SENDING_PENDING is returned.  The registered SendingListener will be called when all the data has gone
     *
     * @param bytesToSend the object to be serialized
     * @param listener the object to call back when the whole object has been successfully sent on the socketChannel
     * @return sending error code from SENDING_DONE, SENDING_PENDING or SENDING_REFUSED
     */
    public int sendObject(byte[] bytesToSend, SendingListener listener){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: sendObject with Listener being called");
	boolean goAndWrite = false;
	int returnedInt = SENDING_REFUSED;
	synchronized(this){
	    if (writingOK){
		writingOK = false;
		goAndWrite = true;
		if( debug && log.isLoggable(Level.FINER) )
		    log.finer("MNH: all set to call writeData() - goAndWrite has been set to TRUE");
	    }
	    else{
		if( debug && log.isLoggable(Level.FINER) )
		    log.finer("MNH: writingOK is false -- object cannot be sent!!!");
	    }
	}
	if (goAndWrite){
	    dataToWrite = toByteBuffer(bytesToSend);
	    this.sendingListener = listener;
	    if( debug && log.isLoggable(Level.FINER) )
		log.finer("MNH: SendObject: Calling writeData...");
	    returnedInt = writeData();
	}
	return returnedInt;
    }

    /**
     * This method writes out whatever data in stored in ByteBufferArray dataToWrite.  It can be called
     * many times by the selector thread until the buffer content has been sent on the channel
     * @return sending error code from SENDING_DONE, SENDING_PENDING or SENDING_REFUSED
     */
    public int writeData(){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: writeData() being called");
	int returnedInt = SENDING_REFUSED;
	long dataLeftToSend = (long)(dataToWrite[0].remaining()+dataToWrite[1].remaining());
	long dataSentThisTime = -1;
	try{
	    dataSentThisTime = sc.write(dataToWrite);
	    if (dataSentThisTime == dataLeftToSend){
		if( debug && log.isLoggable(Level.FINER) )
		    log.finer("MNH: OK, all data has now gone: "+dataSentThisTime);
		// the whole of the buffer has gone - reset parameters and notify listener
		returnedInt = SENDING_DONE;
		resetWriteVars();
		if (writePending){
		    writePending = false;
		    // this call should return immediately since the selector thread might call it
		    sendingListener.sendingDone();
		}
	    }
	    else{
		if (dataSentThisTime >= 0){
		    // only some of the buffer has gone - we are in pending mode
		    returnedInt = SENDING_PENDING;
		    writePending = true;
		    // schedule a register for OP_WRITEABLE so that this method is called
		    // again when the channel is free-open again
		    // how to get the key ???!!! try using keyFor...
		    if( debug && log.isLoggable(Level.FINER) )
			log.finer("MNH: could only write: "+dataSentThisTime);
		    writePendingKeys.add(sc.keyFor(selector));
		    if( debug && log.isLoggable(Level.FINER) )
			log.finer("MNH: waking up the selector so that it registers the keys");
		    selector.wakeup();
		}
	    }
	}
	catch(IOException ioe){
            if( log.isLoggable(Level.WARNING) )
                log.log(Level.WARNING, "", ioe);
	    shutdown();
	}

	return returnedInt;
    }

    /*
    * close() is called when local end goes away
    */
    public void close(){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: call to close results in call to cleanup");
	SelectionKey selKey = sc.keyFor(selector);
	if (selKey != null){
	    cleanup(sc.keyFor(selector));
	    selector.wakeup();
	}
	else
	    writingOK = false;
    }



    /********************** methods from replacing MessageConnectionImpl + ConnectionComms ****************/
    // methods added to relace constructors
    public void init(Identity id, ConnectionSet cs, MessageConnection mc, NonBlockingConnectionInitiator mci){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: init with mc being called");
	// establish connection should have been done before...
	me = id;
        connectionSet = cs;
        messageConnection = mc;
	this.mci = mci;
    }

    public NonBlockingConnectionInitiator getMCI(){
	return mci;
    }


    public void init(Identity id, ConnectionSet cs){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: init being called");
	me = id;
        connectionSet = cs;
    }

    // methods added to mirror existing methods
    public void start(){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: start is being called");
    }


        
    // blocking write method: calls the above asynchronous one and this object needs to register itself as
    // a SendingListener   
    public synchronized void send(TimedMsg tm){ 
        
        byte[] bytesToSend = null;
        try {
            tm.setOrder(sendCount);
            sendCount++;
            bytesToSend = wireSecurity.toWireForm(tm);
        } catch (Exception ex) {
            if( log.isLoggable(Level.SEVERE) )
                log.log(Level.SEVERE, me + " failed to marshall timed message: " + tm + " - not sent", ex);
            return;
        }
        
        send(bytesToSend);
    }
   
    // only called directly from NonBlockingConnectionInitiator
    public synchronized void send(byte[] bytesToSend) {
        
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: sendObject withOUT listener is being called");
	sendingDoneOK = false;
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: Trying to send an object - blocking call: ");
	// this is a blocking call that will return only when all the object has been written
	int retVal = sendObject(bytesToSend, this);
	if (retVal == SENDING_DONE){
	    if( debug && log.isLoggable(Level.FINER) )
		log.finer("MNH: Sending object - blocking call WENT FINE!");
	    return;
	}
	else if (retVal == SENDING_REFUSED){
	    if( debug && log.isLoggable(Level.FINER) )
		log.finer("MNH: Object could not be sent SENDING_REFUSED!!! WHY NOT ?!");
		//		shutdown();
	}
	else if (retVal == SENDING_PENDING){
	    // channel is busy so we need to wait to be called back
	    synchronized(this){
		try{
		    if( debug && log.isLoggable(Level.FINER) )
			log.finer("MNH: SENDING_PENDING so go on wait for notification that writing is done");
		    wait(60*1000);
		}
		catch(InterruptedException ie){}
	    }
	    if (!sendingDoneOK){
		if( debug && log.isLoggable(Level.FINER) )
		    log.finer("MNH: Got woken up but boolean sendingDoneOK is still false !!!");
            shutdown();
	    }
	}
	else
	    log.finer("MNH: RETURN CODE FROM sendObject not recognised?! "+retVal);
    }

    public void sendingDone(){
	synchronized(this){
	    sendingDoneOK = true;
	    notifyAll();
	}
    }


    public void deliverObject(ByteBuffer fullRxBuffer) {
        if( debug && log.isLoggable(Level.FINER) )
            log.finer("MNH: deliverObject is being called");

        if (ignoring)
            return;

        WireMsg msg = null;
        try {

            msg = wireSecurity.fromWireForm(fullRxBuffer.array());

        } catch (WireSecurityException ex) {

            if( log.isLoggable(Level.SEVERE) )
                log.severe(me + "non blocking connection transport encountered security violation unmarshalling message - ignoring the message " ); // + this.getSender() );
            return;
            
        }  catch (Exception ex) {

            if( log.isLoggable(Level.SEVERE) )
                log.severe(me + "connection transport unable to unmarshall message " ); // + this.getSender() );
            shutdown();
            return;
        }
        
        if( !(msg instanceof TimedMsg) ) {

            if( log.isLoggable(Level.SEVERE) )
                log.severe(me + "connection transport received non timed message " ); // + this.getSender() );
            shutdown();
            return;
        }
        
        TimedMsg tm = (TimedMsg)msg;
        
        if( tm.getOrder() != receiveCount ) {
            if( log.isLoggable(Level.SEVERE) ) {
                log.severe(me + "connection transport has delivered a message out of order - shutting down");
            }
            shutdown();
            return;
        } 
        
        /**
         * handle the message. We do not increment the order for the 
         * initial heartbeat message opening a new connection.
         */
        if( messageConnection == null ) {
            initialMsg(tm);
        } else {
            receiveCount++;
            messageConnection.deliver(tm);
        }

    }

    public void closing(){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: closing is being called");
        //if(server != null)
	//  server.removeConnection(this);
        if( announceTerm && (messageConnection != null) )
            messageConnection.closing();
    }

    public void terminate(){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: terminate is being called");
	announceTerm = false;
	shutdown();
    }

    public void silent(){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: silent is being called");
	announceTerm = false;
    }

    public void setIgnoring(boolean ignoring){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: setIgnoring is being called");
	this.ignoring = ignoring;
    }

    public void shutdown(){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: shutdown is being called");
	open = false;
	closing();
	// close the socket channel
	this.close();
    // release any threads waiting for send completion
    synchronized(this) {
        notifyAll();
    }
    }


    public boolean connected(){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: connected is being called");
	return open;
    }

    public String getThreadStatusString(){
	return "MNH: MessageNioHandler does not run as a separate thread...";
    }


    // private methods
    // lifted from MessageConnectionImpl: I do not intend to modify it...
    
    
    private void initialMsg(TimedMsg tm) {
            
        if( debug && log.isLoggable(Level.FINER) )
            log.finer("MNH: initialMsg is being called");

        Object obj = tm;
        TimedMsg bytes = tm;


        /**
         * must be a heartbeat message
         */
        if( !(obj instanceof HeartbeatMsg ) ) {
            if( debug && log.isLoggable(Level.FINER) )
                log.severe(me + " did not receive a heartbeat message first - shutdown");
            shutdown();
            return;
        }

        HeartbeatMsg hbmsg = (HeartbeatMsg)obj;

        /**
         * There must be a valid connection (heartbeat connection)
         */
        if( !connectionSet.getView().contains(hbmsg.getSender()) ) {
            if( debug && log.isLoggable(Level.FINER) )
                log.severe(me + " did not have incoming connection in the connection set");
            shutdown();
            return;
        }

        Connection con = connectionSet.getConnection(hbmsg.getSender());

        /**
         * If it is a message connection then attempt to assign this
         * impl to that connection. If successful then record the message
         * connection so all further messages go directly to it. If not
         * successful then shutdown the this implementation object and
         * abort.
         */
        if( con instanceof MessageConnection ) {
            if( ((MessageConnection)con).assignImpl(this) ) {
                messageConnection = (MessageConnection)con;
		// commented out by ed@hplb since this is not a thread anymore
		//                setName("Anubis: node " + con.getSender().id + " Connection Comms");
                messageConnection.deliver(bytes);
            } else {
                if( debug && log.isLoggable(Level.FINER) )
                    log.severe(me + " failed to assign incoming connection");
                shutdown();
            }
            return;
        }

        /**
         * By now we should be left with a heartbeat connection - sanity check
         */
        if( !(con instanceof HeartbeatConnection) ) {
            if( debug && log.isLoggable(Level.FINER) )
                log.severe(me + " ?!? incoming connection is in connection set, but not heartbeat or message type");
            shutdown();
            return;
        }
        HeartbeatConnection hbcon = (HeartbeatConnection)con;

        /**
         * If the connection is a heartbeat connection then the other end must
         * be setting up the connection without this end having requested it.
         * That means the other end must want it, so check the msgLink field for
         * this end is set - this is a sanity check.
         *
         * *********************************************************************
         *
         * The case can happen, so the above comment is incorrect.
         * If the user does a connect and then
         * disconnect without sending a message, then the other end could
         * initiate a connection neither end needs in response to the initial
         * connect. Do not count this as an error, but do log its occurance.
         */
        if( !hbmsg.getMsgLinks().contains(me.id) ) {
            if( debug && log.isLoggable(Level.FINER) )
                log.severe(me + " incoming connection from " + con.getSender().toString() + " when neither end wants the connection");
            // next two lines removed to allow this case
            // shutdown();
            // return;
        }

        /**
         * Now we are left with a valid heartbeat connection and the other
         * end is initiating a message connection, so create this end.
         *
         * Note that the connection set only finds out about the newly created
         * message connection when it is informed by the call to
         * connectionSet.useNewMessageConnection(), so it can not terminate the
         * connection before the call to messageConnection.assignImpl(). Also, we
         * created the message connection, so we know it does not yet have an impl.
         * Hence we can assume it will succeed in assigning the impl.
         */
        messageConnection = new MessageConnection(me, connectionSet, hbcon.getProtocol(), hbcon.getCandidate());
        messageConnection.assignImpl(this);
        messageConnection.deliver(bytes);

        /**
         * if the call to connectionSet.useNewMessageConnection() then a connection
         * has been created since we checked for it above with connectionSet.getConnection().
         * The other end will not make two connection attempts at the same time, but if this
         * thread is delayed during the last 20 lines of code for long enough for the following to
         * happen:
         * 1. other end time out connection +
         * 2. quiesence period +
         * 3. this end rediscover other end in multicast heartbeats +
         * 4. other end initiates new connection attempt +
         * 5. new connection attempt gets accepted (new thread created for it) +
         * 6. read first heartbeat and get through this code in the new thread.
         * Then it could beat this thread to it. If all this happens (and based on
         * the premise "if it can happen it will happen") then this thread should rightly
         * comit suicide in disgust!!!!
         */
        if( !connectionSet.useNewMessageConnection(messageConnection) ) {
            if( debug && log.isLoggable(Level.FINER) )
                log.severe(me + "Concurrent creation of message connections from " + messageConnection.getSender());
            shutdown();
            return;
        }
	// commented out by ed@hplb since this is nto a thread anymore
	//        setName("Anubis: node " + messageConnection.getSender().id + " Connection Comms");
    }


    // cleanup is called if remote end goes away
    private void cleanup(SelectionKey key){
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: cleanup is being called");
	writingOK = false;
	deadKeys.add(key);
	if( debug && log.isLoggable(Level.FINER) )
	    log.finer("MNH: Cleanup is called - socket has gone away");
    }


    private void resetWriteVars(){
        // reset to possition after magic number
	fullObject[0].position(magicSz);
	fullObject[1] = null;
	dataToWrite = null;
	this.readyForWriting();
    }

    private void resetReadingVars(){
	rxHeaderAlreadyRead = false;
	objectSize = -1;
	rxHeader.clear();
    }

    private ByteBuffer[] toByteBuffer(byte[] bytesToSend){
        // magic number has already been entered.
        fullObject[0].putInt(bytesToSend.length);
        fullObject[0].flip();
	fullObject[1] = ByteBuffer.wrap(bytesToSend);
	return fullObject;
    }


}
