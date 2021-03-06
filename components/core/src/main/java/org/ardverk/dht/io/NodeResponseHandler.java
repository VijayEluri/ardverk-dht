/*
 * Copyright 2009-2012 Roger Kapsi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ardverk.dht.io;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.inject.Provider;

import org.ardverk.dht.KUID;
import org.ardverk.dht.config.NodeConfig;
import org.ardverk.dht.entity.NodeEntity;
import org.ardverk.dht.message.MessageFactory;
import org.ardverk.dht.message.MessageType;
import org.ardverk.dht.message.NodeRequest;
import org.ardverk.dht.message.NodeResponse;
import org.ardverk.dht.message.ResponseMessage;
import org.ardverk.dht.routing.Contact;
import org.ardverk.dht.routing.RouteTable;


/**
 * The {@link NodeResponseHandler} manages a {@link MessageType#FIND_NODE} 
 * lookup process.
 */
public class NodeResponseHandler extends LookupResponseHandler<NodeEntity> {
  
  public NodeResponseHandler(Provider<MessageDispatcher> messageDispatcher,
      Contact[] contacts, RouteTable routeTable, KUID lookupId, NodeConfig config) {
    super(messageDispatcher, contacts, routeTable, lookupId, config);
  }
  
  @Override
  protected void lookup(Contact dst, KUID lookupId, 
      long timeout, TimeUnit unit) throws IOException {
    
    MessageFactory factory = getMessageFactory();
    NodeRequest message = factory.createNodeRequest(dst, lookupId);
    send(dst, message, timeout, unit);
  }

  @Override
  protected void complete(Outcome outcome) {
    Contact[] contacts = outcome.getContacts();
    
    if (contacts.length == 0) {
      setException(new NoSuchNodeException(outcome));        
    } else {
      setValue(new NodeEntity(outcome));
    }
  }
  
  @Override
  protected synchronized void processResponse0(RequestEntity entity,
      ResponseMessage response, long time, TimeUnit unit)
      throws IOException {
    
    Contact src = response.getContact();
    Contact[] contacts = ((NodeResponse)response).getContacts();
    processContacts(src, contacts, time, unit);
  }
}