package net.sf.ehcache.transaction.xa;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import net.sf.ehcache.Element;
import net.sf.ehcache.store.Store;
import net.sf.ehcache.transaction.StoreWriteCommand;
import net.sf.ehcache.transaction.TransactionContext;
import net.sf.ehcache.transaction.VersionAwareStoreWriteCommand;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Nabib El-Rahman
 */
public class EhCacheXAResourceImpl implements EhCacheXAResource {

    private static final Logger LOG = LoggerFactory.getLogger(EhCacheXAResourceImpl.class.getName());

    private final ConcurrentMap<Transaction, TransactionContext> transactionDataTable = new ConcurrentHashMap<Transaction, TransactionContext>();
    private final ConcurrentMap<Xid, Transaction> transactionXids = new ConcurrentHashMap<Xid, Transaction>();
    private final VersionTable versionTable = new VersionTable();

    private final String cacheName;
    private final Store store;
    private final TransactionManager txnManager;
    private int transactionTimeout;

    public EhCacheXAResourceImpl(String cacheName, Store store, TransactionManager txnManager) {
        this.cacheName = cacheName;
        this.store = store;
        this.txnManager = txnManager;
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.sf.ehcache.transaction.xa.EhCacheXAResource#getCacheName()
     */
    public String getCacheName() {
        return cacheName;
    }

    /**
     * XAResource Implementation
     */
    public void start(final Xid xid, final int flags) throws XAException {
        LOG.info("start called for Txn with id: " + xid);
        try {
            transactionXids.putIfAbsent(xid, txnManager.getTransaction());
        } catch (SystemException e) {
            XAException xaException = new XAException("WTF? " + e.getMessage());
            xaException.initCause(e);
            throw xaException;
        }
        System.out.println("\n\n\n    ===> SUCCESSFULLY LINKED XID TO TX\n\n");
    }

    public void commit(final Xid xid, final boolean onePhase) throws XAException {
        LOG.info("commit called for Txn with id: " + xid);
        TransactionContext context = transactionDataTable.get(transactionXids.get(xid));
        for (StoreWriteCommand storeWriteCommand : context.getCommands()) {
            storeWriteCommand.execute(store);
        }
    }

    public void end(final Xid xid, final int flags) throws XAException {
        LOG.info("end called for Txn with id: " + xid);
    }

    public void forget(final Xid xid) throws XAException {
        LOG.info("forget called for Txn with id: " + xid);
    }

    public int prepare(final Xid xid) throws XAException {
        TransactionContext context = transactionDataTable.get(transactionXids.get(xid));
        Transaction txn = context.getTransaction();
        for (StoreWriteCommand storeWriteCommand : context.getCommands()) {
            if (storeWriteCommand instanceof VersionAwareStoreWriteCommand) {
                VersionAwareStoreWriteCommand command = (VersionAwareStoreWriteCommand) storeWriteCommand;
                Element element = command.getElement();
                if(!versionTable.valid(element, txn)) {
                    throw new XAException("Invalid versions during prepare phase!.");
                }
            }
        }
        return XA_OK;
    }

    public Xid[] recover(final int i) throws XAException {
        return new Xid[0];
    }

    public void rollback(final Xid xid) throws XAException {
        LOG.info("rollback called for Txn with id: " + xid);
    }

    public boolean isSameRM(final XAResource xaResource) throws XAException {
        return this == xaResource;
    }

    public boolean setTransactionTimeout(final int i) throws XAException {
        this.transactionTimeout = i;
        // TODO: Figure out what to return here, it should be set to true of
        // setting the transaction timeout was successful.
        return false;
    }

    public int getTransactionTimeout() throws XAException {
        return this.transactionTimeout;
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.sf.ehcache.transaction.xa.EhCacheXAResource#getStore()
     */
    public Store getStore() {
        return store;
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.sf.ehcache.transaction.xa.EhCacheXAResource#checkoutReadOnly(net.sf.ehcache.Element, javax.transaction.Transaction)
     */
    public long checkout(Element element, Transaction txn) {
        return versionTable.checkout(element, txn);
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.sf.ehcache.transaction.xa.EhCacheXAResource#getOrCreateTransactionContext()
     */
    public TransactionContext getOrCreateTransactionContext() throws SystemException, RollbackException {
        Transaction transaction = txnManager.getTransaction();
        TransactionContext context = transactionDataTable.get(transaction);
        if (context == null) {
            context = new XaTransactionContext(transaction);
            transaction.enlistResource(this);
            TransactionContext previous = transactionDataTable.putIfAbsent(transaction, context);
            if (previous != null) {
                context = previous;
            }
        }
        return context;
    }

    @Override
    public boolean equals(Object obj) {
        EhCacheXAResource resource2 = (EhCacheXAResource) obj;
        if (cacheName.equals(resource2.getCacheName())) {
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return cacheName.hashCode();
    }

    public static class VersionTable {

        protected final ConcurrentMap<Object, Version> versionStore = new ConcurrentHashMap<Object, Version>();
        
        public boolean valid(Element element, Transaction txn) {
            long versionNumber = -1;
            Object key = element.getObjectKey();
            Version version = versionStore.get(key);
            if (version != null) {
                long currentVersion = version.getCurrentVersion();
                long txnVersion = version.getVersion(txn);
                return currentVersion == txnVersion;
            } else {
                //TODO: Figure out what this case is..
                return true;
            }
          
        }

        public long checkout(Element element, Transaction txn) {
            long versionNumber = -1;
            Object key = element.getObjectKey();
            Version version = versionStore.get(key);
            if (version == null) {
                version = new Version();
                versionStore.put(key, version);
            }
            versionNumber = version.checkout(txn);

            return versionNumber;
        }

        public void checkin(Element element, Transaction txn, boolean readOnly) {
            Object key = element.getObjectKey();
            Version version = versionStore.get(key);
            boolean removeEntry = false;
            if (readOnly) {
                removeEntry = version.checkinRead(txn);
            } else {
                removeEntry = version.checkinWrite(txn);
            }
            if (removeEntry) {
                versionStore.remove(key);
            }
        }

    }

    static class Version {

        AtomicLong version = new AtomicLong(0);

        // TODO: We need to figure out a more compressed data-structure (need to performance test to confirm
        ConcurrentMap<Transaction, Long> txnVersionMap = new ConcurrentHashMap<Transaction, Long>();

        public long getCurrentVersion() {
            return version.get();
        }

        public boolean hasTransaction(Transaction txn) {
            return txnVersionMap.containsKey(txn);
        }

        public long getVersion(Transaction txn) {
            try {
                return txnVersionMap.get(txn);
            } catch (NullPointerException e) {
                throw new AssertionError("Cannot get version for not existing transaction: " + txn);
            }
        }

        public long checkout(Transaction txn) {
            long v = version.get();
            txnVersionMap.put(txn, v);
            return v;
        }

        public boolean checkinRead(Transaction txn) {
            txnVersionMap.remove(txn);
            return txnVersionMap.isEmpty();
        }

        public boolean checkinWrite(Transaction txn) {
            long v = txnVersionMap.remove(txn);
            version.incrementAndGet();
            return txnVersionMap.isEmpty();
        }
    }

}
