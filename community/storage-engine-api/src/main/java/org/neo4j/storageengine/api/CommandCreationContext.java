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
package org.neo4j.storageengine.api;

import org.eclipse.collections.api.iterator.LongIterator;

/**
 * A context which {@link StorageEngine} hands out to clients and which gets passed back in
 * to calls about creating commands. One of its purposes is to reserve and release ids. E.g. internal nodes and relationship references
 * are publicly exposed even before committed, which means that they will have to be reserved before committing.
 */
public interface CommandCreationContext extends AutoCloseable
{
    /**
     * Reserves a node id for future use to store a node. The reason for it being exposed here is that
     * internal ids of nodes and relationships are publicly accessible all the way out to the user.
     * This will likely change in the future though.
     *
     * @return a reserved node id for future use.
     */
    long reserveNode();

    /**
     * Reserves a relationship id for future use to store a relationship. The reason for it being exposed here is that
     * internal ids of nodes and relationships are publicly accessible all the way out to the user.
     * This will likely change in the future though.
     *
     * @return a reserved relationship id for future use.
     */
    long reserveRelationship();

    /**
     * Reserves an id for a schema record, be it for a constraint or an index, for future use to store a schema record. The reason for it being exposed here
     * is that the record ids are used for producing unique names for indexes, which we would like to do before we get to the prepare phase
     *
     * @return a reserved schema record id for future use.
     */
    long reserveSchema();

    /**
     * Releases previously {@link #reserveNode() reserved} node ids if it turns out to not actually being used,
     * for example in the event of a transaction rolling back.
     *
     * @param ids reserved node ids to release.
     */
    void releaseNodes( LongIterator ids );

    /**
     * Releases previously {@link #reserveRelationship() reserved} relationship ids if it turns out to not
     * actually being used, for example in the event of a transaction rolling back.
     *
     * @param ids reserved relationship id to release.
     */
    void releaseRelationships( LongIterator ids );

    /**
     * Releases a previously {@link #reserveSchema() reserved} schema record id if it turns out to not actaually be needed.
     * For example, in the even of a transaction rollback.
     *
     * @param id reserved schema record id to release.
     */
    void releaseSchema( long id );

    /**
     * Reserves a label token id.
     * @return a unique label token id.
     */
    int reserveLabelTokenId();

    /**
     * Reserves a property key token id.
     * @return a unique property key token id.
     */
    int reservePropertyKeyTokenId();

    /**
     * Reserves a relationship type token id.
     * @return a unique relationship type token id.
     */
    int reserveRelationshipTypeTokenId();

    @Override
    void close();
}
