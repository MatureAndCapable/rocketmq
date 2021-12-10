package org.apache.rocketmq.test.statictopic;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.Logger;
import org.apache.rocketmq.acl.common.AclUtils;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.admin.ConsumeStats;
import org.apache.rocketmq.common.admin.TopicStatsTable;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.rpc.ClientMetadata;
import org.apache.rocketmq.common.statictopic.LogicQueueMappingItem;
import org.apache.rocketmq.common.statictopic.TopicConfigAndQueueMapping;
import org.apache.rocketmq.common.statictopic.TopicQueueMappingOne;
import org.apache.rocketmq.common.statictopic.TopicQueueMappingUtils;
import org.apache.rocketmq.srvutil.ServerUtil;
import org.apache.rocketmq.test.base.BaseConf;
import org.apache.rocketmq.test.client.rmq.RMQNormalConsumer;
import org.apache.rocketmq.test.client.rmq.RMQNormalProducer;
import org.apache.rocketmq.test.listener.rmq.concurrent.RMQNormalListener;
import org.apache.rocketmq.test.util.MQAdminTestUtils;
import org.apache.rocketmq.test.util.MQRandomUtils;
import org.apache.rocketmq.test.util.VerifyUtils;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.apache.rocketmq.tools.admin.MQAdminUtils;
import org.apache.rocketmq.tools.command.MQAdminStartup;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static org.apache.rocketmq.common.statictopic.TopicQueueMappingUtils.getMappingDetailFromConfig;

@FixMethodOrder
public class StaticTopicIT extends BaseConf {

    private static Logger logger = Logger.getLogger(StaticTopicIT.class);
    private DefaultMQAdminExt defaultMQAdminExt;

    @Before
    public void setUp() throws Exception {
        System.setProperty("rocketmq.client.rebalance.waitInterval", "500");
        defaultMQAdminExt = getAdmin(nsAddr);
        waitBrokerRegistered(nsAddr, clusterName);
        defaultMQAdminExt.start();
    }

    @Test
    public void testCommandsWithCluster() throws Exception {
        //This case is used to mock the env to test the command manually
        String topic = "static" + MQRandomUtils.getRandomTopic();
        RMQNormalProducer producer = getProducer(nsAddr, topic);
        RMQNormalConsumer consumer = getConsumer(nsAddr, topic, "*", new RMQNormalListener());
        int queueNum = 10;
        int msgEachQueue = 100;

        {
            MQAdminTestUtils.createStaticTopicWithCommand(topic, queueNum, null, clusterName, nsAddr);
            sendMessagesAndCheck(producer, getBrokers(), topic, queueNum, msgEachQueue, 0);
            //consume and check
            consumeMessagesAndCheck(producer, consumer, topic, queueNum, msgEachQueue, 0, 1);
        }
        {
            MQAdminTestUtils.remappingStaticTopicWithCommand(topic, null, clusterName, nsAddr);
            Thread.sleep(500);
            sendMessagesAndCheck(producer, getBrokers(), topic, queueNum, msgEachQueue, 100);
        }
    }

    @Test
    public void testCommandsWithBrokers() throws Exception {
        //This case is used to mock the env to test the command manually
        String topic = "static" + MQRandomUtils.getRandomTopic();
        RMQNormalProducer producer = getProducer(nsAddr, topic);
        RMQNormalConsumer consumer = getConsumer(nsAddr, topic, "*", new RMQNormalListener());
        int queueNum = 10;
        int msgEachQueue = 100;
        {
            Set<String> brokers = ImmutableSet.of(broker1Name);
            MQAdminTestUtils.createStaticTopicWithCommand(topic, queueNum, brokers, null, nsAddr);
            sendMessagesAndCheck(producer, brokers, topic, queueNum, msgEachQueue, 0);
            //consume and check
            consumeMessagesAndCheck(producer, consumer, topic, queueNum, msgEachQueue, 0, 1);
        }
        {
            Set<String> brokers = ImmutableSet.of(broker2Name);
            MQAdminTestUtils.remappingStaticTopicWithCommand(topic, brokers, null, nsAddr);
            Thread.sleep(500);
            sendMessagesAndCheck(producer, brokers, topic, queueNum, msgEachQueue, TopicQueueMappingUtils.DEFAULT_BLOCK_SEQ_SIZE);
            consumeMessagesAndCheck(producer, consumer, topic, queueNum, msgEachQueue, 0, 2);
        }
    }

    @Test
    public void testNoTargetBrokers() throws Exception {
        String topic = "static" + MQRandomUtils.getRandomTopic();
        int queueNum = 10;
        {
            Set<String> targetBrokers = new HashSet<>();
            targetBrokers.add(broker1Name);
            MQAdminTestUtils.createStaticTopic(topic, queueNum, targetBrokers, defaultMQAdminExt);
            Map<String, TopicConfigAndQueueMapping> remoteBrokerConfigMap = MQAdminUtils.examineTopicConfigAll(topic, defaultMQAdminExt);
            Assert.assertEquals(brokerNum, remoteBrokerConfigMap.size());
            TopicQueueMappingUtils.checkNameEpochNumConsistence(topic, remoteBrokerConfigMap);
            Map<Integer, TopicQueueMappingOne>  globalIdMap = TopicQueueMappingUtils.checkAndBuildMappingItems(new ArrayList<>(getMappingDetailFromConfig(remoteBrokerConfigMap.values())), false, true);
            Assert.assertEquals(queueNum, globalIdMap.size());
            TopicConfigAndQueueMapping configMapping = remoteBrokerConfigMap.get(broker2Name);
            Assert.assertEquals(0, configMapping.getWriteQueueNums());
            Assert.assertEquals(0, configMapping.getReadQueueNums());
            Assert.assertEquals(0, configMapping.getMappingDetail().getHostedQueues().size());
        }

        {
            Set<String> targetBrokers = new HashSet<>();
            targetBrokers.add(broker2Name);
            MQAdminTestUtils.remappingStaticTopic(topic, targetBrokers, defaultMQAdminExt);
            Map<String, TopicConfigAndQueueMapping> remoteBrokerConfigMap = MQAdminUtils.examineTopicConfigAll(topic, defaultMQAdminExt);
            Assert.assertEquals(brokerNum, remoteBrokerConfigMap.size());
            TopicQueueMappingUtils.checkNameEpochNumConsistence(topic, remoteBrokerConfigMap);
            Map<Integer, TopicQueueMappingOne>  globalIdMap = TopicQueueMappingUtils.checkAndBuildMappingItems(new ArrayList<>(getMappingDetailFromConfig(remoteBrokerConfigMap.values())), false, true);
            Assert.assertEquals(queueNum, globalIdMap.size());
        }

    }

    private void sendMessagesAndCheck(RMQNormalProducer producer, Set<String> targetBrokers, String topic, int queueNum, int msgEachQueue, long baseOffset) throws Exception {
        ClientMetadata clientMetadata = MQAdminUtils.getBrokerAndTopicMetadata(topic, defaultMQAdminExt);
        List<MessageQueue> messageQueueList = producer.getMessageQueue();
        Assert.assertEquals(queueNum, messageQueueList.size());
        for (int i = 0; i < queueNum; i++) {
            MessageQueue messageQueue = messageQueueList.get(i);
            Assert.assertEquals(topic, messageQueue.getTopic());
            Assert.assertEquals(MixAll.LOGICAL_QUEUE_MOCK_BROKER_NAME, messageQueue.getBrokerName());
            Assert.assertEquals(i, messageQueue.getQueueId());
            String destBrokerName = clientMetadata.getBrokerNameFromMessageQueue(messageQueue);
            Assert.assertTrue(targetBrokers.contains(destBrokerName));
        }
        for(MessageQueue messageQueue: messageQueueList) {
            producer.send(msgEachQueue, messageQueue);
        }
        Assert.assertEquals(0, producer.getSendErrorMsg().size());
        //leave the time to build the cq
        Thread.sleep(100);
        for(MessageQueue messageQueue: messageQueueList) {
            Assert.assertEquals(0, defaultMQAdminExt.minOffset(messageQueue));
            Assert.assertEquals(msgEachQueue + baseOffset, defaultMQAdminExt.maxOffset(messageQueue));
        }
        TopicStatsTable topicStatsTable = defaultMQAdminExt.examineTopicStats(topic);
        for(MessageQueue messageQueue: messageQueueList) {
            Assert.assertEquals(0, topicStatsTable.getOffsetTable().get(messageQueue).getMinOffset());
            Assert.assertEquals(msgEachQueue + baseOffset, topicStatsTable.getOffsetTable().get(messageQueue).getMaxOffset());
        }
    }

    private Map<Integer, List<MessageExt>> computeMessageByQueue(Collection<Object> msgs) {
        Map<Integer, List<MessageExt>> messagesByQueue = new HashMap<>();
        for (Object object : msgs) {
            MessageExt messageExt = (MessageExt) object;
            if (!messagesByQueue.containsKey(messageExt.getQueueId())) {
                messagesByQueue.put(messageExt.getQueueId(), new ArrayList<>());
            }
            messagesByQueue.get(messageExt.getQueueId()).add(messageExt);
        }
        for (List<MessageExt> msgEachQueue: messagesByQueue.values()) {
            Collections.sort(msgEachQueue, new Comparator<MessageExt>() {
                @Override
                public int compare(MessageExt o1, MessageExt o2) {
                    return (int) (o1.getQueueOffset() - o2.getQueueOffset());
                }
            });
        }
        return messagesByQueue;
    }

    private void consumeMessagesAndCheck(RMQNormalProducer producer, RMQNormalConsumer consumer, String topic, int queueNum, int msgEachQueue, int startGen, int genNum) {
        consumer.getListener().waitForMessageConsume(producer.getAllMsgBody(), 30000);
        /*System.out.println("produce:" + producer.getAllMsgBody().size());
        System.out.println("consume:" + consumer.getListener().getAllMsgBody().size());*/

        assertThat(VerifyUtils.getFilterdMessage(producer.getAllMsgBody(),
                consumer.getListener().getAllMsgBody()))
                .containsExactlyElementsIn(producer.getAllMsgBody());
        Map<Integer, List<MessageExt>> messagesByQueue = computeMessageByQueue(consumer.getListener().getAllOriginMsg());
        Assert.assertEquals(queueNum, messagesByQueue.size());
        for (int i = 0; i < queueNum; i++) {
            List<MessageExt> messageExts = messagesByQueue.get(i);
            /*for (MessageExt messageExt:messageExts) {
                System.out.printf("%d %d\n", messageExt.getQueueId(), messageExt.getQueueOffset());
            }*/
            int totalEachQueue = msgEachQueue * genNum;
            Assert.assertEquals(totalEachQueue, messageExts.size());
            for (int j = 0; j < totalEachQueue; j++) {
                MessageExt messageExt = messageExts.get(j);
                int currGen = startGen + j / msgEachQueue;
                Assert.assertEquals(topic, messageExt.getTopic());
                Assert.assertEquals(MixAll.LOGICAL_QUEUE_MOCK_BROKER_NAME, messageExt.getBrokerName());
                Assert.assertEquals(i, messageExt.getQueueId());
                Assert.assertEquals((j % msgEachQueue) + currGen * TopicQueueMappingUtils.DEFAULT_BLOCK_SEQ_SIZE, messageExt.getQueueOffset());
            }
        }
    }


    @Test
    public void testCreateProduceConsumeStaticTopic() throws Exception {
        String topic = "static" + MQRandomUtils.getRandomTopic();
        RMQNormalProducer producer = getProducer(nsAddr, topic);
        RMQNormalConsumer consumer = getConsumer(nsAddr, topic, "*", new RMQNormalListener());

        int queueNum = 10;
        int msgEachQueue = 100;
        //create static topic
        Map<String, TopicConfigAndQueueMapping> localBrokerConfigMap = MQAdminTestUtils.createStaticTopic(topic, queueNum, getBrokers(), defaultMQAdminExt);
        //check the static topic config
        {
            Map<String, TopicConfigAndQueueMapping> remoteBrokerConfigMap = MQAdminUtils.examineTopicConfigAll(topic, defaultMQAdminExt);
            Assert.assertEquals(brokerNum, remoteBrokerConfigMap.size());
            for (Map.Entry<String, TopicConfigAndQueueMapping> entry: remoteBrokerConfigMap.entrySet())  {
                String broker = entry.getKey();
                TopicConfigAndQueueMapping configMapping = entry.getValue();
                TopicConfigAndQueueMapping localConfigMapping = localBrokerConfigMap.get(broker);
                Assert.assertNotNull(localConfigMapping);
                Assert.assertEquals(configMapping, localConfigMapping);
            }
            TopicQueueMappingUtils.checkNameEpochNumConsistence(topic, remoteBrokerConfigMap);
            Map<Integer, TopicQueueMappingOne>  globalIdMap = TopicQueueMappingUtils.checkAndBuildMappingItems(new ArrayList<>(getMappingDetailFromConfig(remoteBrokerConfigMap.values())), false, true);
            Assert.assertEquals(queueNum, globalIdMap.size());
        }
        //send and check
        sendMessagesAndCheck(producer, getBrokers(), topic, queueNum, msgEachQueue, 0);
        //consume and check
        consumeMessagesAndCheck(producer, consumer, topic, queueNum, msgEachQueue, 0, 1);
    }


    @Test
    public void testRemappingProduceConsumeStaticTopic() throws Exception {
        String topic = "static" + MQRandomUtils.getRandomTopic();
        RMQNormalProducer producer = getProducer(nsAddr, topic);
        RMQNormalConsumer consumer = getConsumer(nsAddr, topic, "*", new RMQNormalListener());

        int queueNum = 1;
        int msgEachQueue = 100;
        //create send consume
        {
            Set<String> targetBrokers = ImmutableSet.of(broker1Name);
            MQAdminTestUtils.createStaticTopic(topic, queueNum, targetBrokers, defaultMQAdminExt);
            sendMessagesAndCheck(producer, targetBrokers, topic, queueNum, msgEachQueue, 0);
            consumeMessagesAndCheck(producer, consumer, topic, queueNum, msgEachQueue, 0, 1);
        }
        //remapping the static topic
        {
            Set<String> targetBrokers = ImmutableSet.of(broker2Name);
            MQAdminTestUtils.remappingStaticTopic(topic, targetBrokers, defaultMQAdminExt);
            Map<String, TopicConfigAndQueueMapping> remoteBrokerConfigMap = MQAdminUtils.examineTopicConfigAll(topic, defaultMQAdminExt);
            TopicQueueMappingUtils.checkNameEpochNumConsistence(topic, remoteBrokerConfigMap);
            Map<Integer, TopicQueueMappingOne>  globalIdMap = TopicQueueMappingUtils.checkAndBuildMappingItems(new ArrayList<>(getMappingDetailFromConfig(remoteBrokerConfigMap.values())), false, true);
            Assert.assertEquals(queueNum, globalIdMap.size());
            for (TopicQueueMappingOne mappingOne: globalIdMap.values()) {
                Assert.assertEquals(broker2Name, mappingOne.getBname());
                Assert.assertEquals(TopicQueueMappingUtils.DEFAULT_BLOCK_SEQ_SIZE, mappingOne.getItems().get(mappingOne.getItems().size() - 1).getLogicOffset());
            }
            Thread.sleep(500);
            sendMessagesAndCheck(producer, targetBrokers, topic, queueNum, msgEachQueue, TopicQueueMappingUtils.DEFAULT_BLOCK_SEQ_SIZE);
            consumeMessagesAndCheck(producer, consumer, topic, queueNum, msgEachQueue, 0, 2);
        }
    }


    @Test
    public void testDoubleReadCheckConsumerOffset() throws Exception {
        String topic = "static" + MQRandomUtils.getRandomTopic();
        String group = initConsumerGroup();
        RMQNormalProducer producer = getProducer(nsAddr, topic);
        RMQNormalConsumer consumer = getConsumer(nsAddr, group, topic, "*", new RMQNormalListener());

        int queueNum = 10;
        int msgEachQueue = 100;
        //create static topic
        {
            Set<String> targetBrokers = ImmutableSet.of(broker1Name);
            MQAdminTestUtils.createStaticTopic(topic, queueNum, targetBrokers, defaultMQAdminExt);
            sendMessagesAndCheck(producer, targetBrokers, topic, queueNum, msgEachQueue, 0);
            consumeMessagesAndCheck(producer, consumer, topic, queueNum, msgEachQueue, 0, 1);
        }
        producer.shutdown();
        consumer.shutdown();
        //use a new producer
        producer = getProducer(nsAddr, topic);

        List<String> brokers = ImmutableList.of(broker2Name, broker3Name, broker1Name);
        for (int i = 0; i < brokers.size(); i++) {
            Set<String> targetBrokers = ImmutableSet.of(brokers.get(i));
            MQAdminTestUtils.remappingStaticTopic(topic, targetBrokers, defaultMQAdminExt);
            //make the metadata
            Thread.sleep(500);
            sendMessagesAndCheck(producer, targetBrokers, topic, queueNum, msgEachQueue, (i + 1) * TopicQueueMappingUtils.DEFAULT_BLOCK_SEQ_SIZE);
        }
        consumer = getConsumer(nsAddr, group, topic, "*", new RMQNormalListener());
        consumeMessagesAndCheck(producer, consumer, topic, queueNum, msgEachQueue, 1, brokers.size());
    }


    @Test
    public void testRemappingAndClear() throws Exception {
        String topic = "static" + MQRandomUtils.getRandomTopic();
        RMQNormalProducer producer = getProducer(nsAddr, topic);
        int queueNum = 10;
        int msgEachQueue = 100;
        //create to broker1Name
        {
            Set<String> targetBrokers = ImmutableSet.of(broker1Name);
            MQAdminTestUtils.createStaticTopic(topic, queueNum, targetBrokers, defaultMQAdminExt);
            //leave the time to refresh the metadata
            Thread.sleep(500);
            sendMessagesAndCheck(producer, targetBrokers, topic, queueNum, msgEachQueue, 0);
        }

        //remapping to broker2Name
        {
            Set<String> targetBrokers = ImmutableSet.of(broker2Name);
            MQAdminTestUtils.remappingStaticTopic(topic, targetBrokers, defaultMQAdminExt);
            //leave the time to refresh the metadata
            Thread.sleep(500);
            sendMessagesAndCheck(producer, targetBrokers, topic, queueNum, msgEachQueue, 1 * TopicQueueMappingUtils.DEFAULT_BLOCK_SEQ_SIZE);
        }

        //remapping to broker3Name
        {
            Set<String> targetBrokers = ImmutableSet.of(broker3Name);
            MQAdminTestUtils.remappingStaticTopic(topic, targetBrokers, defaultMQAdminExt);
            //leave the time to refresh the metadata
            Thread.sleep(500);
            sendMessagesAndCheck(producer, targetBrokers, topic, queueNum, msgEachQueue, 2 * TopicQueueMappingUtils.DEFAULT_BLOCK_SEQ_SIZE);
        }

        // 1 -> 2 -> 3, currently 1 should not has any mappings

        {
            for (int i = 0; i < 10; i++) {
                for (BrokerController brokerController: brokerControllerList) {
                    brokerController.getTopicQueueMappingCleanService().wakeup();
                }
                Thread.sleep(100);
            }
            Map<String, TopicConfigAndQueueMapping> brokerConfigMap = MQAdminUtils.examineTopicConfigAll(topic, defaultMQAdminExt);
            Assert.assertEquals(brokerNum, brokerConfigMap.size());
            TopicConfigAndQueueMapping config1 = brokerConfigMap.get(broker1Name);
            TopicConfigAndQueueMapping config2 = brokerConfigMap.get(broker2Name);
            TopicConfigAndQueueMapping config3 = brokerConfigMap.get(broker3Name);
            Assert.assertEquals(0, config1.getMappingDetail().getHostedQueues().size());
            Assert.assertEquals(queueNum, config2.getMappingDetail().getHostedQueues().size());

            Assert.assertEquals(queueNum, config3.getMappingDetail().getHostedQueues().size());

        }
        {
            Set<String> topics =  new HashSet<>(brokerController1.getTopicConfigManager().getTopicConfigTable().keySet());
            topics.remove(topic);
            brokerController1.getMessageStore().cleanUnusedTopic(topics);
            brokerController2.getMessageStore().cleanUnusedTopic(topics);
            for (int i = 0; i < 10; i++) {
                for (BrokerController brokerController: brokerControllerList) {
                    brokerController.getTopicQueueMappingCleanService().wakeup();
                }
                Thread.sleep(100);
            }

            Map<String, TopicConfigAndQueueMapping> brokerConfigMap = MQAdminUtils.examineTopicConfigAll(topic, defaultMQAdminExt);
            Assert.assertEquals(brokerNum, brokerConfigMap.size());
            TopicConfigAndQueueMapping config1 = brokerConfigMap.get(broker1Name);
            TopicConfigAndQueueMapping config2 = brokerConfigMap.get(broker2Name);
            TopicConfigAndQueueMapping config3 = brokerConfigMap.get(broker3Name);
            Assert.assertEquals(0, config1.getMappingDetail().getHostedQueues().size());
            Assert.assertEquals(queueNum, config2.getMappingDetail().getHostedQueues().size());
            Assert.assertEquals(queueNum, config3.getMappingDetail().getHostedQueues().size());
            //The first leader will clear it
            for (List<LogicQueueMappingItem> items : config1.getMappingDetail().getHostedQueues().values()) {
                Assert.assertEquals(3, items.size());
            }
            //The second leader do nothing
            for (List<LogicQueueMappingItem> items : config3.getMappingDetail().getHostedQueues().values()) {
                Assert.assertEquals(1, items.size());
            }
        }
    }


    @Test
    public void testRemappingWithNegativeLogicOffset() throws Exception {
        String topic = "static" + MQRandomUtils.getRandomTopic();
        RMQNormalProducer producer = getProducer(nsAddr, topic);
        int queueNum = 10;
        int msgEachQueue = 100;
        //create and send
        {
            Set<String> targetBrokers = ImmutableSet.of(broker1Name);
            MQAdminTestUtils.createStaticTopic(topic, queueNum, targetBrokers, defaultMQAdminExt);
            sendMessagesAndCheck(producer, targetBrokers, topic, queueNum, msgEachQueue, 0);
        }

        //remapping the static topic with -1 logic offset
        {
            Set<String> targetBrokers = ImmutableSet.of(broker2Name);
            MQAdminTestUtils.remappingStaticTopicWithNegativeLogicOffset(topic, targetBrokers, defaultMQAdminExt);
            Map<String, TopicConfigAndQueueMapping> remoteBrokerConfigMap = MQAdminUtils.examineTopicConfigAll(topic, defaultMQAdminExt);
            TopicQueueMappingUtils.checkNameEpochNumConsistence(topic, remoteBrokerConfigMap);
            Map<Integer, TopicQueueMappingOne>  globalIdMap = TopicQueueMappingUtils.checkAndBuildMappingItems(new ArrayList<>(getMappingDetailFromConfig(remoteBrokerConfigMap.values())), false, true);
            Assert.assertEquals(queueNum, globalIdMap.size());
            for (TopicQueueMappingOne mappingOne: globalIdMap.values()) {
                Assert.assertEquals(broker2Name, mappingOne.getBname());
                Assert.assertEquals(-1, mappingOne.getItems().get(mappingOne.getItems().size() - 1).getLogicOffset());
            }
            //leave the time to refresh the metadata
            Thread.sleep(500);
            //here the gen should be 0
            sendMessagesAndCheck(producer, targetBrokers, topic, queueNum, msgEachQueue, 0);
        }
    }


    @After
    public void tearDown() {
        System.setProperty("rocketmq.client.rebalance.waitInterval", "20000");
        super.shutdown();
    }

}