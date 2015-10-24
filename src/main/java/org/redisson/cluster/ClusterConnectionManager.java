/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.cluster;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.redisson.ClusterServersConfig;
import org.redisson.Config;
import org.redisson.MasterSlaveServersConfig;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisConnection;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.cluster.ClusterNodeInfo.Flag;
import org.redisson.connection.MasterSlaveConnectionManager;
import org.redisson.connection.MasterSlaveEntry;
import org.redisson.connection.SingleEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

public class ClusterConnectionManager extends MasterSlaveConnectionManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Map<URI, RedisConnection> nodeConnections = new HashMap<URI, RedisConnection>();

    private final Map<ClusterSlotRange, ClusterPartition> lastPartitions = new HashMap<ClusterSlotRange, ClusterPartition>();

    private ScheduledFuture<?> monitorFuture;

    public ClusterConnectionManager(ClusterServersConfig cfg, Config config) {
        init(config);

        this.config = create(cfg);
        init(this.config);

        for (URI addr : cfg.getNodeAddresses()) {
            RedisConnection connection = connect(cfg, addr);
            if (connection == null) {
                continue;
            }

            String nodesValue = connection.sync(RedisCommands.CLUSTER_NODES);

            Collection<ClusterPartition> partitions = parsePartitions(nodesValue);
            for (ClusterPartition partition : partitions) {
                addMasterEntry(partition, cfg);
            }

            break;
        }

        monitorClusterChange(cfg);
    }

    private RedisConnection connect(ClusterServersConfig cfg, URI addr) {
        RedisConnection connection = nodeConnections.get(addr);
        if (connection != null) {
            return connection;
        }
        RedisClient client = createClient(addr.getHost(), addr.getPort(), cfg.getTimeout());
        try {
            connection = client.connect();
            nodeConnections.put(addr, connection);
        } catch (RedisConnectionException e) {
            log.warn(e.getMessage(), e);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return connection;
    }

    @Override
    protected void initEntry(MasterSlaveServersConfig config) {
    }

    private void addMasterEntry(ClusterPartition partition, ClusterServersConfig cfg) {
        if (partition.isMasterFail()) {
            log.warn("add master: {} for slot ranges: {} failed. Reason - server has FAIL flag", partition.getMasterAddress(), partition.getSlotRanges());
            return;
        }

        RedisConnection connection = connect(cfg, partition.getMasterAddress());
        if (connection == null) {
            return;
        }
        Map<String, String> params = connection.sync(RedisCommands.CLUSTER_INFO);
        if ("fail".equals(params.get("cluster_state"))) {
            log.warn("add master: {} for slot ranges: {} failed. Reason - cluster_state:fail", partition.getMasterAddress(), partition.getSlotRanges());
            return;
        }

        MasterSlaveServersConfig config = create(cfg);
        log.info("added master: {} for slot ranges: {}", partition.getMasterAddress(), partition.getSlotRanges());
        config.setMasterAddress(partition.getMasterAddress());

        SingleEntry entry = new SingleEntry(partition.getSlotRanges(), this, config);
        entry.setupMasterEntry(config.getMasterAddress().getHost(), config.getMasterAddress().getPort());
        for (ClusterSlotRange slotRange : partition.getSlotRanges()) {
            addMaster(slotRange, entry);
            lastPartitions.put(slotRange, partition);
        }
    }

    private void monitorClusterChange(final ClusterServersConfig cfg) {
        monitorFuture = GlobalEventExecutor.INSTANCE.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    for (URI addr : cfg.getNodeAddresses()) {
                        RedisConnection connection = connect(cfg, addr);
                        if (connection == null) {
                            continue;
                        }

                        String nodesValue = connection.sync(RedisCommands.CLUSTER_NODES);

                        log.debug("cluster nodes state: {}", nodesValue);

                        Collection<ClusterPartition> newPartitions = parsePartitions(nodesValue);
                        checkMasterNodesChange(newPartitions);
                        checkSlotsChange(cfg, newPartitions);

                        break;
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }

            }


        }, cfg.getScanInterval(), cfg.getScanInterval(), TimeUnit.MILLISECONDS);
    }

    private Collection<ClusterSlotRange> slots(Collection<ClusterPartition> partitions) {
        List<ClusterSlotRange> result = new ArrayList<ClusterSlotRange>();
        for (ClusterPartition clusterPartition : partitions) {
            result.addAll(clusterPartition.getSlotRanges());
        }
        return result;
    }

    private ClusterPartition find(Collection<ClusterPartition> partitions, ClusterSlotRange slotRange) {
        for (ClusterPartition clusterPartition : partitions) {
            if (clusterPartition.getSlotRanges().contains(slotRange)) {
                return clusterPartition;
            }
        }
        return null;
    }

    private void checkMasterNodesChange(Collection<ClusterPartition> newPartitions) {
        for (ClusterPartition newPart : newPartitions) {
            for (ClusterPartition currentPart : lastPartitions.values()) {
                if (!newPart.getMasterAddress().equals(currentPart.getMasterAddress())) {
                    continue;
                }
                // current master marked as failed
                if (newPart.isMasterFail()) {
                    for (ClusterSlotRange currentSlotRange : currentPart.getSlotRanges()) {
                        ClusterPartition newMasterPart = find(newPartitions, currentSlotRange);
                        // does partition has a new master?
                        if (!newMasterPart.getMasterAddress().equals(currentPart.getMasterAddress())) {
                            log.info("changing master from {} to {} for {}",
                                    currentPart.getMasterAddress(), newMasterPart.getMasterAddress(), currentSlotRange);
                            URI newUri = newMasterPart.getMasterAddress();
                            URI oldUri = currentPart.getMasterAddress();

                            changeMaster(currentSlotRange, newUri.getHost(), newUri.getPort());
                            slaveDown(currentSlotRange, oldUri.getHost(), oldUri.getPort());

                            currentPart.setMasterAddress(newMasterPart.getMasterAddress());
                        }
                    }
                }
                break;
            }
        }
    }

    private void checkSlotsChange(ClusterServersConfig cfg, Collection<ClusterPartition> partitions) {
        Collection<ClusterSlotRange> partitionsSlots = slots(partitions);
        Set<ClusterSlotRange> removedSlots = new HashSet<ClusterSlotRange>(lastPartitions.keySet());
        removedSlots.removeAll(partitionsSlots);
        lastPartitions.keySet().removeAll(removedSlots);
        if (!removedSlots.isEmpty()) {
            log.info("{} slot ranges found to remove", removedSlots.size());
        }

        Map<ClusterSlotRange, MasterSlaveEntry> removeAddrs = new HashMap<ClusterSlotRange, MasterSlaveEntry>();
        for (ClusterSlotRange slot : removedSlots) {
            MasterSlaveEntry entry = removeMaster(slot);
            entry.shutdownMasterAsync();
            removeAddrs.put(slot, entry);
        }

        Set<ClusterSlotRange> addedSlots = new HashSet<ClusterSlotRange>(partitionsSlots);
        addedSlots.removeAll(lastPartitions.keySet());
        if (!addedSlots.isEmpty()) {
            log.info("{} slots found to add", addedSlots.size());
        }
        for (ClusterSlotRange slot : addedSlots) {
            ClusterPartition partition = find(partitions, slot);
            addMasterEntry(partition, cfg);
        }

        for (Entry<ClusterSlotRange, MasterSlaveEntry> entry : removeAddrs.entrySet()) {
            InetSocketAddress url = entry.getValue().getClient().getAddr();
            slaveDown(entry.getKey(), url.getHostName(), url.getPort());
        }
    }

    private Collection<ClusterPartition> parsePartitions(String nodesValue) {
        Map<String, ClusterPartition> partitions = new HashMap<String, ClusterPartition>();
        List<ClusterNodeInfo> nodes = parse(nodesValue);
        for (ClusterNodeInfo clusterNodeInfo : nodes) {
            if (clusterNodeInfo.containsFlag(Flag.NOADDR)) {
                // skip it
                continue;
            }

            String id = clusterNodeInfo.getNodeId();
            if (clusterNodeInfo.containsFlag(Flag.SLAVE)) {
                id = clusterNodeInfo.getSlaveOf();
            }


            ClusterPartition partition = partitions.get(id);
            if (partition == null) {
                partition = new ClusterPartition();
                partitions.put(id, partition);
            }

            if (clusterNodeInfo.containsFlag(Flag.FAIL)) {
                partition.setMasterFail(true);
            }

            if (clusterNodeInfo.containsFlag(Flag.SLAVE)) {
                partition.addSlaveAddress(clusterNodeInfo.getAddress());
            } else {
                partition.addSlotRanges(clusterNodeInfo.getSlotRanges());
                partition.setMasterAddress(clusterNodeInfo.getAddress());
            }
        }
        return partitions.values();
    }

    private MasterSlaveServersConfig create(ClusterServersConfig cfg) {
        MasterSlaveServersConfig c = new MasterSlaveServersConfig();
        c.setRetryInterval(cfg.getRetryInterval());
        c.setRetryAttempts(cfg.getRetryAttempts());
        c.setTimeout(cfg.getTimeout());
        c.setPingTimeout(cfg.getPingTimeout());
        c.setLoadBalancer(cfg.getLoadBalancer());
        c.setPassword(cfg.getPassword());
        c.setDatabase(cfg.getDatabase());
        c.setClientName(cfg.getClientName());
        c.setRefreshConnectionAfterFails(cfg.getRefreshConnectionAfterFails());
        c.setMasterConnectionPoolSize(cfg.getMasterConnectionPoolSize());
        c.setSlaveConnectionPoolSize(cfg.getSlaveConnectionPoolSize());
        c.setSlaveSubscriptionConnectionPoolSize(cfg.getSlaveSubscriptionConnectionPoolSize());
        c.setSubscriptionsPerConnection(cfg.getSubscriptionsPerConnection());
        return c;
    }

    private List<ClusterNodeInfo> parse(String nodesResponse) {
        List<ClusterNodeInfo> nodes = new ArrayList<ClusterNodeInfo>();
        for (String nodeInfo : nodesResponse.split("\n")) {
            ClusterNodeInfo node = new ClusterNodeInfo();
            String[] params = nodeInfo.split(" ");

            String nodeId = params[0];
            node.setNodeId(nodeId);

            String addr = params[1];
            node.setAddress(addr);

            String flags = params[2];
            for (String flag : flags.split(",")) {
                String flagValue = flag.toUpperCase().replaceAll("\\?", "");
                node.addFlag(ClusterNodeInfo.Flag.valueOf(flagValue));
            }

            String slaveOf = params[3];
            if (!"-".equals(slaveOf)) {
                node.setSlaveOf(slaveOf);
            }

            if (params.length > 8) {
                for (int i = 0; i < params.length - 8; i++) {
                    String slots = params[i + 8];
                    String[] parts = slots.split("-");

                    if(parts.length == 1) {
                        node.addSlotRange(new ClusterSlotRange(Integer.valueOf(parts[0]), Integer.valueOf(parts[0])));
                    } else if(parts.length == 2) {
                        node.addSlotRange(new ClusterSlotRange(Integer.valueOf(parts[0]), Integer.valueOf(parts[1])));
                    }
                }
            }
            nodes.add(node);
        }
        return nodes;
    }

    @Override
    public void shutdown() {
        monitorFuture.cancel(true);
        super.shutdown();

        for (RedisConnection connection : nodeConnections.values()) {
            connection.getRedisClient().shutdown();
        }
    }
}
