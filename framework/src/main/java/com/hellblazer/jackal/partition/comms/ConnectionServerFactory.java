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
package com.hellblazer.jackal.partition.comms;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;

import org.smartfrog.services.anubis.partition.comms.IOConnectionServer;
import org.smartfrog.services.anubis.partition.comms.IOConnectionServerFactory;
import org.smartfrog.services.anubis.partition.protocols.partitionmanager.ConnectionSet;
import org.smartfrog.services.anubis.partition.util.Identity;
import org.smartfrog.services.anubis.partition.wire.security.WireSecurity;

import com.hellblazer.pinkie.SocketOptions;

/**
 * 
 * @author hhildebrand
 * 
 */
public class ConnectionServerFactory implements IOConnectionServerFactory {
    private final ExecutorService executor;
    private final SocketOptions   socketOptions;
    private final WireSecurity    wireSecurity;

    public ConnectionServerFactory(WireSecurity wireSecurity,
                                   SocketOptions socketOptions,
                                   ExecutorService executor) {
        this.socketOptions = socketOptions;
        this.wireSecurity = wireSecurity;
        this.executor = executor;
    }

    @Override
    public IOConnectionServer create(InetSocketAddress endpointAddress,
                                     Identity id, ConnectionSet connectionSet)
                                                                              throws IOException {
        return new ConnectionServer(executor, endpointAddress, socketOptions,
                                    id, connectionSet, wireSecurity);
    }
}
