/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.alibaba.rocketmq.storm;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.InvalidTopologyException;
import backtype.storm.generated.StormTopology;
import backtype.storm.topology.BoltDeclarer;
import backtype.storm.topology.TopologyBuilder;
import com.alibaba.rocketmq.storm.bolt.RocketMqBolt;
import com.alibaba.rocketmq.storm.domain.RocketMQConfig;
import com.alibaba.rocketmq.storm.domain.RocketMQSpouts;
import com.alibaba.rocketmq.storm.internal.tools.ConfigUtils;
import com.alibaba.rocketmq.storm.spout.SimpleMessageSpout;
import com.alibaba.rocketmq.storm.spout.factory.RocketMQSpoutFactory;
import org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Von Gosling
 */
public class SimpleTopologyDemo {
    private static final Logger LOG = LoggerFactory.getLogger(SimpleTopologyDemo.class);

    private static final String BOLT_NAME = "notifier";
    private static final String PROP_FILE_NAME = "mqspout.test.prop";

    private static Config conf = new Config();
    private static boolean isLocalMode = true;

    public static void main(String[] args) throws Exception {
        TopologyBuilder builder = buildTopology(ConfigUtils.init(PROP_FILE_NAME));

        submitTopology(builder);
    }

    private static TopologyBuilder buildTopology(Config config) throws Exception {
        TopologyBuilder builder = new TopologyBuilder();

        int boltParallel = NumberUtils.toInt((String) config.get("topology.bolt.parallel"), 1);

        int spoutParallel = NumberUtils.toInt((String) config.get("topology.spout.parallel"), 1);

        BoltDeclarer writerBolt = builder.setBolt(BOLT_NAME, new RocketMqBolt(), boltParallel);

        SimpleMessageSpout defaultSpout = (SimpleMessageSpout) RocketMQSpoutFactory
                .getSpout(RocketMQSpouts.SIMPLE.getValue());
        RocketMQConfig mqConig = (RocketMQConfig) config.get(ConfigUtils.CONFIG_ROCKETMQ);
        defaultSpout.setConfig(mqConig);

        String id = (String) config.get(ConfigUtils.CONFIG_TOPIC);
        builder.setSpout(id, defaultSpout, spoutParallel);

        writerBolt.shuffleGrouping(id);
        return builder;
    }

    private static void submitTopology(TopologyBuilder builder) {
        try {
            String topologyName = String.valueOf(conf.get("topology.name"));
            StormTopology topology = builder.createTopology();

            if (isLocalMode == true) {
                LocalCluster cluster = new LocalCluster();
                conf.put(Config.STORM_CLUSTER_MODE, "local");

                cluster.submitTopology(topologyName, conf, topology);

                Thread.sleep(50000);

                cluster.killTopology(topologyName);
                cluster.shutdown();
            } else {
                conf.put(Config.STORM_CLUSTER_MODE, "distributed");
                StormSubmitter.submitTopology(topologyName, conf, topology);
            }

        } catch (AlreadyAliveException e) {
            LOG.error(e.getMessage(), e.getCause());
        } catch (InvalidTopologyException e) {
            LOG.error(e.getMessage(), e.getCause());
        } catch (Exception e) {
            LOG.error(e.getMessage(), e.getCause());
        }
    }
}
