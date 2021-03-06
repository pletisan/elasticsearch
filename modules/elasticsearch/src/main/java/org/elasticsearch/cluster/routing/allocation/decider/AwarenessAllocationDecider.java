/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.cluster.routing.allocation.decider;

import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.MutableShardRouting;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.common.collect.Maps;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.trove.map.hash.TObjectIntHashMap;
import org.elasticsearch.node.settings.NodeSettingsService;

import java.util.HashMap;
import java.util.Map;

/**
 */
public class AwarenessAllocationDecider extends AllocationDecider {

    static {
        MetaData.addDynamicSettings(
                "cluster.routing.allocation.awareness.attributes",
                "cluster.routing.allocation.awareness.force.*"
        );
    }

    class ApplySettings implements NodeSettingsService.Listener {
        @Override public void onRefreshSettings(Settings settings) {
            String[] awarenessAttributes = settings.getAsArray("cluster.routing.allocation.awareness.attributes", null);
            if (awarenessAttributes != null) {
                logger.info("updating [cluster.routing.allocation.awareness.attributes] from [{}] to [{}]", AwarenessAllocationDecider.this.awarenessAttributes, awarenessAttributes);
                AwarenessAllocationDecider.this.awarenessAttributes = awarenessAttributes;
            }
            Map<String, String[]> forcedAwarenessAttributes = new HashMap<String, String[]>(AwarenessAllocationDecider.this.forcedAwarenessAttributes);
            Map<String, Settings> forceGroups = settings.getGroups("cluster.routing.allocation.awareness.force.");
            if (!forceGroups.isEmpty()) {
                for (Map.Entry<String, Settings> entry : forceGroups.entrySet()) {
                    String[] aValues = entry.getValue().getAsArray("values");
                    if (aValues.length > 0) {
                        forcedAwarenessAttributes.put(entry.getKey(), aValues);
                    }
                }
            }
            AwarenessAllocationDecider.this.forcedAwarenessAttributes = forcedAwarenessAttributes;
        }
    }

    private String[] awarenessAttributes;

    private Map<String, String[]> forcedAwarenessAttributes;

    @Inject public AwarenessAllocationDecider(Settings settings, NodeSettingsService nodeSettingsService) {
        super(settings);
        this.awarenessAttributes = settings.getAsArray("cluster.routing.allocation.awareness.attributes");

        forcedAwarenessAttributes = Maps.newHashMap();
        Map<String, Settings> forceGroups = settings.getGroups("cluster.routing.allocation.awareness.force.");
        for (Map.Entry<String, Settings> entry : forceGroups.entrySet()) {
            String[] aValues = entry.getValue().getAsArray("values");
            if (aValues.length > 0) {
                forcedAwarenessAttributes.put(entry.getKey(), aValues);
            }
        }

        nodeSettingsService.addListener(new ApplySettings());
    }

    public String[] awarenessAttributes() {
        return this.awarenessAttributes;
    }

    @Override public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return underCapacity(shardRouting, node, allocation, true) ? Decision.YES : Decision.NO;
    }

    @Override public boolean canRemain(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return underCapacity(shardRouting, node, allocation, false);
    }

    private boolean underCapacity(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation, boolean moveToNode) {
        if (awarenessAttributes.length == 0) {
            return true;
        }

        IndexMetaData indexMetaData = allocation.metaData().index(shardRouting.index());
        int shardCount = indexMetaData.numberOfReplicas() + 1; // 1 for primary
        for (String awarenessAttribute : awarenessAttributes) {
            // the node the shard exists on must be associated with an awareness attribute
            if (!node.node().attributes().containsKey(awarenessAttribute)) {
                return false;
            }

            // build attr_value -> nodes map
            TObjectIntHashMap<String> nodesPerAttribute = allocation.routingNodes().nodesPerAttributesCounts(awarenessAttribute);

            // build the count of shards per attribute value
            TObjectIntHashMap<String> shardPerAttribute = new TObjectIntHashMap<String>();
            for (RoutingNode routingNode : allocation.routingNodes()) {
                for (int i = 0; i < routingNode.shards().size(); i++) {
                    MutableShardRouting nodeShardRouting = routingNode.shards().get(i);
                    if (nodeShardRouting.shardId().equals(shardRouting.shardId())) {
                        // if the shard is relocating, then make sure we count it as part of the node it is relocating to
                        if (nodeShardRouting.relocating()) {
                            RoutingNode relocationNode = allocation.routingNodes().node(nodeShardRouting.relocatingNodeId());
                            shardPerAttribute.adjustOrPutValue(relocationNode.node().attributes().get(awarenessAttribute), 1, 1);
                        } else if (nodeShardRouting.started()) {
                            shardPerAttribute.adjustOrPutValue(routingNode.node().attributes().get(awarenessAttribute), 1, 1);
                        }
                    }
                }
            }
            if (moveToNode) {
                if (shardRouting.assignedToNode()) {
                    String nodeId = shardRouting.relocating() ? shardRouting.relocatingNodeId() : shardRouting.currentNodeId();
                    if (!node.nodeId().equals(nodeId)) {
                        // we work on different nodes, move counts around
                        shardPerAttribute.adjustOrPutValue(allocation.routingNodes().node(nodeId).node().attributes().get(awarenessAttribute), -1, 0);
                        shardPerAttribute.adjustOrPutValue(node.node().attributes().get(awarenessAttribute), 1, 1);
                    }
                } else {
                    shardPerAttribute.adjustOrPutValue(node.node().attributes().get(awarenessAttribute), 1, 1);
                }
            }

            int numberOfAttributes = nodesPerAttribute.size();
            String[] fullValues = forcedAwarenessAttributes.get(awarenessAttribute);
            if (fullValues != null) {
                for (String fullValue : fullValues) {
                    if (!shardPerAttribute.contains(fullValue)) {
                        numberOfAttributes++;
                    }
                }
            }
            // TODO should we remove ones that are not part of full list?

            int averagePerAttribute = shardCount / numberOfAttributes;
            int totalLeftover = shardCount % numberOfAttributes;
            int requiredCountPerAttribute;
            if (averagePerAttribute == 0) {
                // if we have more attributes values than shard count, no leftover
                totalLeftover = 0;
                requiredCountPerAttribute = 1;
            } else {
                requiredCountPerAttribute = averagePerAttribute;
            }
            int leftoverPerAttribute = totalLeftover == 0 ? 0 : 1;

            int currentNodeCount = shardPerAttribute.get(node.node().attributes().get(awarenessAttribute));
            // if we are above with leftover, then we know we are not good, even with mod
            if (currentNodeCount > (requiredCountPerAttribute + leftoverPerAttribute)) {
                return false;
            }
            // all is well, we are below or same as average
            if (currentNodeCount <= requiredCountPerAttribute) {
                continue;
            }
        }

        return true;
    }
}
