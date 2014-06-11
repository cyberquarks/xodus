/**
 * Copyright 2010 - 2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.env;

import jetbrains.exodus.ExodusException;
import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator;
import jetbrains.exodus.core.dataStructures.hash.HashMap;
import jetbrains.exodus.log.Loggable;
import jetbrains.exodus.tree.ITree;
import jetbrains.exodus.tree.ITreeMutable;
import jetbrains.exodus.tree.TreeMetaInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class TransactionImpl implements Transaction {

    @NotNull
    private final EnvironmentImpl env;
    @Nullable
    private final Thread creatingThread;
    @NotNull
    private MetaTree metaTree;
    @NotNull
    private final Map<String, ITree> immutableTrees;
    @NotNull
    private final Map<String, ITreeMutable> mutableTrees;
    @NotNull
    private final Map<String, ITree> removedStores;
    @NotNull
    private final Map<String, TreeMetaInfo> createdStores;
    @Nullable
    private Runnable beginHook;
    @Nullable
    private Runnable commitHook;
    @Nullable
    private final Throwable trace;
    private final long created;

    TransactionImpl(@NotNull final EnvironmentImpl env, @Nullable final Thread creatingThread,
                    @Nullable final Runnable beginHook, final boolean cloneMeta) {
        this.env = env;
        this.creatingThread = creatingThread;
        immutableTrees = new HashMap<String, ITree>();
        mutableTrees = new TreeMap<String, ITreeMutable>();
        removedStores = new HashMapDecorator<String, ITree>();
        createdStores = new HashMapDecorator<String, TreeMetaInfo>();
        this.beginHook = new Runnable() {
            @Override
            public void run() {
                final MetaTree initialMetaTree = env.getMetaTreeUnsafe();
                metaTree = cloneMeta ? initialMetaTree.getCloneWithMeta() : initialMetaTree.getClone();
                env.registerTransaction(TransactionImpl.this);
                if (beginHook != null) {
                    beginHook.run();
                }
            }
        };
        trace = env.transactionTimeout() > 0 ? new Throwable() : null;
        created = System.currentTimeMillis();
        holdNewestSnapshot();
    }

    /**
     * Constructor for creating new snapshot transaction.
     */
    protected TransactionImpl(@NotNull final TransactionImpl origin) {
        env = origin.env;
        metaTree = origin.metaTree;
        commitHook = origin.commitHook;
        creatingThread = origin.creatingThread;
        immutableTrees = new HashMap<String, ITree>();
        mutableTrees = new HashMap<String, ITreeMutable>();
        removedStores = new HashMapDecorator<String, ITree>();
        createdStores = new HashMapDecorator<String, TreeMetaInfo>();
        trace = env.transactionTimeout() > 0 ? new Throwable() : null;
        created = System.currentTimeMillis();
        env.registerTransaction(this);
    }

    @Override
    public void abort() {
        doRevert();
        env.finishTransaction(this);
    }

    @Override
    public boolean commit() {
        return env.commitTransaction(this, false);
    }

    @Override
    public boolean flush() {
        return env.flushTransaction(this, false);
    }

    @Override
    public void revert() {
        doRevert();
        final long oldRoot = metaTree.root;
        holdNewestSnapshot();
        if (!checkVersion(oldRoot)) {
            env.runTransactionSafeTasks();
        }
        if (!env.isRegistered(this)) {
            throw new ExodusException("Transaction should remain registered after revert");
        }
    }

    @Override
    public Transaction getSnapshot() {
        return new ReadonlyTransaction(this);
    }

    @Override
    @NotNull
    public Environment getEnvironment() {
        return env;
    }

    @Override
    public void setCommitHook(@Nullable final Runnable hook) {
        commitHook = hook;
    }

    public boolean forceFlush() {
        return env.flushTransaction(this, true);
    }

    @Nullable
    public Throwable getTrace() {
        return trace;
    }

    public long getCreated() {
        return created;
    }

    @NotNull
    public StoreImpl openStoreByStructureId(final long structureId) {
        final String storeName = metaTree.getStoreNameByStructureId(structureId, env);
        return storeName == null ?
                new StoreEmpty(env, structureId) :
                env.openStoreImpl(storeName, StoreConfig.USE_EXISTING, this, env.getCurrentMetaInfo(storeName, this));
    }

    boolean isIdempotent() {
        return mutableTrees.isEmpty() && removedStores.isEmpty() && createdStores.isEmpty();
    }

    void storeRemoved(@NotNull final String storeName, @NotNull final ITree tree) {
        removedStores.put(storeName, tree);
        immutableTrees.remove(storeName);
        mutableTrees.remove(storeName);
    }

    boolean isStoreNew(@NotNull final String name) {
        return createdStores.containsKey(name);
    }

    void storeCreated(@NotNull final String storeName, @NotNull final TreeMetaInfo metaInfo) {
        createdStores.put(storeName, metaInfo);
    }

    boolean checkVersion(final long root) {
        return metaTree.root == root;
    }

    Iterable<Loggable>[] doCommit(@NotNull final MetaTree[] out) {
        final Set<Map.Entry<String, ITreeMutable>> entries = mutableTrees.entrySet();
        final Set<Map.Entry<String, ITree>> removedEntries = removedStores.entrySet();
        final int size = entries.size() + removedEntries.size();
        //noinspection unchecked
        final Iterable<Loggable>[] expiredLoggables = new Iterable[size + 1];
        int i = 0;
        final ITreeMutable metaTreeMutable = metaTree.tree.getMutableCopy();
        for (final Map.Entry<String, ITree> entry : removedEntries) {
            final ITree tree = entry.getValue();
            MetaTree.removeStore(metaTreeMutable, entry.getKey(), tree.getStructureId());
            expiredLoggables[i++] = TreeMetaInfo.getTreeLoggables(tree);
        }
        removedStores.clear();
        for (final Map.Entry<String, TreeMetaInfo> entry : createdStores.entrySet()) {
            MetaTree.addStore(metaTreeMutable, entry.getKey(), entry.getValue());
        }
        createdStores.clear();
        final Collection<Loggable> last;
        for (final Map.Entry<String, ITreeMutable> entry : entries) {
            final ITreeMutable treeMutable = entry.getValue();
            expiredLoggables[i++] = treeMutable.getExpiredLoggables();
            MetaTree.saveTree(metaTreeMutable, treeMutable);
        }
        immutableTrees.clear();
        mutableTrees.clear();
        expiredLoggables[i] = last = metaTreeMutable.getExpiredLoggables();
        out[0] = MetaTree.saveMetaTree(metaTreeMutable, env, last);
        return expiredLoggables;
    }

    void setMetaTree(@NotNull final MetaTree metaTree) {
        this.metaTree = metaTree;
    }

    void executeCommitHook() {
        if (commitHook != null) {
            commitHook.run();
        }
    }

    @Nullable
    TreeMetaInfo getMetaInfo(@NotNull final String storeName) {
        return metaTree.getMetaInfo(storeName, env);
    }

    @NotNull
    ITreeMutable getMutableTree(@NotNull final StoreImpl store) {
        if (creatingThread != null && !creatingThread.equals(Thread.currentThread())) {
            throw new ExodusException("Can't create mutable tree in a thread different from the one which transaction was created in.");
        }
        final String name = store.getName();
        ITreeMutable result = mutableTrees.get(name);
        if (result == null) {
            result = getImmutableTree(store).getMutableCopy();
            mutableTrees.put(name, result);
        }
        return result;
    }

    @NotNull
    Map<String, ITreeMutable> getMutableTrees() {
        return mutableTrees;
    }

    /**
     * @param store opened store.
     * @return whether a mutable tree is created for specified store.
     */
    boolean hasTreeMutable(@NotNull final StoreImpl store) {
        return mutableTrees.containsKey(store.getName());
    }

    void removeTreeMutable(@NotNull final StoreImpl store) {
        mutableTrees.remove(store.getName());
    }

    @Nullable
    Thread getCreatingThread() {
        return creatingThread;
    }

    @NotNull
    MetaTree getMetaTree() {
        return metaTree;
    }

    long getRoot() {
        return metaTree.root;
    }

    List<String> getAllStoreNames() {
        // TODO: optimize
        List<String> result = metaTree.getAllStoreNames();
        if (createdStores.isEmpty()) return result;
        if (result.isEmpty()) {
            result = new ArrayList<String>();
        }
        result.addAll(createdStores.keySet());
        Collections.sort(result);
        return result;
    }

    @NotNull
    ITree getTree(@NotNull final StoreImpl store) {
        final String name = store.getName();
        final ITreeMutable result = mutableTrees.get(name);
        if (result == null) {
            return getImmutableTree(store);
        }
        return result;
    }

    private void holdNewestSnapshot() {
        env.getMetaTree(beginHook);
    }

    @NotNull
    private ITree getImmutableTree(@NotNull final StoreImpl store) {
        final String name = store.getName();
        ITree result = immutableTrees.get(name);
        if (result == null) {
            result = store.openImmutableTree(metaTree);
            immutableTrees.put(name, result);
        }
        return result;
    }

    private void doRevert() {
        immutableTrees.clear();
        mutableTrees.clear();
        removedStores.clear();
        createdStores.clear();
    }
}
