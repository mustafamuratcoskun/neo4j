/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import org.neo4j.collection.Dependencies;
import org.neo4j.collection.pool.LinkedQueuePool;
import org.neo4j.collection.pool.MarshlandPool;
import org.neo4j.collection.pool.Pool;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.function.Factory;
import org.neo4j.graphdb.DatabaseShutdownException;
import org.neo4j.graphdb.TransactionFailureException;
import org.neo4j.internal.id.IdController;
import org.neo4j.internal.index.label.LabelScanStore;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.internal.kernel.api.security.SecurityContext;
import org.neo4j.internal.schema.SchemaState;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracerSupplier;
import org.neo4j.io.pagecache.tracing.cursor.context.VersionContextSupplier;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.KernelTransactionHandle;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.availability.AvailabilityGuard;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.api.index.stats.IndexStatisticsStore;
import org.neo4j.kernel.impl.api.state.ConstraintIndexCreator;
import org.neo4j.kernel.impl.constraints.ConstraintSemantics;
import org.neo4j.kernel.impl.factory.AccessCapability;
import org.neo4j.kernel.impl.locking.StatementLocks;
import org.neo4j.kernel.impl.locking.StatementLocksFactory;
import org.neo4j.kernel.impl.transaction.TransactionMonitor;
import org.neo4j.kernel.impl.transaction.tracing.TransactionTracer;
import org.neo4j.kernel.impl.util.MonotonicCounter;
import org.neo4j.kernel.impl.util.collection.CollectionsFactorySupplier;
import org.neo4j.kernel.internal.event.DatabaseTransactionEventListeners;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.lock.LockTracer;
import org.neo4j.resources.CpuClock;
import org.neo4j.resources.HeapAllocation;
import org.neo4j.storageengine.api.StorageEngine;
import org.neo4j.storageengine.api.TransactionId;
import org.neo4j.storageengine.api.TransactionIdStore;
import org.neo4j.time.SystemNanoClock;
import org.neo4j.token.TokenHolders;

import static java.util.stream.Collectors.toSet;

/**
 * Central source of transactions in the database.
 * <p>
 * This class maintains references to all transactions, a pool of passive kernel transactions, and provides
 * capabilities
 * for enumerating all running transactions. During normal operation, acquiring new transactions and enumerating live
 * ones requires no synchronization (although the live list is not guaranteed to be exact).
 */
public class KernelTransactions extends LifecycleAdapter implements Supplier<IdController.ConditionSnapshot>
{
    private final StatementLocksFactory statementLocksFactory;
    private final ConstraintIndexCreator constraintIndexCreator;
    private final TransactionCommitProcess transactionCommitProcess;
    private final DatabaseTransactionEventListeners eventListeners;
    private final TransactionMonitor transactionMonitor;
    private final AvailabilityGuard databaseAvailabilityGuard;
    private final StorageEngine storageEngine;
    private final GlobalProcedures globalProcedures;
    private final TransactionIdStore transactionIdStore;
    private final AtomicReference<CpuClock> cpuClockRef;
    private final AtomicReference<HeapAllocation> heapAllocationRef;
    private final AccessCapability accessCapability;
    private final SystemNanoClock clock;
    private final VersionContextSupplier versionContextSupplier;
    private final ReentrantReadWriteLock newTransactionsLock = new ReentrantReadWriteLock();
    private final MonotonicCounter userTransactionIdCounter = MonotonicCounter.newAtomicMonotonicCounter();
    private final TokenHolders tokenHolders;
    private final NamedDatabaseId namedDatabaseId;
    private final IndexingService indexingService;
    private final LabelScanStore labelScanStore;
    private final IndexStatisticsStore indexStatisticsStore;
    private final Dependencies databaseDependendies;
    private final Config config;
    private final CollectionsFactorySupplier collectionsFactorySupplier;
    private final SchemaState schemaState;
    private final TransactionTracer transactionTracer;
    private final PageCursorTracerSupplier pageCursorTracerSupplier;
    private final LockTracer lockTracer;
    private final LeaseService leaseService;

    /**
     * Used to enumerate all transactions in the system, active and idle ones.
     * <p>
     * This data structure is *only* updated when brand-new transactions are created, or when transactions are disposed
     * of. During normal operation (where all transactions come from and are returned to the pool), this will be left
     * in peace, working solely as a collection of references to all transaction objects (idle and active) in the
     * database.
     * <p>
     * As such, it provides a good mechanism for listing all transactions without requiring synchronization when
     * starting and committing transactions.
     */
    private final Set<KernelTransactionImplementation> allTransactions = ConcurrentHashMap.newKeySet();

    // This is the factory that actually builds brand-new instances.
    private final Factory<KernelTransactionImplementation> factory;
    // Global pool of transactions, wrapped by the thread-local marshland pool and so is not used directly.
    private final LinkedQueuePool<KernelTransactionImplementation> globalTxPool;
    // Pool of unused transactions.
    private final MarshlandPool<KernelTransactionImplementation> localTxPool;
    private final ConstraintSemantics constraintSemantics;
    private final AtomicInteger activeTransactionCounter = new AtomicInteger();
    private final TokenHoldersIdLookup tokenHoldersIdLookup;

    /**
     * Kernel transactions component status. True when stopped, false when started.
     * Will not allow to start new transaction by stopped instance of kernel transactions.
     * Should simplify tracking of stopped component usage by up the stack components.
     */
    private volatile boolean stopped = true;

    public KernelTransactions( Config config, StatementLocksFactory statementLocksFactory, ConstraintIndexCreator constraintIndexCreator,
            TransactionCommitProcess transactionCommitProcess,
            DatabaseTransactionEventListeners eventListeners, TransactionMonitor transactionMonitor, AvailabilityGuard databaseAvailabilityGuard,
            StorageEngine storageEngine, GlobalProcedures globalProcedures, TransactionIdStore transactionIdStore, SystemNanoClock clock,
            AtomicReference<CpuClock> cpuClockRef, AtomicReference<HeapAllocation> heapAllocationRef, AccessCapability accessCapability,
            VersionContextSupplier versionContextSupplier, CollectionsFactorySupplier collectionsFactorySupplier, ConstraintSemantics constraintSemantics,
            SchemaState schemaState, TokenHolders tokenHolders, NamedDatabaseId namedDatabaseId, IndexingService indexingService, LabelScanStore labelScanStore,
            IndexStatisticsStore indexStatisticsStore, Dependencies databaseDependencies, TransactionTracer transactionTracer,
            PageCursorTracerSupplier pageCursorTracerSupplier, LockTracer lockTracer, LeaseService leaseService )
    {
        this.config = config;
        this.statementLocksFactory = statementLocksFactory;
        this.constraintIndexCreator = constraintIndexCreator;
        this.transactionCommitProcess = transactionCommitProcess;
        this.eventListeners = eventListeners;
        this.transactionMonitor = transactionMonitor;
        this.databaseAvailabilityGuard = databaseAvailabilityGuard;
        this.storageEngine = storageEngine;
        this.globalProcedures = globalProcedures;
        this.transactionIdStore = transactionIdStore;
        this.cpuClockRef = cpuClockRef;
        this.heapAllocationRef = heapAllocationRef;
        this.accessCapability = accessCapability;
        this.tokenHolders = tokenHolders;
        this.tokenHoldersIdLookup = new TokenHoldersIdLookup( tokenHolders );
        this.namedDatabaseId = namedDatabaseId;
        this.indexingService = indexingService;
        this.labelScanStore = labelScanStore;
        this.indexStatisticsStore = indexStatisticsStore;
        this.databaseDependendies = databaseDependencies;
        this.versionContextSupplier = versionContextSupplier;
        this.clock = clock;
        this.collectionsFactorySupplier = collectionsFactorySupplier;
        this.constraintSemantics = constraintSemantics;
        this.schemaState = schemaState;
        this.transactionTracer = transactionTracer;
        this.pageCursorTracerSupplier = pageCursorTracerSupplier;
        this.lockTracer = lockTracer;
        this.leaseService = leaseService;
        this.factory = new KernelTransactionImplementationFactory( allTransactions );
        this.globalTxPool = new GlobalKernelTransactionPool( allTransactions, factory );
        this.localTxPool = new LocalKernelTransactionPool( globalTxPool, activeTransactionCounter, config );
        doBlockNewTransactions();
    }

    public KernelTransaction newInstance( KernelTransaction.Type type, LoginContext loginContext, ClientConnectionInfo clientInfo, long timeout )
    {
        assertCurrentThreadIsNotBlockingNewTransactions();
        SecurityContext securityContext = loginContext.authorize( tokenHoldersIdLookup, namedDatabaseId.name() );
        try
        {
            while ( !newTransactionsLock.readLock().tryLock( 1, TimeUnit.SECONDS ) )
            {
                assertRunning();
            }
            try
            {
                assertRunning();
                TransactionId lastCommittedTransaction = transactionIdStore.getLastCommittedTransaction();
                KernelTransactionImplementation tx = localTxPool.acquire();
                StatementLocks statementLocks = statementLocksFactory.newInstance();
                tx.initialize( lastCommittedTransaction.transactionId(), lastCommittedTransaction.commitTimestamp(),
                        statementLocks, type, securityContext, timeout, userTransactionIdCounter.incrementAndGet(), clientInfo );
                return tx;
            }
            finally
            {
                newTransactionsLock.readLock().unlock();
            }
        }
        catch ( InterruptedException ie )
        {
            Thread.interrupted();
            throw new TransactionFailureException( "Fail to start new transaction.", ie );
        }
    }

    /**
     * Give an approximate set of all transactions currently running.
     * This is not guaranteed to be exact, as transactions may stop and start while this set is gathered.
     *
     * @return the (approximate) set of open transactions.
     */
    public Set<KernelTransactionHandle> activeTransactions()
    {
        return allTransactions
            .stream()
            .map( this::createHandle )
            .filter( KernelTransactionHandle::isOpen )
            .collect( toSet() );
    }

    /**
     * Give an approximate set of all transactions currently executing. In contrast to {@link #activeTransactions}, this also includes transactions in the
     * closing state, e.g. committing or rolling back. This is not guaranteed to be exact, as transactions may stop and start while this set is gathered.
     *
     * @return the (approximate) set of executing transactions.
     */
    public Set<KernelTransactionHandle> executingTransactions()
    {
        return allTransactions
                .stream()
                .map( this::createHandle )
                .filter( h -> h.isOpen() || h.isClosing() )
                .collect( toSet() );
    }

    /**
     * Dispose of all pooled transactions. This is done on shutdown.
     */
    public void disposeAll()
    {
        terminateTransactions();
        localTxPool.close();
        globalTxPool.close();
    }

    public void terminateTransactions()
    {
        markAllTransactionsAsTerminated();
    }

    private void markAllTransactionsAsTerminated()
    {
        // we mark all transactions for termination since we want to make sure these transactions
        // won't be reused, ever. Each transaction has, among other things, a Locks.Client and we
        // certainly want to keep that from being reused from this point.
        allTransactions.forEach( tx -> tx.markForTermination( Status.Database.DatabaseUnavailable ) );
    }

    public boolean haveClosingTransaction()
    {
        return allTransactions.stream().anyMatch( KernelTransactionImplementation::isClosing );
    }

    @Override
    public void start()
    {
        stopped = false;
        unblockNewTransactions();
    }

    @Override
    public void stop()
    {
        blockNewTransactions();
        stopped = true;
    }

    @Override
    public void shutdown()
    {
        disposeAll();
    }

    @Override
    public IdController.ConditionSnapshot get()
    {
        return new KernelTransactionsSnapshot( activeTransactions() );
    }

    /**
     * Do not allow new transactions to start until {@link #unblockNewTransactions()} is called. Current thread have
     * responsibility of doing so.
     * <p>
     * Blocking call.
     */
    public void blockNewTransactions()
    {
        doBlockNewTransactions();
    }

    /**
     * This is private since it's called from the constructor.
     */
    private void doBlockNewTransactions()
    {
        newTransactionsLock.writeLock().lock();
    }

    /**
     * Allow new transactions to be started again if current thread is the one who called
     * {@link #blockNewTransactions()}.
     *
     * @throws IllegalStateException if current thread is not the one that called {@link #blockNewTransactions()}.
     */
    public void unblockNewTransactions()
    {
        if ( !newTransactionsLock.writeLock().isHeldByCurrentThread() )
        {
            throw new IllegalStateException( "This thread did not block transactions previously" );
        }
        newTransactionsLock.writeLock().unlock();
    }

    public int getNumberOfActiveTransactions()
    {
        return activeTransactionCounter.get();
    }

    /**
     * Create new handle for the given transaction.
     * <p>
     * <b>Note:</b> this method is package-private for testing <b>only</b>.
     *
     * @param tx transaction to wrap.
     * @return transaction handle.
     */
    KernelTransactionHandle createHandle( KernelTransactionImplementation tx )
    {
        return new KernelTransactionImplementationHandle( tx, clock );
    }

    private void assertRunning()
    {
        if ( databaseAvailabilityGuard.isShutdown() )
        {
            throw new DatabaseShutdownException();
        }
        if ( stopped )
        {
            throw new IllegalStateException( "Can't start new transaction with stopped " + getClass() );
        }
    }

    private void assertCurrentThreadIsNotBlockingNewTransactions()
    {
        if ( newTransactionsLock.isWriteLockedByCurrentThread() )
        {
            throw new IllegalStateException(
                    "Thread that is blocking new transactions from starting can't start new transaction" );
        }
    }

    private class KernelTransactionImplementationFactory implements Factory<KernelTransactionImplementation>
    {
        private final Set<KernelTransactionImplementation> transactions;

        KernelTransactionImplementationFactory( Set<KernelTransactionImplementation> transactions )
        {
            this.transactions = transactions;
        }

        @Override
        public KernelTransactionImplementation newInstance()
        {
            KernelTransactionImplementation tx =
                    new KernelTransactionImplementation( config, eventListeners,
                            constraintIndexCreator, globalProcedures,
                            transactionCommitProcess, transactionMonitor, localTxPool, clock, cpuClockRef, heapAllocationRef,
                            transactionTracer, lockTracer, pageCursorTracerSupplier, storageEngine, accessCapability,
                            versionContextSupplier, collectionsFactorySupplier, constraintSemantics,
                            schemaState, tokenHolders, indexingService, labelScanStore, indexStatisticsStore,
                            databaseDependendies, namedDatabaseId, leaseService, () -> stopped );
            this.transactions.add( tx );
            return tx;
        }
    }

    private static class GlobalKernelTransactionPool extends LinkedQueuePool<KernelTransactionImplementation>
    {
        private final Set<KernelTransactionImplementation> transactions;

        GlobalKernelTransactionPool( Set<KernelTransactionImplementation> transactions,
                Factory<KernelTransactionImplementation> factory )
        {
            super( 8, factory );
            this.transactions = transactions;
        }

        @Override
        protected void dispose( KernelTransactionImplementation tx )
        {
            transactions.remove( tx );
            tx.dispose();
            super.dispose( tx );
        }
    }

    private static class LocalKernelTransactionPool extends MarshlandPool<KernelTransactionImplementation>
    {
        private final AtomicInteger activeTransactionCounter;
        private volatile int maxNumberOfTransaction;

        LocalKernelTransactionPool( Pool<KernelTransactionImplementation> delegatePool, AtomicInteger activeTransactionCounter, Config config )
        {
            super( delegatePool );
            this.activeTransactionCounter = activeTransactionCounter;
            this.maxNumberOfTransaction = config.get( GraphDatabaseSettings.max_concurrent_transactions );
            config.addListener( GraphDatabaseSettings.max_concurrent_transactions,
                    ( oldValue, newValue ) -> maxNumberOfTransaction = newValue );
        }

        @Override
        public KernelTransactionImplementation acquire()
        {
            verifyTransactionsLimit();
            return super.acquire();
        }

        @Override
        public void release( KernelTransactionImplementation obj )
        {
            activeTransactionCounter.decrementAndGet();
            super.release( obj );
        }

        private void verifyTransactionsLimit()
        {
            int activeTransactions;
            do
            {
                activeTransactions = activeTransactionCounter.get();
                int localTransactionMaximum = maxNumberOfTransaction;
                if ( localTransactionMaximum != 0 && activeTransactions >= localTransactionMaximum )
                {
                    throw new MaximumTransactionLimitExceededException();
                }
            }
            while ( !activeTransactionCounter.weakCompareAndSetAcquire( activeTransactions, activeTransactions + 1 ) );
        }
    }
}
