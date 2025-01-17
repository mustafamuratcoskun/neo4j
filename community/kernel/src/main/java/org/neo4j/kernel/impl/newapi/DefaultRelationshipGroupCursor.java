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
package org.neo4j.kernel.impl.newapi;

import org.eclipse.collections.api.iterator.IntIterator;
import org.eclipse.collections.api.iterator.LongIterator;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

import org.neo4j.internal.kernel.api.RelationshipGroupCursor;
import org.neo4j.internal.kernel.api.RelationshipTraversalCursor;
import org.neo4j.storageengine.api.RelationshipDirection;
import org.neo4j.storageengine.api.StorageRelationshipGroupCursor;
import org.neo4j.storageengine.api.txstate.NodeState;
import org.neo4j.storageengine.api.txstate.RelationshipState;

import static org.neo4j.internal.kernel.api.TokenRead.ANY_RELATIONSHIP_TYPE;
import static org.neo4j.kernel.impl.newapi.Read.NO_ID;
import static org.neo4j.kernel.impl.newapi.RelationshipReferenceEncoding.encodeDenseSelection;
import static org.neo4j.kernel.impl.newapi.RelationshipReferenceEncoding.encodeNoIncoming;
import static org.neo4j.kernel.impl.newapi.RelationshipReferenceEncoding.encodeNoLoops;
import static org.neo4j.kernel.impl.newapi.RelationshipReferenceEncoding.encodeNoOutgoing;
import static org.neo4j.kernel.impl.newapi.RelationshipReferenceEncoding.encodeSelection;

class DefaultRelationshipGroupCursor extends TraceableCursor implements RelationshipGroupCursor
{
    private Read read;
    private final CursorPool<DefaultRelationshipGroupCursor> pool;

    private StorageRelationshipGroupCursor storeCursor;
    private boolean hasCheckedTxState;
    private final MutableIntSet txTypes = new IntHashSet();
    private IntIterator txTypeIterator;
    private int currentTypeAddedInTx = ANY_RELATIONSHIP_TYPE;
    private boolean nodeIsDense;

    DefaultRelationshipGroupCursor( CursorPool<DefaultRelationshipGroupCursor> pool, StorageRelationshipGroupCursor storeCursor )
    {
        this.pool = pool;
        this.storeCursor = storeCursor;
    }

    void init( long nodeReference, long reference, boolean nodeIsDense, Read read )
    {
        this.nodeIsDense = nodeIsDense;
        this.storeCursor.init( nodeReference, reference, nodeIsDense );
        this.txTypes.clear();
        this.txTypeIterator = null;
        this.currentTypeAddedInTx = ANY_RELATIONSHIP_TYPE;
        this.hasCheckedTxState = false;
        this.read = read;
    }

    @Override
    public Position suspend()
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void resume( Position position )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public boolean next()
    {
        //We need to check tx state if there are new types added
        //on the first call of next
        if ( !hasCheckedTxState )
        {
            checkTxStateForUpdates();
            hasCheckedTxState = true;
        }

        if ( !storeCursor.next() )
        {
            //We have now run out of groups from the store, however there may still
            //be new types that was added in the transaction that we haven't visited yet.
            boolean next = nextFromTxState();
            if ( next )
            {
                getTracer().onRelationshipGroup( type() );
            }
            return next;
        }

        markTypeAsSeen( type() );
        getTracer().onRelationshipGroup( type() );
        return true;
    }

    private boolean nextFromTxState()
    {
        if ( txTypeIterator == null && !txTypes.isEmpty() )
        {
            txTypeIterator = txTypes.intIterator();
            //here it may be tempting to do txTypes.clear()
            //however that will also clear the iterator
        }
        if ( txTypeIterator != null )
        {
            if ( txTypeIterator.hasNext() )
            {
                currentTypeAddedInTx = txTypeIterator.next();
                return true;
            }
            else
            {
                currentTypeAddedInTx = ANY_RELATIONSHIP_TYPE;
            }
        }
        return false;
    }

    /**
     * Marks the given type as already seen
     * @param type the type we have seen
     */
    private void markTypeAsSeen( int type )
    {
        txTypes.remove( type );
    }

    /**
     * Store all types that was added in the transaction for the current node
     */
    private void checkTxStateForUpdates()
    {
        if ( read.hasTxStateWithChanges() )
        {
            NodeState nodeState = read.txState().getNodeState( storeCursor.getOwningNode() );
            LongIterator addedRelationships = nodeState.getAddedRelationships();
            while ( addedRelationships.hasNext() )
            {
                RelationshipState relationshipState = read.txState().getRelationshipState( addedRelationships.next() );
                relationshipState.accept( ( relationshipId, typeId, startNodeId, endNodeId ) -> txTypes.add( typeId ) );
            }
        }
    }

    @Override
    public void closeInternal()
    {
        if ( !isClosed() )
        {
            read = null;
            storeCursor.reset();

            if ( pool != null )
            {
                pool.accept( this );
            }
        }
    }

    @Override
    public int type()
    {
        return currentTypeAddedInTx != NO_ID ? currentTypeAddedInTx : storeCursor.type();
    }

    @Override
    public int outgoingCount()
    {
        int count = currentTypeAddedInTx != NO_ID ? 0 : storeCursor.outgoingCount();
        return read.hasTxStateWithChanges()
               ? read.txState().getNodeState( storeCursor.getOwningNode() )
                       .augmentDegree( RelationshipDirection.OUTGOING, count, type() ) : count;
    }

    @Override
    public int incomingCount()
    {
        int count = currentTypeAddedInTx != NO_ID ? 0 : storeCursor.incomingCount();
        return read.hasTxStateWithChanges()
               ? read.txState().getNodeState( storeCursor.getOwningNode() )
                       .augmentDegree( RelationshipDirection.INCOMING, count, type() ) : count;
    }

    @Override
    public int loopCount()
    {
        int count = currentTypeAddedInTx != NO_ID ? 0 : storeCursor.loopCount();
        return read.hasTxStateWithChanges()
               ? read.txState().getNodeState( storeCursor.getOwningNode() )
                       .augmentDegree( RelationshipDirection.LOOP, count, type() ) : count;

    }

    @Override
    public void outgoing( RelationshipTraversalCursor cursor )
    {
        ((DefaultRelationshipTraversalCursor) cursor).init( storeCursor.getOwningNode(), outgoingReferenceWithoutFlags(),
                type(), RelationshipDirection.OUTGOING, nodeIsDense, read );
    }

    @Override
    public void incoming( RelationshipTraversalCursor cursor )
    {
        ((DefaultRelationshipTraversalCursor) cursor).init( storeCursor.getOwningNode(), incomingReferenceWithoutFlags(),
                type(), RelationshipDirection.INCOMING, nodeIsDense, read );
    }

    @Override
    public void loops( RelationshipTraversalCursor cursor )
    {
        ((DefaultRelationshipTraversalCursor) cursor).init( storeCursor.getOwningNode(), loopsReferenceWithoutFlags(),
                type(), RelationshipDirection.LOOP, nodeIsDense, read );
    }

    @Override
    public long outgoingReference()
    {
        long reference = outgoingReferenceWithoutFlags();
        return reference == NO_ID ? encodeNoOutgoing( type() ) : encodeSelectionToReference( reference );
    }

    @Override
    public long incomingReference()
    {
        long reference = incomingReferenceWithoutFlags();
        return reference == NO_ID ? encodeNoIncoming( type() ) : encodeSelectionToReference( reference );
    }

    @Override
    public long loopsReference()
    {
        long reference = loopsReferenceWithoutFlags();
        return reference == NO_ID ? encodeNoLoops( type() ) : encodeSelectionToReference( reference );
    }

    private long encodeSelectionToReference( long reference )
    {
        return nodeIsDense ? encodeDenseSelection( reference ) : encodeSelection( reference );
    }

    private long outgoingReferenceWithoutFlags()
    {
        return relationshipReferenceWithoutFlags( storeCursor.outgoingReference() );
    }

    private long incomingReferenceWithoutFlags()
    {
        return relationshipReferenceWithoutFlags( storeCursor.incomingReference() );
    }

    private long loopsReferenceWithoutFlags()
    {
        return relationshipReferenceWithoutFlags( storeCursor.loopsReference() );
    }

    private long relationshipReferenceWithoutFlags( long storeReference )
    {
        return currentTypeAddedInTx != NO_ID ? NO_ID : storeReference;
    }

    @Override
    public boolean isClosed()
    {
        return read == null;
    }

    @Override
    public String toString()
    {
        if ( isClosed() )
        {
            return "RelationshipGroupCursor[closed state]";
        }
        else
        {
            return "RelationshipGroupCursor[" + storeCursor.toString() + "]";
        }
    }

    public void release()
    {
        storeCursor.close();
    }
}
