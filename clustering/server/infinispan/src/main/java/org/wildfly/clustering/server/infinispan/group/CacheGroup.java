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
package org.wildfly.clustering.server.infinispan.group;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.infinispan.Cache;
import org.infinispan.commons.util.concurrent.CompletableFutures;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.TransportConfiguration;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener.Observation;
import org.infinispan.notifications.cachelistener.annotation.TopologyChanged;
import org.infinispan.notifications.cachelistener.event.TopologyChangedEvent;
import org.infinispan.notifications.cachemanagerlistener.annotation.Merged;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.LocalModeAddress;
import org.infinispan.remoting.transport.jgroups.JGroupsAddress;
import org.infinispan.remoting.transport.jgroups.JGroupsAddressCache;
import org.infinispan.util.concurrent.BlockingManager;
import org.wildfly.clustering.Registration;
import org.wildfly.clustering.context.DefaultExecutorService;
import org.wildfly.clustering.context.ExecutorServiceFactory;
import org.wildfly.clustering.group.GroupListener;
import org.wildfly.clustering.group.Membership;
import org.wildfly.clustering.group.Node;
import org.wildfly.clustering.server.NodeFactory;
import org.wildfly.clustering.server.group.Group;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * {@link Group} implementation based on the topology of a cache.
 * @author Paul Ferraro
 */
@org.infinispan.notifications.Listener(observation = Observation.POST)
public class CacheGroup implements Group<Address>, AutoCloseable, Function<GroupListener, ExecutorService> {

    private final Map<GroupListener, ExecutorService> listeners = new ConcurrentHashMap<>();
    private final Cache<?, ?> cache;
    private final NodeFactory<org.jgroups.Address> nodeFactory;
    private final SortedMap<Integer, Boolean> views = Collections.synchronizedSortedMap(new TreeMap<>());
    private final BlockingManager blocking;
    private final Executor executor;

    public CacheGroup(CacheGroupConfiguration config) {
        this.cache = config.getCache();
        this.nodeFactory = config.getMemberFactory();
        this.blocking = config.getBlockingManager();
        this.executor = this.blocking.asExecutor(this.getClass().getName());
        this.cache.getCacheManager().addListener(this);
        this.cache.addListener(this);
    }

    @Override
    public void close() {
        this.cache.removeListener(this);
        this.cache.getCacheManager().removeListener(this);
        // Cleanup any unregistered listeners
        for (ExecutorService executor : this.listeners.values()) {
            this.shutdown(executor);
        }
        this.listeners.clear();
    }

    private void shutdown(ExecutorService executor) {
        WildFlySecurityManager.doUnchecked(executor, DefaultExecutorService.SHUTDOWN_NOW_ACTION);
        try {
            executor.awaitTermination(this.cache.getCacheConfiguration().transaction().cacheStopTimeout(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String getName() {
        GlobalConfiguration global = this.cache.getCacheManager().getCacheManagerConfiguration();
        TransportConfiguration transport = global.transport();
        return transport.transport() != null ? transport.clusterName() : global.cacheManagerName();
    }

    @Override
    public Node getLocalMember() {
        return this.createNode(this.cache.getCacheManager().getAddress());
    }

    @Override
    public Membership getMembership() {
        if (this.isSingleton()) {
            return new SingletonMembership(this.getLocalMember());
        }
        EmbeddedCacheManager manager = this.cache.getCacheManager();
        DistributionManager dist = this.cache.getAdvancedCache().getDistributionManager();
        return (dist != null) ? new CacheMembership(manager.getAddress(), dist.getCacheTopology(), this) : new CacheMembership(manager, this);
    }

    @Override
    public boolean isSingleton() {
        return this.cache.getAdvancedCache().getRpcManager() == null;
    }

    @Override
    public Node createNode(Address address) {
        return this.nodeFactory.createNode(toJGroupsAddress(address));
    }

    @Override
    public Address getAddress(Node node) {
        return (node instanceof AddressableNode) ? JGroupsAddressCache.fromJGroupsAddress(((AddressableNode) node).getAddress()) : LocalModeAddress.INSTANCE;
    }

    private static org.jgroups.Address toJGroupsAddress(Address address) {
        if ((address == null) || (address == LocalModeAddress.INSTANCE)) return null;
        if (address instanceof JGroupsAddress) {
            JGroupsAddress jgroupsAddress = (JGroupsAddress) address;
            return jgroupsAddress.getJGroupsAddress();
        }
        throw new IllegalArgumentException(address.toString());
    }

    @Merged
    @ViewChanged
    public CompletionStage<Void> viewChanged(ViewChangedEvent event) {
        if (this.cache.getAdvancedCache().getDistributionManager() != null) {
            // Record view status for use by @TopologyChanged event
            return this.blocking.runBlocking(() -> this.views.put(event.getViewId(), event.isMergeView()), event.getViewId());
        }

        Membership previousMembership = new CacheMembership(event.getLocalAddress(), event.getOldMembers(), this);
        Membership membership = new CacheMembership(event.getLocalAddress(), event.getNewMembers(), this);
        if (!this.listeners.isEmpty()) {
            this.executor.execute(new GroupListenerNotificationTask(this.listeners.entrySet(), previousMembership, membership, event.isMergeView()));
        }
        return CompletableFutures.completedNull();
    }

    @TopologyChanged
    public CompletionStage<Void> topologyChanged(TopologyChangedEvent<?, ?> event) {
        int viewId = event.getCache().getAdvancedCache().getRpcManager().getTransport().getViewId();
        Address localAddress = event.getCache().getCacheManager().getAddress();
        Membership previousMembership = new CacheMembership(localAddress, event.getWriteConsistentHashAtStart(), this);
        Membership membership = new CacheMembership(localAddress, event.getWriteConsistentHashAtEnd(), this);

        this.executor.execute(() -> {
            Boolean status = this.views.get(viewId);
            boolean merged = (status != null) ? status : false;
            new GroupListenerNotificationTask(this.listeners.entrySet(), previousMembership, membership, merged).run();
            // Purge obsolete views
            this.views.headMap(viewId).clear();
        });
        return CompletableFutures.completedNull();
    }

    @Override
    public Registration register(GroupListener listener) {
        this.listeners.computeIfAbsent(listener, this);
        return () -> this.unregister(listener);
    }

    @Override
    public ExecutorService apply(GroupListener listener) {
        return new DefaultExecutorService(listener.getClass(), ExecutorServiceFactory.SINGLE_THREAD);
    }

    private void unregister(GroupListener listener) {
        ExecutorService executor = this.listeners.remove(listener);
        if (executor != null) {
            this.shutdown(executor);
        }
    }
}
