/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.wildfly.clustering.server.infinispan.registry;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.infinispan.Cache;
import org.infinispan.commons.CacheException;
import org.infinispan.commons.util.concurrent.CompletableFutures;
import org.infinispan.context.Flag;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.distribution.ch.KeyPartitioner;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.Listener.Observation;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryModified;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryRemoved;
import org.infinispan.notifications.cachelistener.annotation.TopologyChanged;
import org.infinispan.notifications.cachelistener.event.CacheEntryEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryRemovedEvent;
import org.infinispan.notifications.cachelistener.event.Event;
import org.infinispan.notifications.cachelistener.event.TopologyChangedEvent;
import org.infinispan.remoting.transport.Address;
import org.wildfly.clustering.Registration;
import org.wildfly.clustering.context.DefaultExecutorService;
import org.wildfly.clustering.context.ExecutorServiceFactory;
import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.ee.Batcher;
import org.wildfly.clustering.ee.Invoker;
import org.wildfly.clustering.ee.infinispan.retry.RetryingInvoker;
import org.wildfly.clustering.group.Node;
import org.wildfly.clustering.infinispan.distribution.ConsistentHashLocality;
import org.wildfly.clustering.infinispan.distribution.Locality;
import org.wildfly.clustering.infinispan.listener.KeyFilter;
import org.wildfly.clustering.registry.Registry;
import org.wildfly.clustering.registry.RegistryListener;
import org.wildfly.clustering.server.group.Group;
import org.wildfly.clustering.server.infinispan.ClusteringServerLogger;
import org.wildfly.common.function.ExceptionRunnable;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * Clustered {@link Registry} backed by an Infinispan cache.
 * @author Paul Ferraro
 * @param <K> key type
 * @param <V> value type
 */
@Listener(observation = Observation.POST)
public class CacheRegistry<K, V> implements Registry<K, V>, ExceptionRunnable<CacheException>, Function<RegistryListener<K, V>, ExecutorService> {

    private final Map<RegistryListener<K, V>, ExecutorService> listeners = new ConcurrentHashMap<>();
    private final Cache<Address, Map.Entry<K, V>> cache;
    private final Batcher<? extends Batch> batcher;
    private final Group<Address> group;
    private final Runnable closeTask;
    private final Map.Entry<K, V> entry;
    private final Invoker invoker;
    private final KeyPartitioner partitioner;
    private final Executor executor;

    @SuppressWarnings("deprecation")
    public CacheRegistry(CacheRegistryConfiguration<K, V> config, Map.Entry<K, V> entry, Runnable closeTask) {
        this.cache = config.getCache();
        this.batcher = config.getBatcher();
        this.group = config.getGroup();
        this.closeTask = closeTask;
        this.executor = config.getBlockingManager().asExecutor(this.getClass().getName());
        this.partitioner = this.cache.getAdvancedCache().getComponentRegistry().getLocalComponent(KeyPartitioner.class);
        this.entry = new AbstractMap.SimpleImmutableEntry<>(entry);
        this.invoker = new RetryingInvoker(this.cache);
        this.invoker.invoke(this);
        this.cache.addListener(this, new KeyFilter<>(Address.class), null);
    }

    @Override
    public void run() {
        try (Batch batch = this.batcher.createBatch()) {
            this.cache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES).put(this.group.getAddress(this.group.getLocalMember()), this.entry);
        }
    }

    @Override
    public void close() {
        this.cache.removeListener(this);
        try (Batch batch = this.batcher.createBatch()) {
            // If this remove fails, the entry will be auto-removed on topology change by the new primary owner
            this.cache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES, Flag.FAIL_SILENTLY).remove(this.group.getAddress(this.group.getLocalMember()));
        } catch (CacheException e) {
            ClusteringServerLogger.ROOT_LOGGER.warn(e.getLocalizedMessage(), e);
        } finally {
            // Cleanup any unregistered listeners
            for (ExecutorService executor : this.listeners.values()) {
                this.shutdown(executor);
            }
            this.listeners.clear();
            this.closeTask.run();
        }
    }

    @Override
    public Registration register(RegistryListener<K, V> listener) {
        this.listeners.computeIfAbsent(listener, this);
        return () -> this.unregister(listener);
    }

    @Override
    public ExecutorService apply(RegistryListener<K, V> listener) {
        return new DefaultExecutorService(listener.getClass(), ExecutorServiceFactory.SINGLE_THREAD);
    }

    private void unregister(RegistryListener<K, V> listener) {
        ExecutorService executor = this.listeners.remove(listener);
        if (executor != null) {
            this.shutdown(executor);
        }
    }

    @Override
    public org.wildfly.clustering.group.Group getGroup() {
        return this.group;
    }

    @Override
    public Map<K, V> getEntries() {
        Set<Address> addresses = new TreeSet<>();
        for (Node member : this.group.getMembership().getMembers()) {
            addresses.add(this.group.getAddress(member));
        }
        Map<K, V> result = new HashMap<>();
        for (Map.Entry<K, V> entry : this.cache.getAdvancedCache().getAll(addresses).values()) {
            if (entry != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @Override
    public Map.Entry<K, V> getEntry(Node node) {
        Address address = this.group.getAddress(node);
        return this.cache.get(address);
    }

    @TopologyChanged
    public CompletionStage<Void> topologyChanged(TopologyChangedEvent<Address, Map.Entry<K, V>> event) {
        ConsistentHash previousHash = event.getWriteConsistentHashAtStart();
        List<Address> previousMembers = previousHash.getMembers();
        ConsistentHash hash = event.getWriteConsistentHashAtEnd();
        List<Address> members = hash.getMembers();

        if (!members.equals(previousMembers)) {
            Cache<Address, Map.Entry<K, V>> cache = event.getCache().getAdvancedCache().withFlags(Flag.FORCE_SYNCHRONOUS);
            EmbeddedCacheManager container = cache.getCacheManager();
            Address localAddress = container.getAddress();

            // Determine which nodes have left the cache view
            Set<Address> leftMembers = new HashSet<>(previousMembers);
            leftMembers.removeAll(members);

            if (!leftMembers.isEmpty()) {
                Locality locality = new ConsistentHashLocality(this.partitioner, hash, localAddress);
                // We're only interested in the entries for which we are the primary owner
                Iterator<Address> addresses = leftMembers.iterator();
                while (addresses.hasNext()) {
                    if (!locality.isLocal(addresses.next())) {
                        addresses.remove();
                    }
                }
            }

            // If this is a merge after cluster split: re-populate the cache registry with lost registry entries
            boolean restoreLocalEntry = !previousMembers.contains(localAddress);

            if (!leftMembers.isEmpty() || restoreLocalEntry) {
                this.executor.execute(() -> {
                    if (!leftMembers.isEmpty()) {
                        Map<K, V> removed = new HashMap<>();
                        try {
                            for (Address leftMember: leftMembers) {
                                Map.Entry<K, V> old = cache.remove(leftMember);
                                if (old != null) {
                                    removed.put(old.getKey(), old.getValue());
                                }
                            }
                        } catch (CacheException e) {
                            ClusteringServerLogger.ROOT_LOGGER.registryPurgeFailed(e, container.toString(), cache.getName(), leftMembers);
                        }
                        if (!removed.isEmpty()) {
                            this.notifyListeners(Event.Type.CACHE_ENTRY_REMOVED, removed);
                        }
                    }
                    if (restoreLocalEntry) {
                        // If this node is not a member at merge start, its mapping may have been lost and need to be recreated
                        try {
                            if (cache.put(localAddress, this.entry) == null) {
                                // Local cache events do not trigger notifications
                                this.notifyListeners(Event.Type.CACHE_ENTRY_CREATED, this.entry);
                            }
                        } catch (CacheException e) {
                            ClusteringServerLogger.ROOT_LOGGER.failedToRestoreLocalRegistryEntry(e, container.toString(), cache.getName());
                        }
                    }
                });
            }
        }
        return CompletableFutures.completedNull();
    }

    @CacheEntryCreated
    @CacheEntryModified
    public CompletionStage<Void> event(CacheEntryEvent<Address, Map.Entry<K, V>> event) {
        if (!event.isOriginLocal()) {
            Map.Entry<K, V> entry = event.getValue();
            if (entry != null) {
                this.executor.execute(() -> this.notifyListeners(event.getType(), entry));
            }
        }
        return CompletableFutures.completedNull();
    }

    @CacheEntryRemoved
    public CompletionStage<Void> removed(CacheEntryRemovedEvent<Address, Map.Entry<K, V>> event) {
        if (!event.isOriginLocal()) {
            Map.Entry<K, V> entry = event.getOldValue();
            // WFLY-4938 For some reason, the old value can be null
            if (entry != null) {
                this.executor.execute(() -> this.notifyListeners(event.getType(), entry));
            }
        }
        return CompletableFutures.completedNull();
    }

    private void notifyListeners(Event.Type type, Map.Entry<K, V> entry) {
        this.notifyListeners(type, Collections.singletonMap(entry.getKey(), entry.getValue()));
    }

    private void notifyListeners(Event.Type type, Map<K, V> entries) {
        for (Map.Entry<RegistryListener<K, V>, ExecutorService> entry: this.listeners.entrySet()) {
            RegistryListener<K, V> listener = entry.getKey();
            ExecutorService executor = entry.getValue();
            try {
                executor.submit(() -> {
                    try {
                        switch (type) {
                            case CACHE_ENTRY_CREATED: {
                                listener.addedEntries(entries);
                                break;
                            }
                            case CACHE_ENTRY_MODIFIED: {
                                listener.updatedEntries(entries);
                                break;
                            }
                            case CACHE_ENTRY_REMOVED: {
                                listener.removedEntries(entries);
                                break;
                            }
                            default: {
                                throw new IllegalStateException(type.name());
                            }
                        }
                    } catch (Throwable e) {
                        ClusteringServerLogger.ROOT_LOGGER.registryListenerFailed(e, this.cache.getCacheManager().getCacheManagerConfiguration().cacheManagerName(), this.cache.getName(), type, entries);
                    }
                });
            } catch (RejectedExecutionException e) {
                // Executor was shutdown
            }
        }
    }

    private void shutdown(ExecutorService executor) {
        WildFlySecurityManager.doUnchecked(executor, DefaultExecutorService.SHUTDOWN_ACTION);
        try {
            executor.awaitTermination(this.cache.getCacheConfiguration().transaction().cacheStopTimeout(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
