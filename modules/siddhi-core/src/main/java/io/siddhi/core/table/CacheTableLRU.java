/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.siddhi.core.table;

import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.event.ComplexEvent;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.state.StateEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.table.holder.IndexEventHolder;
import io.siddhi.core.util.collection.AddingStreamEventExtractor;
import io.siddhi.core.util.collection.operator.CompiledCondition;
import io.siddhi.core.util.collection.operator.Operator;
import io.siddhi.query.api.definition.Attribute;
import io.siddhi.query.api.definition.TableDefinition;
import org.apache.log4j.Logger;

import java.util.Arrays;
import java.util.Set;

import static io.siddhi.core.util.SiddhiConstants.CACHE_TABLE_TIMESTAMP_ADDED;
import static io.siddhi.core.util.SiddhiConstants.CACHE_TABLE_TIMESTAMP_LRU;
import static io.siddhi.core.util.cache.CacheUtils.getPrimaryKey;
import static io.siddhi.core.util.cache.CacheUtils.getPrimaryKeyFromMatchingEvent;

/**
 * cache table with LRU entry removal
 */
public class CacheTableLRU extends CacheTable {
    private static final Logger log = Logger.getLogger(CacheTableLRU.class);

    @Override
    public StreamEvent find(CompiledCondition compiledCondition, StateEvent matchingEvent) {
        readWriteLock.readLock().lock();
        TableState state = stateHolder.getState();
        try {
            StreamEvent foundEvent = ((Operator) compiledCondition).find(matchingEvent, state.getEventHolder(),
                    tableStreamEventCloner);
            String primaryKey;

            if (stateHolder.getState().getEventHolder() instanceof IndexEventHolder) {
                IndexEventHolder indexEventHolder = (IndexEventHolder) stateHolder.getState().getEventHolder();
                primaryKey = getPrimaryKey(compiledCondition, matchingEvent);
                StreamEvent usedEvent = indexEventHolder.getEvent(primaryKey);
                if (usedEvent != null) {
                    usedEvent.getOutputData()[policyAttributePosition] = System.currentTimeMillis();
                }
            }
            return foundEvent;
        } finally {
            stateHolder.returnState(state);
            readWriteLock.readLock().unlock();
        }
    }

    @Override
    public boolean contains(StateEvent matchingEvent, CompiledCondition compiledCondition) {
        readWriteLock.readLock().lock();
        TableState state = stateHolder.getState();
        try {
            if (((Operator) compiledCondition).contains(matchingEvent, state.getEventHolder())) {
                String primaryKey;

                if (stateHolder.getState().getEventHolder() instanceof IndexEventHolder) {
                    IndexEventHolder indexEventHolder = (IndexEventHolder) stateHolder.getState().getEventHolder();
                    primaryKey = getPrimaryKey(compiledCondition, matchingEvent);
                    if (primaryKey == null || primaryKey.equals("")) {
                        primaryKey = getPrimaryKeyFromMatchingEvent(matchingEvent);
                    }
                    StreamEvent usedEvent = indexEventHolder.getEvent(primaryKey);
                    if (usedEvent != null) {
                        usedEvent.getOutputData()[policyAttributePosition] = System.currentTimeMillis();
                    }
                }
                return true;
            } else {
                return false;
            }
        } finally {
            stateHolder.returnState(state);
            readWriteLock.readLock().unlock();
        }
    }

    @Override
    public void update(ComplexEventChunk<StateEvent> updatingEventChunk, CompiledCondition compiledCondition,
                       CompiledUpdateSet compiledUpdateSet) {
        readWriteLock.writeLock().lock();
        TableState state = stateHolder.getState();
        try {
            String primaryKey;
            if (stateHolder.getState().getEventHolder() instanceof IndexEventHolder) {
                updatingEventChunk.reset();
                while (updatingEventChunk.hasNext()) {
                    StateEvent matchingEvent = updatingEventChunk.next();
                    IndexEventHolder indexEventHolder = (IndexEventHolder) stateHolder.getState().getEventHolder();
                    primaryKey = getPrimaryKey(compiledCondition, matchingEvent);
                    StreamEvent usedEvent = indexEventHolder.getEvent(primaryKey);
                    if (usedEvent != null) {
                        usedEvent.getOutputData()[policyAttributePosition] = System.currentTimeMillis();
                    }
                }
            }
            ((Operator) compiledCondition).update(updatingEventChunk, state.getEventHolder(),
                    (InMemoryCompiledUpdateSet) compiledUpdateSet);
        } finally {
            stateHolder.returnState(state);
            readWriteLock.writeLock().unlock();
        }
    }

    @Override
    public void updateOrAddAndTrimUptoMaxSize(ComplexEventChunk<StateEvent> updateOrAddingEventChunk,
                                              CompiledCondition compiledCondition,
                                              CompiledUpdateSet compiledUpdateSet,
                                              AddingStreamEventExtractor addingStreamEventExtractor, int maxTableSize) {
        ComplexEventChunk<StateEvent> updateOrAddingEventChunkForCache = new ComplexEventChunk<>(true);
        updateOrAddingEventChunk.reset();
        while (updateOrAddingEventChunk.hasNext()) {
            StateEvent event = updateOrAddingEventChunk.next();
            updateOrAddingEventChunkForCache.add((StateEvent) generateEventWithRequiredFields(event, siddhiAppContext,
                    cacheExpiryEnabled));
        }
        readWriteLock.writeLock().lock();
        TableState state = stateHolder.getState();
        try {
            ComplexEventChunk<StreamEvent> failedEvents = ((Operator) compiledCondition).tryUpdate(
                    updateOrAddingEventChunkForCache,
                    state.getEventHolder(),
                    (InMemoryCompiledUpdateSet) compiledUpdateSet,
                    addingStreamEventExtractor);
            if (failedEvents != null) {
                this.addAndTrimUptoMaxSize(failedEvents);
            }
            while (this.size() > maxTableSize) {
                this.deleteOneEntryUsingCachePolicy();
            }
        } finally {
            stateHolder.returnState(state);
            readWriteLock.writeLock().unlock();
        }
    }


    @Override
    void addRequiredFieldsToCacheTableDefinition(TableDefinition cacheTableDefinition, boolean cacheExpiryEnabled) {
        if (cacheExpiryEnabled) {
            cacheTableDefinition.attribute(CACHE_TABLE_TIMESTAMP_ADDED, Attribute.Type.LONG);
            cacheTableDefinition.attribute(CACHE_TABLE_TIMESTAMP_LRU, Attribute.Type.LONG);
            expiryAttributePosition = cacheTableDefinition.getAttributeList().size() - 2;
        } else {
            cacheTableDefinition.attribute(CACHE_TABLE_TIMESTAMP_LRU, Attribute.Type.LONG);
        }
        policyAttributePosition = cacheTableDefinition.getAttributeList().size() - 1;
        numColumns = policyAttributePosition + 1;
    }

    @Override
    public void deleteOneEntryUsingCachePolicy() {
        try {
            IndexEventHolder indexEventHolder = (IndexEventHolder) stateHolder.getState().getEventHolder();
            Set<Object> keys = indexEventHolder.getAllPrimaryKeyValues();
            long minTimestamp = Long.MAX_VALUE;
            Object keyOfMinTimestamp = null;
            for (Object key: keys) {
                Object[] data = indexEventHolder.getEvent(key).getOutputData();
                long timestamp = (long) data[policyAttributePosition];
                if (timestamp < minTimestamp) {
                    minTimestamp = timestamp;
                    keyOfMinTimestamp = key;
                }
            }
            indexEventHolder.deleteEvent(keyOfMinTimestamp);
        } catch (ClassCastException e) {
            log.error(siddhiAppContext + ": " + e.getMessage());
        }
    }

    @Override
    public void deleteEntriesUsingCachePolicy(int numRowsToDelete) {
        try {
            IndexEventHolder indexEventHolder = (IndexEventHolder) stateHolder.getState().getEventHolder();
            Set<Object> keys = indexEventHolder.getAllPrimaryKeyValues();
            long[] minTimestampArray = new long[numRowsToDelete];
            Arrays.fill(minTimestampArray, Long.MAX_VALUE);
            Object[] keyOfMinTimestampArray = new Object[numRowsToDelete];

            for (Object key: keys) {
                Object[] data = indexEventHolder.getEvent(key).getOutputData();
                long timestamp = (long) data[policyAttributePosition];
                for (int i = 0; i < numRowsToDelete; i++) {
                    if (timestamp < minTimestampArray[i]) {
                        minTimestampArray[i] = timestamp;
                        keyOfMinTimestampArray[i] = key;
                    }
                }
            }
            for (Object deleteKey: keyOfMinTimestampArray) {
                if (deleteKey != null) {
                    indexEventHolder.deleteEvent(deleteKey);
                }
            }
        } catch (ClassCastException e) {
            log.error(siddhiAppContext + ": " + e.getMessage());
        }
    }

    @Override
    protected StreamEvent addRequiredFields(ComplexEvent event, SiddhiAppContext siddhiAppContext,
                                            boolean cacheExpiryEnabled) {
        Object[] outputDataForCache;
        Object[] outputData = event.getOutputData();
        if (cacheExpiryEnabled) {
            outputDataForCache = new Object[numColumns];
            outputDataForCache[expiryAttributePosition] = outputDataForCache[policyAttributePosition] =
                            siddhiAppContext.getTimestampGenerator().currentTime();
        } else {
            outputDataForCache = new Object[numColumns];
            outputDataForCache[policyAttributePosition] =
                    siddhiAppContext.getTimestampGenerator().currentTime();
        }
        System.arraycopy(outputData, 0 , outputDataForCache, 0, outputData.length);
        StreamEvent eventForCache = new StreamEvent(0, 0, outputDataForCache.length);
        eventForCache.setOutputData(outputDataForCache);
        return eventForCache;
    }
}
