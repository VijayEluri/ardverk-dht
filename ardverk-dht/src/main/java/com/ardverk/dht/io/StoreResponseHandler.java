package com.ardverk.dht.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.ardverk.concurrent.AsyncFuture;

import com.ardverk.dht.KUID;
import com.ardverk.dht.entity.NodeEntity;
import com.ardverk.dht.entity.StoreEntity;
import com.ardverk.dht.message.MessageFactory;
import com.ardverk.dht.message.RequestMessage;
import com.ardverk.dht.message.ResponseMessage;
import com.ardverk.dht.message.StoreRequest;
import com.ardverk.dht.message.StoreResponse;
import com.ardverk.dht.routing.Contact;

public class StoreResponseHandler extends ResponseHandler<StoreEntity> {

    private static final int K = 20;
    
    /*private static final NodeEntity QUERY = new NodeEntity() {
        @Override
        public Contact[] getContacts() {
            return null;
        }
        
        @Override
        public int getHop() {
            return 0;
        }

        @Override
        public AsyncFuture<StoreEntity> store(KUID key, byte[] value) {
            return null;
        }

        @Override
        public long getTime(TimeUnit unit) {
            return 0;
        }

        @Override
        public long getTimeInMillis() {
            return 0;
        }
    };*/
    
    private final MaxStackCounter counter = new MaxStackCounter(4);
    
    private final StoreManager storeManager;
    
    private final KUID key;
    
    private final byte[] value;
    
    private final List<StoreResponse> responses 
        = new ArrayList<StoreResponse>();
    
    public StoreResponseHandler(
            MessageDispatcher messageDispatcher, 
            NodeEntity entity, KUID key, byte[] value) {
        super(messageDispatcher);
        
        if (key == null) {
            throw new NullPointerException("key");
        }
        
        if (value == null) {
            throw new NullPointerException("value");
        }
        
        this.storeManager = new StoreManager(entity);
        this.key = key;
        this.value = value;
    }

    @Override
    protected void go(AsyncFuture<StoreEntity> future) throws Exception {
        process(0);
    }

    private synchronized void process(int pop) throws IOException {
        try {
            preProcess(pop);
            
            while (counter.hasNext() && responses.size() < K) {
                if (!storeManager.hasNext()) {
                    break;
                }
                
                Contact contact = storeManager.next();
                store(contact);
                
                counter.push();
            }
        } finally {
            postProcess();
        }
    }
    
    private synchronized void preProcess(int pop) {
        while (0 < pop--) {
            counter.pop();
        }
    }
    
    private synchronized void postProcess() {
        
    }
    
    private void store(Contact dst) throws IOException {
        MessageFactory factory = messageDispatcher.getMessageFactory();
        StoreRequest request = factory.createStoreRequest(dst, key, value);
        messageDispatcher.send(this, request, 10L, TimeUnit.SECONDS);
    }
    
    @Override
    protected void processResponse(RequestMessage request, 
            ResponseMessage response, long time, TimeUnit unit) throws IOException {
        StoreResponse message = (StoreResponse)response;
        
        try {
            
        } finally {
            process(1);
        }
    }

    @Override
    protected void processTimeout(RequestMessage request, 
            long time, TimeUnit unit) throws IOException {
        //setException(new TimeoutIoException(request, time, unit));
        
        try {
            
        } finally {
            process(1);
        }
    }
    
    private static class StoreManager {
        
        private final NodeEntity entity;
        
        private int index = 0;
        
        public StoreManager(NodeEntity entity) {
            if (entity == null) {
                throw new NullPointerException("entity");
            }
            
            this.entity = entity;
        }
        
        public boolean hasNext() {
            return index < entity.size();
        }
        
        public Contact next() {
            return entity.getContact(index++);
        }
    }
}
