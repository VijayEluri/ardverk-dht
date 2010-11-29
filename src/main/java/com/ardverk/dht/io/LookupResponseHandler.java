package com.ardverk.dht.io;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.ardverk.concurrent.AsyncFuture;
import org.ardverk.lang.Arguments;
import org.slf4j.Logger;

import com.ardverk.dht.KUID;
import com.ardverk.dht.config.LookupConfig;
import com.ardverk.dht.entity.LookupEntity;
import com.ardverk.dht.logging.LoggerUtils;
import com.ardverk.dht.message.ResponseMessage;
import com.ardverk.dht.routing.Contact;
import com.ardverk.dht.routing.RouteTable;
import com.ardverk.dht.utils.SchedulingUtils;
import com.ardverk.dht.utils.XorComparator;

public abstract class LookupResponseHandler<T extends LookupEntity> 
        extends AbstractResponseHandler<T> {
    
    private static final Logger LOG 
        = LoggerUtils.getLogger(LookupResponseHandler.class);
    
    protected final LookupConfig config;
    
    private final LookupManager lookupManager;
    
    private final ProcessCounter lookupCounter;
    
    private long startTime = -1L;
    
    private ScheduledFuture<?> boostFuture;
    
    public LookupResponseHandler(MessageDispatcher messageDispatcher, 
            RouteTable routeTable, KUID lookupId, LookupConfig config) {
        super(messageDispatcher);
        
        this.config = Arguments.notNull(config, "config");
        lookupManager = new LookupManager(routeTable, lookupId);
        lookupCounter = new ProcessCounter(config.getAlpha());
    }

    @Override
    protected synchronized void go(AsyncFuture<T> future) throws IOException {
        
        long boostFrequency = config.getBoostFrequencyInMillis();
        
        if (0L < boostFrequency) {
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    try {
                        boost();                    
                    } catch (IOException err) {
                        LOG.error("IOException", err);
                    }
                }
            };
            
            boostFuture = SchedulingUtils.scheduleWithFixedDelay(
                    task, boostFrequency, boostFrequency, 
                    TimeUnit.MILLISECONDS);
        }
        
        process(0);
    }
    
    @Override
    protected synchronized void done() {
        if (boostFuture != null) {
            boostFuture.cancel(true);
        }
    }
    
    /**
     * Kicks off an additional lookup if we haven't received any 
     * responses for a while. 
     */
    private synchronized void boost() throws IOException {
        if (lookupManager.hasNext(true)) {
            long boostTimeout = config.getBoostTimeoutInMillis();
            
            if (boostTimeout >= 0L && getLastResponseTimeInMillis() >= boostTimeout) {
                try {
                    Contact contact = lookupManager.next();
                    
                    lookup(contact);
                    lookupCounter.increment(true);
                } finally {
                    postProcess();
                }
            }
        }
    }
    
    /**
     * The {@link #process(int)} method is the heart of the lookup process.
     */
    private synchronized void process(int decrement) throws IOException {
        try {
            preProcess(decrement);
            while (lookupCounter.hasNext()) {
                if (!lookupManager.hasNext()) {
                    break;
                }
                
                Contact contact = lookupManager.next();
                
                lookup(contact);
                lookupCounter.increment();
            }
        } finally {
            postProcess();
        }
    }
    
    /**
     * Sends a lookup request to the given {@link Contact}.
     */
    private void lookup(Contact dst) throws IOException {
        long defaultTimeout = config.getLookupTimeoutInMillis();
        long adaptiveTimeout = config.getAdaptiveTimeout(
                dst, defaultTimeout, TimeUnit.MILLISECONDS);
        lookup(dst, lookupManager.key, adaptiveTimeout, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Called by {@link #process(int)} before it is executing its own code.
     */
    private synchronized void preProcess(int decrement) {
        if (startTime == -1L) {
            startTime = System.currentTimeMillis();
        }
        
        while (0 < decrement--) {
            lookupCounter.decrement();
        }
    }
    
    /**
     * Called by {@link #process(int)} after it has executed its own code.
     */
    private synchronized void postProcess() {
        int count = lookupCounter.getProcesses();
        if (count == 0) {
            complete(createOutcome());
        }
    }
    
    /**
     * Sends a lookup request to the given {@link Contact}.
     */
    protected abstract void lookup(Contact dst, KUID lookupId, 
            long timeout, TimeUnit unit) throws IOException;
    
    /**
     * Called upon completion.
     */
    protected abstract void complete(Outcome outcome);
    
    
    @Override
    protected final synchronized void processResponse(RequestEntity entity,
            ResponseMessage response, long time, TimeUnit unit)
            throws IOException {
        
        try {
            processResponse0(entity, response, time, unit);
        } finally {
            process(1);
        }
    }
    
    /**
     * @see #processResponse(RequestEntity, ResponseMessage, long, TimeUnit)
     */
    protected abstract void processResponse0(RequestEntity entity,
            ResponseMessage response, long time, TimeUnit unit) throws IOException;

    /**
     * Adds the given {@link Contact}s to the lookup's internal processing
     * queue.
     */
    protected synchronized void processContacts(Contact src, 
            Contact[] contacts, long time, TimeUnit unit) throws IOException {
        lookupManager.handleResponse(src, contacts, time, unit);
    }

    @Override
    protected final synchronized void processTimeout(RequestEntity entity, 
            long time, TimeUnit unit) throws IOException {
        
        try {
            processTimeout0(entity, time, unit);
        } finally {
            process(1);
        }
    }
    
    /**
     * @see #processTimeout(RequestEntity, long, TimeUnit)
     */
    protected synchronized void processTimeout0(RequestEntity entity, 
            long time, TimeUnit unit) throws IOException {
        lookupManager.handleTimeout(time, unit);
    }
    
    /**
     * Creates and returns the current lookup {@link Outcome}.
     */
    protected synchronized Outcome createOutcome() {
        if (startTime == -1L) {
            throw new IllegalStateException("startTime=" + startTime);
        }
        
        final long time = System.currentTimeMillis() - startTime;
        final Contact[] contacts = lookupManager.getContacts();
        final int hop = lookupManager.getHop();
        final int timeouts = lookupManager.getErrorCount();
        
        return new Outcome() {

            @Override
            public KUID getLookupId() {
                return lookupManager.key;
            }

            @Override
            public Contact[] getContacts() {
                return contacts;
            }

            @Override
            public int getHop() {
                return hop;
            }
            
            @Override
            public int getErrorCount() {
                return timeouts;
            }

            @Override
            public long getTime(TimeUnit unit) {
                return unit.convert(time, TimeUnit.MILLISECONDS);
            }
        };
    }
    
    /**
     * The {@link Outcome} is a snapshot of the current lookup process.
     */
    public static abstract class Outcome {
        
        /**
         * Returns the lookup {@link KUID}.
         */
        public abstract KUID getLookupId();
        
        /**
         * Returns the {@link Contact}s that have been found.
         */
        public abstract Contact[] getContacts();
        
        /**
         * Returns the number of hops the lookup has taken.
         */
        public abstract int getHop();
        
        /**
         * Returns the number of errors that have been occurred.
         */
        public abstract int getErrorCount();
        
        /**
         * Returns the lookup time in the given {@link TimeUnit}.
         */
        public abstract long getTime(TimeUnit unit);
        
        /**
         * Returns the lookup time in milliseconds.
         */
        public long getTimeInMillis() {
            return getTime(TimeUnit.MILLISECONDS);
        }
    }
    
    /**
     * The {@link LookupManager} controls the lookup process.
     */
    private class LookupManager {
        
        private final boolean exhaustive = config.isExhaustive();
        
        private final boolean randomize = config.isRandomize();
        
        private final RouteTable routeTable;
        
        private final KUID key;
        
        /**
         * A {@link Set} of all responses
         */
        private final NavigableSet<Contact> responses;
        
        /**
         * A {@link Set} of the k-closest responses
         */
        private final NavigableSet<Contact> closest;
        
        /**
         * A {@link Set} of {@link Contact}s to query
         */
        private final NavigableSet<Contact> query;
        
        /**
         * A history of all {@link KUID}s that were added to the 
         * {@link #query} {@link NavigableSet}.
         */
        private final Map<KUID, Integer> history 
            = new HashMap<KUID, Integer>();
        
        private int currentHop = 0;
        
        private int timeouts = 0;
        
        public LookupManager(RouteTable routeTable, KUID key) {
            this.routeTable = Arguments.notNull(routeTable, "routeTable");
            this.key = Arguments.notNull(key, "key");
            
            Contact localhost = routeTable.getLocalhost();
            KUID contactId = localhost.getId();
            
            XorComparator comparator = new XorComparator(key);
            this.responses = new TreeSet<Contact>(comparator);
            this.closest = new TreeSet<Contact>(comparator);
            this.query = new TreeSet<Contact>(comparator);
            
            history.put(contactId, 0);
            Contact[] contacts = routeTable.select(key);
            
            if (0 < contacts.length) {
                addToResponses(localhost);
                
                for (Contact contact : contacts) {
                    addToQuery(contact, 1);
                }
            }
        }
        
        public void handleResponse(Contact src, Contact[] contacts, 
                long time, TimeUnit unit) {
            
            boolean success = addToResponses(src);
            if (!success) {
                return;
            }
            
            for (Contact contact : contacts) {
                if (addToQuery(contact, currentHop+1)) {
                    routeTable.add(contact);
                }
            }
        }
        
        public void handleTimeout(long time, TimeUnit unit) {
            timeouts++;
        }
        
        public Contact[] getContacts() {
            return responses.toArray(new Contact[0]);
        }
        
        public int getHop() {
            return currentHop;
        }
        
        public int getErrorCount() {
            return timeouts;
        }
        
        private boolean addToResponses(Contact contact) {
            if (responses.add(contact)) {
                closest.add(contact);
                
                if (closest.size() > routeTable.getK()) {
                    closest.pollLast();
                }
                
                KUID contactId = contact.getId();
                currentHop = history.get(contactId);
                return true;
            }
            
            return false;
        }
        
        private boolean addToQuery(Contact contact, int hop) {
            KUID contactId = contact.getId();
            if (!history.containsKey(contactId)) { 
                history.put(contactId, hop);
                query.add(contact);
                return true;
            }
            
            return false;
        }
        
        private boolean isCloserThanClosest(Contact other) {
            if (!closest.isEmpty()) {
                Contact contact = closest.last();
                KUID contactId = contact.getId();
                KUID otherId = other.getId();
                return otherId.isCloserTo(key, contactId);
            }
            
            return true;
        }
        
        public boolean hasNext() {
            return hasNext(false);
        }
        
        public boolean hasNext(boolean force) {
            if (!query.isEmpty()) {
                
                Contact contact = query.first();
                if (force || exhaustive
                        || closest.size() < routeTable.getK() 
                        || isCloserThanClosest(contact)) {
                    return true;
                }
            }
            
            return false;
        }
        
        public Contact next() {
            Contact contact = null;
            
            if (randomize && !query.isEmpty()) {
                
                // Knuth: Can we pick a random element from a set of 
                // items whose cardinality we do not know?
                //
                // Pick an item and store it. Pick the next one, and replace 
                // the first one with it with probability 1/2. Pick the third 
                // one, and do a replace with probability 1/3, and so on. At 
                // the end, the item you've stored has a probability of 1/n 
                // of being any particular element.
                //
                // NOTE: We do know the cardinality but we don't have the 
                // required methods to retrieve elements from the Set and 
                // are forced to use the Iterator (streaming) as described 
                // above.
                
                int index = 0;
                for (Contact c : query) {
                    
                    if (index >= routeTable.getK()) {
                        break;
                    }
                    
                    // First element is always true because 1/1 >= random[0..1]!
                    if (1d/++index >= Math.random()) {
                        contact = c;
                    }
                }
                
                query.remove(contact);
                
            } else {
                contact = query.pollFirst();
            }
            
            if (contact == null) {
                throw new NoSuchElementException();
            }
            return contact;
        }
    }
}