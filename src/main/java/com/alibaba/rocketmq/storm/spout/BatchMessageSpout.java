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

package com.alibaba.rocketmq.storm.spout;

import backtype.storm.Config;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichSpout;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;
import com.alibaba.rocketmq.client.consumer.listener.*;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.rocketmq.storm.MessagePushConsumer;
import com.alibaba.rocketmq.storm.annotation.Extension;
import com.alibaba.rocketmq.storm.domain.BatchMessage;
import com.alibaba.rocketmq.storm.domain.RocketMQConfig;
import com.google.common.collect.MapMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author Von Gosling
 */
@Extension("batch")
public class BatchMessageSpout implements IRichSpout {
    private static final long serialVersionUID = 4641537253577312163L;

    private static final Logger LOG = LoggerFactory
            .getLogger(BatchMessageSpout.class);
    protected RocketMQConfig config;

    protected MessagePushConsumer mqClient;

    protected String topologyName;

    protected SpoutOutputCollector collector;

    protected final BlockingQueue<BatchMessage> batchQueue = new LinkedBlockingQueue<BatchMessage>();
    protected Map<UUID, BatchMessage> cache = new MapMaker().makeMap();

    public void setConfig(RocketMQConfig config) {
        this.config = config;
    }

    @SuppressWarnings("rawtypes")
    public void open(final Map conf, final TopologyContext context,
                     final SpoutOutputCollector collector) {
        this.collector = collector;

        this.topologyName = (String) conf.get(Config.TOPOLOGY_NAME);

        if (mqClient == null) {
            try {
                config.setInstanceName(String.valueOf(context.getThisTaskId()));
                mqClient = new MessagePushConsumer(config);

                mqClient.start(buildMessageListener());
            } catch (Throwable e) {
                LOG.error("Failed to init consumer !", e);
                throw new RuntimeException(e);
            }
        }

        LOG.info("Topology {} opened {} spout successfully!",
                new Object[]{topologyName, config.getTopic()});
    }

    public void nextTuple() {
        BatchMessage msg = null;
        try {
            msg = batchQueue.take();
        } catch (InterruptedException e) {
            return;
        }
        if (msg == null) {
            return;
        }

        UUID uuid = msg.getBatchId();
        collector.emit(new Values(msg.getMsgList()), uuid);
    }

    public BatchMessage finish(UUID batchId) {
        BatchMessage batchMsg = cache.remove(batchId);
        if (batchMsg == null) {
            LOG.warn("Failed to get cache {}!", batchId);
            return null;
        } else {
            batchMsg.done();
            return batchMsg;
        }
    }

    public void ack(final Object id) {
        if (id instanceof UUID) {
            UUID batchId = (UUID) id;
            finish(batchId);
            return;
        } else {
            LOG.error("Id isn't UUID, type is {}!", id.getClass().getName());
        }
    }

    protected void handleFail(UUID batchId) {
        BatchMessage msg = cache.get(batchId);

        LOG.info("Failed to handle {} !", msg);

        int failureTimes = msg.getMessageStat().getFailureTimes().incrementAndGet();
        if (config.getMaxFailTimes() < 0 || failureTimes < config.getMaxFailTimes()) {
            batchQueue.offer(msg);
            return;
        } else {
            LOG.info("Skip message {} !", msg);
            finish(batchId);
            return;
        }

    }

    public void fail(final Object id) {
        if (id instanceof UUID) {
            UUID batchId = (UUID) id;
            handleFail(batchId);
            return;
        } else {
            LOG.error("Id isn't UUID, type is {} !", id.getClass().getName());
        }
    }

    public void declareOutputFields(final OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("MessageExtList"));
    }

    public boolean isDistributed() {
        return true;
    }

    public Map<String, Object> getComponentConfiguration() {
        return null;
    }

    public void activate() {
        mqClient.resume();
    }

    public void deactivate() {
        mqClient.suspend();
    }

    public void close() {
        cleanup();
    }

    public void cleanup() {
        for (Entry<UUID, BatchMessage> entry : cache.entrySet()) {
            BatchMessage msgs = entry.getValue();
            msgs.fail();
        }
        mqClient.shutdown();
    }

    public BlockingQueue<BatchMessage> getBatchQueue() {
        return batchQueue;
    }

    public MessageListener buildMessageListener() {
        if (config.isOrdered()) {
            MessageListener listener = new MessageListenerOrderly() {
                public ConsumeOrderlyStatus consumeMessage(List<MessageExt> msgs,
                                                           ConsumeOrderlyContext context) {
                    boolean isSuccess = BatchMessageSpout.this.consumeMessage(msgs,
                            context.getMessageQueue());
                    if (isSuccess) {
                        return ConsumeOrderlyStatus.SUCCESS;
                    } else {
                        return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                    }
                }
            };
            LOG.debug("Successfully create ordered listener !");
            return listener;
        } else {
            MessageListener listener = new MessageListenerConcurrently() {
                public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                                                                ConsumeConcurrentlyContext context) {
                    boolean isSuccess = BatchMessageSpout.this.consumeMessage(msgs,
                            context.getMessageQueue());
                    if (isSuccess) {
                        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                    } else {
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }

            };
            LOG.debug("Successfully create concurrently listener !");
            return listener;
        }
    }

    public boolean consumeMessage(List<MessageExt> msgs, MessageQueue mq) {
        LOG.info("Receiving {} messages {} from MQ {} !", new Object[]{msgs.size(), msgs, mq});

        if (msgs == null || msgs.isEmpty()) {
            return true;
        }

        BatchMessage batchMsgs = new BatchMessage(msgs, mq);

        cache.put(batchMsgs.getBatchId(), batchMsgs);

        batchQueue.offer(batchMsgs);

        try {
            boolean isDone = batchMsgs.waitFinish();
            if (!isDone) {
                cache.remove(batchMsgs.getBatchId());
                return false;
            }
        } catch (InterruptedException e) {
            cache.remove(batchMsgs.getBatchId());
            return false;
        }

        return batchMsgs.isSuccess();
    }

}
