package com.ardverk.dht.routing;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.Map.Entry;

import org.ardverk.collection.Cursor;
import org.ardverk.collection.KeyAnalyzer;
import org.ardverk.collection.PatriciaTrie;
import org.ardverk.collection.Trie;
import org.ardverk.collection.Cursor.Decision;
import org.slf4j.Logger;

import com.ardverk.collection.FixedSizeHashMap;
import com.ardverk.concurrent.AsyncFuture;
import com.ardverk.concurrent.AsyncFutureListener;
import com.ardverk.dht.ContactPinger;
import com.ardverk.dht.KUID;
import com.ardverk.dht.KeyFactory;
import com.ardverk.dht.message.PingResponse;
import com.ardverk.dht.routing.Contact.Type;
import com.ardverk.logging.LoggerUtils;
import com.ardverk.net.NetworkConstants;
import com.ardverk.net.NetworkCounter;
import com.ardverk.utils.NetworkUtils;

public class DefaultRouteTable extends AbstractRouteTable {
    
    private static final long serialVersionUID = 2942655317183321858L;
    
    private static final Logger LOG 
        = LoggerUtils.getLogger(DefaultRouteTable.class);
    
    private final Contact localhost;
    
    private final KeyAnalyzer<KUID> keyAnalyzer;
    
    private final Trie<KUID, Bucket> buckets;
    
    private int consecutiveErrors = 0;
    
    public DefaultRouteTable(ContactPinger pinger, ContactFactory contactFactory, 
            int k, KUID contactId, int instanceId, SocketAddress address) {
        super(pinger, contactFactory, k);
        
        if (contactId == null) {
            throw new NullPointerException("contactId");
        }
        
        if (address == null) {
            throw new NullPointerException("address");
        }
        
        KeyFactory keyFactory = getKeyFactory();
        int lengthInBits = keyFactory.lengthInBits();
        if (contactId.lengthInBits() != lengthInBits) {
            throw new IllegalArgumentException(
                    "Bits: " + lengthInBits + " vs. " 
                        + contactId.lengthInBits());
        }
        
        this.keyAnalyzer = KUID.createKeyAnalyzer(lengthInBits);
        
        this.localhost = contactFactory.createCharted(contactId, instanceId, address);
        this.buckets = new PatriciaTrie<KUID, Bucket>(keyAnalyzer);
        
        init();
    }
    
    @Override
    public Contact getLocalhost() {
        return localhost;
    }
    
    private synchronized void init() {
        consecutiveErrors = 0;
        
        KUID bucketId = getKeyFactory().min();
        Bucket bucket = new Bucket(bucketId, 0, getK(), getMaxCacheSize());
        buckets.put(bucketId, bucket);
        
        innerAdd(localhost);
    }
    
    private boolean isLocalhost(Contact contact) {
        return localhost.getContactId().equals(contact.getContactId());
    }
    
    @Override
    public synchronized void add(Contact contact) {
        if (contact == null) {
            throw new NullPointerException("contact");
        }
        
        KeyFactory keyFactory = getKeyFactory();
        KUID contactId = contact.getContactId();
        if (keyFactory.lengthInBits() != contactId.lengthInBits()) {
            throw new IllegalArgumentException(
                    "Bits: " + keyFactory.lengthInBits() 
                    + " vs. " + contactId.lengthInBits());
        }
        
        // Nobody and nothing can add a Contact that has 
        // the exact same KUID as the localhost Contact!
        if (isLocalhost(contact)) {
            return;
        }
        
        // Reset the consecutive errors counter every time
        // we receive a "message" from an actual Contact.
        if (contact.isActive()) {
            consecutiveErrors = 0;
        }
        
        innerAdd(contact);
    }
    
    private synchronized void innerAdd(Contact contact) {
        KUID contactId = contact.getContactId();
        Bucket bucket = buckets.selectValue(contactId);
        ContactEntity entity = bucket.get(contactId);
        
        if (entity != null) {
            updateContact(bucket, entity, contact);
        } else if (!bucket.isActiveFull()) {
            if (isOkayToAdd(bucket, contact)) {
                addActive(bucket, contact);
            } else if (!canSplit(bucket)) {
                addCache(bucket, contact);
            }
        } else if (split(bucket)) {
            innerAdd(contact);
        } else {
            replaceCache(bucket, contact);
        }
    }
    
    private synchronized void updateContact(Bucket bucket, 
            ContactEntity entity, Contact contact) {
        
        assert (!entity.same(localhost) 
                && !contact.equals(localhost));
        
        // 
        if (entity.isAlive() && !contact.isSolicited()) {
            return;
        }
        
        if (entity.isSameRemoteAddress(contact)) {
            Contact[] merged = entity.update(contact);
            fireContactChanged(bucket, merged[0], merged[1]);
        } else {
            // Spoof Check
        }
    }
    
    private static final int MAX_PER_BUCKET = Integer.MAX_VALUE;
    
    private synchronized boolean isOkayToAdd(Bucket bucket, Contact contact) {
        SocketAddress address = contact.getRemoteAddress();
        return bucket.getActiveCount(address) < MAX_PER_BUCKET;
    }
    
    private synchronized void addActive(Bucket bucket, Contact contact) {
        ContactEntity entity = new ContactEntity(contact);
        boolean success = bucket.addActive(entity);
        
        if (success) {
            fireContactAdded(bucket, contact);
        }
    }
    
    private synchronized void addCache(Bucket bucket, Contact contact) {
        ContactEntity entity = new ContactEntity(contact);
        ContactEntity other = bucket.addCache(entity);
        
        if (other != null) {
            if (entity == other) {
                fireContactAdded(bucket, contact);
            } else {
                fireContactReplaced(bucket, other.getContact(), contact);
            }
        }
    }
    
    private synchronized void replaceCache(Bucket bucket, Contact contact) {
        
    }
    
    private synchronized boolean split(Bucket bucket) {
        if (canSplit(bucket)) {
            if (LOG.isInfoEnabled()) {
                LOG.info("Splitting Bucket: " + bucket);
            }
            
            Bucket[] split = bucket.split();
            assert (split.length == 2);
            
            Bucket left = split[0];
            Bucket right = split[1];
            
            // The left one replaces the existing Bucket
            Bucket oldLeft = buckets.put(left.getBucketId(), left);
            assert (oldLeft == bucket);
            
            // The right one is new in the RouteTable
            Bucket oldRight = this.buckets.put(right.getBucketId(), right);
            assert (oldRight == null);
            
            fireSplitBucket(bucket, left, right);
            return true;
        }
        
        return false;
    }
    
    private synchronized boolean canSplit(Bucket bucket) {
        
        // We *split* the Bucket if:
        // 1. Bucket contains the localhost Contact
        // 2. Bucket is smallest subtree
        // 3. Bucket hasn't reached its max depth
        KUID contactId = localhost.getContactId();
        
        if (bucket.contains(contactId)
                || isSmallestSubtree(bucket)
                || !isTooDeep(bucket)) {
            return true;
        }
        return false;
    }
    
    @Override
    public Contact[] select(KUID contactId, int count) {
        List<Contact> items = new ArrayList<Contact>(count);
        selectR(contactId, items, count);
        return items.toArray(new Contact[0]);
    }
    
    private synchronized void selectR(final KUID contactId, 
            final Collection<Contact> dst, final int count) {
        
        if (contactId == null) {
            throw new NullPointerException("contactId");
        }
        
        if (dst.size() >= count) {
            return;
        }
       
        buckets.select(contactId, new Cursor<KUID, Bucket>() {
            @Override
            public Decision select(Entry<? extends KUID, ? extends Bucket> entry) {
                Bucket bucket = entry.getValue();
                return bucket.select(contactId, dst, count);
            }
        });
    }
    
    public int getMaxDepth() {
        return Integer.MAX_VALUE;
    }
    
    public int getMaxCacheSize() {
        return 16;
    }
    
    /**
     * Returns true if the given {@link Bucket} has reached its maximum
     * depth in the RoutingTable Tree.
     */
    private synchronized boolean isTooDeep(Bucket bucket) {
        return bucket.getDepth() >= getMaxDepth();
    }
    
    /**
     * Returns true if the given {@link Bucket} is the closest left
     * or right hand sibling of the {@link Bucket} which contains 
     * the localhost {@link Contact}.
     */
    private synchronized boolean isSmallestSubtree(Bucket bucket) {
        KUID contactId = localhost.getContactId();
        KUID bucketId = bucket.getBucketId();
        int prefixLength = contactId.getPrefixLength(bucketId);
        
        // The sibling Bucket contains the localhost Contact. 
        // We're looking if the other Bucket is its sibling 
        // (what we call the smallest subtree).
        Bucket sibling = buckets.selectValue(contactId);
        return (sibling.getDepth() - 1) == prefixLength;
    }
    
    private synchronized void ping(Contact contact) {
        AsyncFutureListener<PingResponse> listener 
                = new AsyncFutureListener<PingResponse>() {
            @Override
            public void operationComplete(AsyncFuture<PingResponse> future) {
            }
        };
        
        AsyncFuture<PingResponse> future = pinger.ping(contact);
        future.addAsyncFutureListener(listener);
    }
    
    private static final int MAX_CONSECUTIVE_ERRORS = 100;
    
    @Override
    public synchronized void failure(KUID contactId, SocketAddress address) {
        if (contactId == null) {
            return;
        }
        
        Bucket bucket = buckets.selectValue(contactId);
        ContactEntity entity = bucket.get(contactId);
        
        if (entity == null) {
            return;
        }
        
        // Make sure we're not going kill the entire RouteTable 
        // if the Network goes down!
        if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            return;
        }
        
        boolean dead = entity.error();
        if (dead) {
            
        }
    }
    
    @Override
    public synchronized void rebuild() {
        
    }
    
    private static class ContactEntity {
        
        private static final int MAX_ERRORS = 5;
        
        private final long creationTime = System.currentTimeMillis();
        
        private long timeStamp = creationTime;
        
        private final KUID contactId;
        
        private Contact contact;
        
        private int errorCount = 0;
        
        public ContactEntity(Contact contact) {
            if (contact == null) {
                throw new NullPointerException("contact");
            }
            
            this.contactId = contact.getContactId();
            this.contact = contact;
        }
        
        public long getCreationTime() {
            return creationTime;
        }
        
        public long getTimeStamp() {
            return timeStamp;
        }
        
        public KUID getContactId() {
            return contactId;
        }
        
        public Contact getContact() {
            return contact;
        }
        
        public Contact[] update(Contact contact) {
            if (contact == null) {
                throw new NullPointerException("contact");
            }
            
            if (!contactId.equals(contact.getContactId())) {
                throw new IllegalArgumentException();
            }
            
            Contact previous = this.contact;
            
            if (previous != null) {
                if (previous.getType() == Type.SOLICITED
                        && contact.getType() == Type.UNSOLICITED) {
                    throw new IllegalArgumentException();
                }
                
                contact = new DefaultContact(previous, contact);
            }
            
            this.contact = contact;
            this.timeStamp = System.currentTimeMillis();
            
            return new Contact[] { previous, contact };
        }
        
        public Contact replaceContact(Contact contact) {
            return update(contact)[0];
        }
        
        public boolean error() {
            ++errorCount;
            return isDead();
        }
        
        public boolean isSolicited() {
            return contact.isSolicited();
        }
        
        public boolean isUnsolicited() {
            return contact.isUnsolicited();
        }
        
        public boolean isDead() {
            return errorCount >= MAX_ERRORS;
        }
        
        public boolean isAlive() {
            return !isDead() && contact.isActive();
        }
        
        public boolean isUnknown() {
            return !isDead() && isUnsolicited();
        }
        
        public boolean isSameRemoteAddress(Contact contact) {
            return NetworkUtils.isSameAddress(
                    this.contact.getRemoteAddress(), 
                    contact.getRemoteAddress());
        }
        
        private static final long X = 5L*60L*1000L;
        
        public boolean hasBeenActiveRecently() {
            return (System.currentTimeMillis() - getTimeStamp()) < X;
        }
        
        public boolean same(Contact other) {
            if (other == null) {
                throw new NullPointerException("other");
            }
            
            return contact != null && contact.equals(other);
        }
    }
    
    public class Bucket {
        
        private final KUID bucketId;
        
        private final int depth;
        
        private final Trie<KUID, ContactEntity> active;
        
        private final FixedSizeHashMap<KUID, ContactEntity> cached;
        
        private final NetworkCounter counter 
            = new NetworkCounter(NetworkConstants.CLASS_C);
        
        private Bucket(KUID bucketId, int depth, int k, int maxCacheSize) {
            if (bucketId == null) {
                throw new NullPointerException("bucketId");
            }
            
            if (depth <= 0) {
                throw new IllegalArgumentException("depth=" + depth);
            }
            
            if (k <= 0) {
                throw new IllegalArgumentException("k=" + k);
            }
            
            if (maxCacheSize < 0) {
                throw new IllegalArgumentException(
                        "maxCacheSize=" + maxCacheSize);
            }
            
            this.bucketId = bucketId;
            this.depth = depth;
            
            active = new PatriciaTrie<KUID, ContactEntity>(keyAnalyzer);
            
            cached = new FixedSizeHashMap<KUID, ContactEntity>(
                    maxCacheSize, 1.0f, true, maxCacheSize);
        }
        
        public KUID getBucketId() {
            return bucketId;
        }
        
        public int getDepth() {
            return depth;
        }
        
        public int getMaxCacheSize() {
            return cached.getMaxSize();
        }
        
        public int getCacheSize() {
            return cached.size();
        }
        
        public boolean isCacheEmpty() {
            return cached.isEmpty();
        }
        
        public boolean isCacheFull() {
            return cached.isFull();
        }
        
        public boolean isActiveFull() {
            return active.size() >= getK();
        }
        
        public boolean isActiveEmpty() {
            return active.isEmpty();
        }
        
        private static final double PROBABILITY = 0.75d;
        
        public Decision select(KUID contactId, 
                final Collection<Contact> dst, final int count) {
            
            active.select(contactId, new Cursor<KUID, ContactEntity>() {
                @Override
                public Decision select(Entry<? extends KUID, 
                        ? extends ContactEntity> entry) {
                    
                    ContactEntity handle = entry.getValue();
                    
                    double probability = 1.0d;
                    if (handle.isDead()) {
                        probability = Math.random();
                    }
                    
                    if (probability >= PROBABILITY) {
                        dst.add(handle.getContact());
                    }
                    
                    return (dst.size() < count ? Decision.CONTINUE : Decision.EXIT);
                }
            });
            
            return (dst.size() < count ? Decision.CONTINUE : Decision.EXIT);
        }
        
        public ContactEntity get(KUID contactId) {
            ContactEntity entity = active.get(contactId);
            if (entity == null) {
                entity = cached.get(contactId);
            }
            return entity;
        }
        
        public ContactEntity[] getActive() {
            return active.values().toArray(new ContactEntity[0]);
        }
        
        public ContactEntity[] getCached() {
            return cached.values().toArray(new ContactEntity[0]);
        }
        
        public int getActiveCount(SocketAddress address) {
            return counter.get(address);
        }
        
        public void add(ContactEntity entity) {
            // Remove it from the Cache if it's there
            removeCache(entity);
            
            // Add it to the active RouteTable if possible
            boolean success = addActive(entity);
            
            // Add the Contact back to the Cache if it was not 
            // possible to add it to the active RouteTable
            if (!success) {
                addCache(entity);
            }
        }
        
        private boolean addActive(ContactEntity entity) {
            Contact contact = entity.getContact();
            KUID contactId = contact.getContactId();
            
            // Make sure Bucket does not contain the Contact!
            assert (!active.containsKey(contactId)
                    && !cached.containsKey(contactId));
                
            if (hasOrMakeSpace()) {
                active.put(contactId, entity);
                counter.add(contact.getRemoteAddress());
                return true;
            }
            
            return false;
        }
        
        private ContactEntity addCache(ContactEntity entity) {
            Contact contact = entity.getContact();
            KUID contactId = contact.getContactId();
            
            // Make sure Bucket does not contain the Contact!
            assert (!active.containsKey(contactId)
                    && !cached.containsKey(contactId));
            
            if (!isCacheFull()) {
                cached.put(contactId, entity);
                return entity;
            }
            
            ContactEntity lrs = getLeastRecentlySeenCachedContact();
            if (lrs.isDead() || (!lrs.hasBeenActiveRecently() && !entity.isDead())) {
                ContactEntity removed = cached.remove(lrs.getContactId());
                assert (lrs == removed);
                
                cached.put(contactId, entity);
                return removed;
            }
            
            return null;
        }
        
        private ContactEntity getLeastRecentlySeenCachedContact() {
            ContactEntity lrs = null;
            for (ContactEntity entity : cached.values()) {
                if (lrs == null || entity.getTimeStamp() < lrs.getTimeStamp()) {
                    lrs = entity;
                }
            }
            return lrs;
        }
        
        private ContactEntity getLeastRecentlySeenActiveContact() {
            ContactEntity lrs = null;
            for (ContactEntity entity : active.values()) {
                if (lrs == null || entity.getTimeStamp() < lrs.getTimeStamp()) {
                    lrs = entity;
                }
            }
            return lrs;
        }
        
        private ContactEntity removeCache(ContactEntity entity) {
            KUID contactId = entity.getContactId();
            return cached.remove(contactId);
        }
        
        private boolean hasOrMakeSpace() {
            if (isActiveFull()) {
                for (ContactEntity current : getActive()) {
                    if (current.isDead()) {
                        removeActive(current);
                        break;
                    }
                }
            }
            
            return !isActiveFull();
        }
        
        private void removeActive(ContactEntity entity) {
            ContactEntity other = active.remove(entity.getContactId());
            assert (entity == other);
            
            SocketAddress address = other.getContact().getRemoteAddress();
            counter.remove(address);
        }
        
        public Bucket[] split() {
            KUID bucketId = getBucketId();
            int depth = getDepth();
            int k = getK();
            int maxCacheSize = getMaxCacheSize();
            
            Bucket left = new Bucket(bucketId, 
                    depth+1, k, maxCacheSize);
            
            Bucket right = new Bucket(bucketId.set(depth), 
                    depth+1, k, maxCacheSize);
            
            for (ContactEntity entity : active.values()) {
                KUID contactId = entity.getContactId();
                if (!contactId.isSet(depth)) {
                    left.add(entity);
                } else {
                    right.add(entity);
                }
            }
            
            for (ContactEntity entity : cached.values()) {
                KUID contactId = entity.getContactId();
                if (!contactId.isSet(depth)) {
                    left.add(entity);
                } else {
                    right.add(entity);
                }
            }
            
            return new Bucket[] { left, right };
        }
        
        public boolean contains(KUID contactId) {
            return active.containsKey(contactId)
                || cached.containsKey(contactId);
        }
    }
    
    public static void main(String[] args) {
        TreeMap<KUID, KUID> tree = new TreeMap<KUID, KUID>();
        Trie<KUID, KUID> trie = new PatriciaTrie<KUID, KUID>(KUID.createKeyAnalyzer(160));
        
        Random generator = new Random();
        
        for (int i = 0; i < 5; i++) {
            byte[] data = new byte[20];
            generator.nextBytes(data);
            
            KUID key = new KUID(data, KUID.NO_BIT_MASK, 160);
            
            tree.put(key, key);
            trie.put(key, key);
        }
        
        byte[] data = new byte[20];
        generator.nextBytes(data);
        final KUID lookupKey = new KUID(data, KUID.NO_BIT_MASK, 160);
        
        System.out.println("KEY: " + lookupKey);
        System.out.println();
        
        Comparator<KUID> comparator = new Comparator<KUID>() {
            @Override
            public int compare(KUID o1, KUID o2) {
                return o1.xor(lookupKey).compareTo(o2.xor(lookupKey));
            }
        };
        
        KUID[] keys = tree.keySet().toArray(new KUID[0]);
        Arrays.sort(keys, comparator);
        
        for (KUID id : keys) {
            System.out.println("TREE: " + id + ", " + id.xor(lookupKey));
        }
        
        System.out.println();
        
        Cursor<KUID, KUID> cursor = new Cursor<KUID, KUID>() {
            @Override
            public Decision select(Entry<? extends KUID, ? extends KUID> entry) {
                System.out.println("TRIE: " + entry.getKey() + ", " + entry.getKey().xor(lookupKey));
                return Decision.CONTINUE;
            }
        };
        
        trie.select(lookupKey, cursor);
    }
}
