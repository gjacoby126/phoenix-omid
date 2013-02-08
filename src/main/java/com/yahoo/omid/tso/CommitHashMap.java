/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.tso;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.netty.util.internal.ConcurrentHashMap;

/**
 * A hash map that uses byte[] for the key rather than longs.
 * 
 * Change it to lazyly clean the old entries, i.e., upon a hit This would reduce
 * the mem access benefiting from cache locality
 * 
 * @author maysam
 */

class CommitHashMap {

    private final int size;
    private long largestDeletedTimestamp;
    private final long[] startCommitMapping;
    private final long[] rowsCommitMapping;

    /**
     * Constructs a new, empty hashtable with a default size of 1000
     */
    public CommitHashMap() {
        this(1000);
    }

    /**
     * Constructs a new, empty hashtable with the specified initial capacity and
     * default load factor, which is <code>0.75</code>.
     * 
     * @param initialCapacity
     *            the initial capacity of the hashtable.
     * @throws IllegalArgumentException
     *             if the initial capacity is less than zero.
     */
    public CommitHashMap(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Illegal size: " + size);
        }

        this.size = size;
        this.startCommitMapping = new long[size * 2];
        this.rowsCommitMapping = new long[size * 2];
    }

    private int index(long hash) {
        return (int) (Math.abs(hash) % size);
    }

    public long getLatestWriteForRow(long hash) {
        int index = index(hash);
        return rowsCommitMapping[index];
    }

    public void putLatestWriteForRow(long hash, long commitTimestamp) {
        int index = index(hash);
        long oldCommitTS = rowsCommitMapping[index];

        if (oldCommitTS == commitTimestamp)
            return;

        rowsCommitMapping[index] = commitTimestamp;
        largestDeletedTimestamp = Math
                .max(oldCommitTS, largestDeletedTimestamp);
    }

    public long getCommittedTimestamp(long startTimestamp) {
        int indexStart = 2 * index(startTimestamp);
        int indexCommit = indexStart + 1;
        
        if (startCommitMapping[indexStart] == startTimestamp) {
            return startCommitMapping[indexCommit];
        } else {
            return 0;
        }
    }

    public void setCommittedTimestamp(long startTimestamp, long commitTimestamp) {
        int indexStart = 2 * index(startTimestamp);
        int indexCommit = indexStart + 1;

        long oldCommitTS = startCommitMapping[indexCommit];
        if (oldCommitTS == commitTimestamp)
            return;

        startCommitMapping[indexStart] = startTimestamp;
        startCommitMapping[indexCommit] = commitTimestamp;
        largestDeletedTimestamp = Math
                .max(oldCommitTS, largestDeletedTimestamp);
    }

    // set of half aborted transactions
    // TODO: set the initial capacity in a smarter way
    Set<AbortedTransaction> halfAborted = Collections
            .newSetFromMap(new ConcurrentHashMap<AbortedTransaction, Boolean>(
                    10000));

    private AtomicLong abortedSnapshot = new AtomicLong();

    long getAndIncrementAbortedSnapshot() {
        return abortedSnapshot.getAndIncrement();
    }

    // add a new half aborted transaction
    void setHalfAborted(long startTimestamp) {
        halfAborted.add(new AbortedTransaction(startTimestamp, abortedSnapshot
                .get()));
    }

    // call when a half aborted transaction is fully aborted
    void setFullAborted(long startTimestamp) {
        halfAborted.remove(new AbortedTransaction(startTimestamp, 0));
    }

    // query to see if a transaction is half aborted
    boolean isHalfAborted(long startTimestamp) {
        return halfAborted.contains(new AbortedTransaction(startTimestamp, 0));
    }

    public long getLargestDeletedTimestamp() {
        return largestDeletedTimestamp;
    }
}
