package com.ardverk.dht.io;

import java.io.IOException;

import org.ardverk.lang.Arguments;

import com.ardverk.dht.KUID;
import com.ardverk.dht.message.MessageFactory;
import com.ardverk.dht.message.RequestMessage;
import com.ardverk.dht.message.ResponseMessage;
import com.ardverk.dht.message.ValueRequest;
import com.ardverk.dht.routing.Contact;
import com.ardverk.dht.routing.RouteTable;
import com.ardverk.dht.storage.Database;
import com.ardverk.dht.storage.Value;

public class ValueRequestHandler extends AbstractRequestHandler {

    private final RouteTable routeTable;
    
    private final Database database;
    
    public ValueRequestHandler(
            MessageDispatcher messageDispatcher, 
            RouteTable routeTable, 
            Database database) {
        super(messageDispatcher);
        
        this.routeTable = Arguments.notNull(routeTable, "routeTable");
        this.database = Arguments.notNull(database, "database");
    }

    @Override
    public void handleRequest(RequestMessage message) throws IOException {
        ValueRequest request = (ValueRequest)message;
        
        KUID key = request.getKey();
        Value value = database.get(key);
        
        MessageFactory factory = messageDispatcher.getMessageFactory();
        ResponseMessage response = null;
        
        if (value != null) {
            response = factory.createValueResponse(request, value);
        } else {
            Contact[] contacts = routeTable.select(key);
            response = factory.createNodeResponse(request, contacts);
        }
        
        send(request, response);
    }
}
