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
package org.smartfrog.services.anubis.partition.test.mainconsole;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.smartfrog.services.anubis.basiccomms.connectiontransport.ConnectionAddress;
import org.smartfrog.services.anubis.basiccomms.connectiontransport.ConnectionComms;
import org.smartfrog.services.anubis.partition.util.Identity;
import org.smartfrog.services.anubis.partition.wire.Wire;
import org.smartfrog.services.anubis.partition.wire.msg.untimed.SerializedMsg;

public class TestConnection extends ConnectionComms {

    private Controller controller;
    private Logger log = Logger.getLogger(this.getClass().toString());
    private NodeData nodeData;

    public TestConnection(ConnectionAddress address, NodeData nodeData,
                          Identity id, Controller controller) {
        super(
              "Anubis: Partition Manager Test Console - connection (remote node "
                      + id.id + ")", address);
        this.nodeData = nodeData;
        this.controller = controller;
    }

    @Override
    public void closing() {
        controller.disconnectNode(nodeData);
    }

    /**
     * Connection comms interface
     * 
     * @param bytes
     */
    @Override
    public void deliver(byte[] bytes) {

        SerializedMsg msg = null;
        try {
            msg = (SerializedMsg) Wire.fromWire(bytes);
            Object obj = msg.getObject();

            controller.deliverObject(obj, nodeData);
        } catch (Exception ex) {
            if (log.isLoggable(Level.WARNING)) {
                log.log(Level.WARNING, "", ex);
            }
        }
    }

    @Override
    public void send(byte[] bytes) {
        if (log.isLoggable(Level.SEVERE)) {
            Exception e = new Exception();
            e.fillInStackTrace();
            log.log(Level.SEVERE,
                    "Should not call send(byte[] bytes) in TestConnection", e);
        }
    }

    public void sendObject(Object obj) {
        SerializedMsg msg = new SerializedMsg(obj);
        try {
            super.send(msg.toWire());
        } catch (Exception ex) {
            if (log.isLoggable(Level.WARNING)) {
                log.log(Level.WARNING, "", ex);
            }
        }
    }

}
