/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.agent.plugin.sinks.filecollect;

import org.apache.inlong.agent.common.AgentThreadFactory;
import org.apache.inlong.agent.conf.AgentConfiguration;
import org.apache.inlong.agent.conf.InstanceProfile;
import org.apache.inlong.agent.constant.CommonConstants;
import org.apache.inlong.agent.message.filecollect.SenderMessage;
import org.apache.inlong.agent.metrics.AgentMetricItem;
import org.apache.inlong.agent.metrics.AgentMetricItemSet;
import org.apache.inlong.agent.metrics.audit.AuditUtils;
import org.apache.inlong.agent.plugin.message.SequentialID;
import org.apache.inlong.agent.utils.AgentUtils;
import org.apache.inlong.agent.utils.ThreadUtils;
import org.apache.inlong.common.constant.ProtocolType;
import org.apache.inlong.common.metric.MetricRegister;
import org.apache.inlong.sdk.dataproxy.DefaultMessageSender;
import org.apache.inlong.sdk.dataproxy.ProxyClientConfig;
import org.apache.inlong.sdk.dataproxy.common.SendMessageCallback;
import org.apache.inlong.sdk.dataproxy.common.SendResult;
import org.apache.inlong.sdk.dataproxy.network.ProxysdkException;

import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.inlong.agent.constant.CommonConstants.DEFAULT_PROXY_BATCH_FLUSH_INTERVAL;
import static org.apache.inlong.agent.constant.CommonConstants.PROXY_BATCH_FLUSH_INTERVAL;
import static org.apache.inlong.agent.constant.FetcherConstants.AGENT_MANAGER_AUTH_SECRET_ID;
import static org.apache.inlong.agent.constant.FetcherConstants.AGENT_MANAGER_AUTH_SECRET_KEY;
import static org.apache.inlong.agent.constant.FetcherConstants.AGENT_MANAGER_VIP_HTTP_HOST;
import static org.apache.inlong.agent.constant.FetcherConstants.AGENT_MANAGER_VIP_HTTP_PORT;
import static org.apache.inlong.agent.constant.TaskConstants.DEFAULT_JOB_PROXY_SEND;
import static org.apache.inlong.agent.constant.TaskConstants.JOB_PROXY_SEND;
import static org.apache.inlong.agent.metrics.AgentMetricItem.KEY_INLONG_GROUP_ID;
import static org.apache.inlong.agent.metrics.AgentMetricItem.KEY_INLONG_STREAM_ID;
import static org.apache.inlong.agent.metrics.AgentMetricItem.KEY_PLUGIN_ID;

/**
 * proxy client
 */
public class SenderManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SenderManager.class);
    private static final SequentialID SEQUENTIAL_ID = SequentialID.getInstance();
    // cache for group and sender list, share the map cross agent lifecycle.
    private DefaultMessageSender sender;
    private LinkedBlockingQueue<AgentSenderCallback> resendQueue;
    private static final ThreadPoolExecutor EXECUTOR_SERVICE = new ThreadPoolExecutor(
            0, Integer.MAX_VALUE,
            1L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new AgentThreadFactory("sender-manager"));
    // sharing worker threads between sender client
    // in case of thread abusing.
    private ThreadFactory SHARED_FACTORY;
    private static final AtomicLong METRIC_INDEX = new AtomicLong(0);
    private final String managerHost;
    private final int managerPort;
    private final String netTag;
    private final String localhost;
    private final boolean isLocalVisit;
    private final int totalAsyncBufSize;
    private final int aliveConnectionNum;
    private final boolean isCompress;
    private final int msgType;
    private final boolean isFile;
    private final long maxSenderTimeout;
    private final int maxSenderRetry;
    private final long retrySleepTime;
    private final String inlongGroupId;
    private final int maxSenderPerGroup;
    private final String sourcePath;
    private final boolean proxySend;
    private volatile boolean shutdown = false;
    // metric
    private AgentMetricItemSet metricItemSet;
    private Map<String, String> dimensions;
    private int ioThreadNum;
    private boolean enableBusyWait;
    private String authSecretId;
    private String authSecretKey;
    protected int batchFlushInterval;
    protected InstanceProfile profile;
    private volatile boolean resendRunning = false;
    private volatile boolean started = false;

    public SenderManager(InstanceProfile profile, String inlongGroupId, String sourcePath) {
        AgentConfiguration conf = AgentConfiguration.getAgentConf();
        this.profile = profile;
        managerHost = conf.get(AGENT_MANAGER_VIP_HTTP_HOST);
        managerPort = conf.getInt(AGENT_MANAGER_VIP_HTTP_PORT);
        proxySend = profile.getBoolean(JOB_PROXY_SEND, DEFAULT_JOB_PROXY_SEND);
        localhost = profile.get(CommonConstants.PROXY_LOCAL_HOST, CommonConstants.DEFAULT_PROXY_LOCALHOST);
        netTag = profile.get(CommonConstants.PROXY_NET_TAG, CommonConstants.DEFAULT_PROXY_NET_TAG);
        isLocalVisit = profile.getBoolean(
                CommonConstants.PROXY_IS_LOCAL_VISIT, CommonConstants.DEFAULT_PROXY_IS_LOCAL_VISIT);
        totalAsyncBufSize = profile
                .getInt(
                        CommonConstants.PROXY_TOTAL_ASYNC_PROXY_SIZE,
                        CommonConstants.DEFAULT_PROXY_TOTAL_ASYNC_PROXY_SIZE);
        aliveConnectionNum = profile
                .getInt(
                        CommonConstants.PROXY_ALIVE_CONNECTION_NUM, CommonConstants.DEFAULT_PROXY_ALIVE_CONNECTION_NUM);
        isCompress = profile.getBoolean(
                CommonConstants.PROXY_IS_COMPRESS, CommonConstants.DEFAULT_PROXY_IS_COMPRESS);
        maxSenderPerGroup = profile.getInt(
                CommonConstants.PROXY_MAX_SENDER_PER_GROUP, CommonConstants.DEFAULT_PROXY_MAX_SENDER_PER_GROUP);
        msgType = profile.getInt(CommonConstants.PROXY_MSG_TYPE, CommonConstants.DEFAULT_PROXY_MSG_TYPE);
        maxSenderTimeout = profile.getInt(
                CommonConstants.PROXY_SENDER_MAX_TIMEOUT, CommonConstants.DEFAULT_PROXY_SENDER_MAX_TIMEOUT);
        maxSenderRetry = profile.getInt(
                CommonConstants.PROXY_SENDER_MAX_RETRY, CommonConstants.DEFAULT_PROXY_SENDER_MAX_RETRY);
        retrySleepTime = profile.getLong(
                CommonConstants.PROXY_RETRY_SLEEP, CommonConstants.DEFAULT_PROXY_RETRY_SLEEP);
        isFile = profile.getBoolean(CommonConstants.PROXY_IS_FILE, CommonConstants.DEFAULT_IS_FILE);
        ioThreadNum = profile.getInt(CommonConstants.PROXY_CLIENT_IO_THREAD_NUM,
                CommonConstants.DEFAULT_PROXY_CLIENT_IO_THREAD_NUM);
        enableBusyWait = profile.getBoolean(CommonConstants.PROXY_CLIENT_ENABLE_BUSY_WAIT,
                CommonConstants.DEFAULT_PROXY_CLIENT_ENABLE_BUSY_WAIT);
        batchFlushInterval = profile.getInt(PROXY_BATCH_FLUSH_INTERVAL, DEFAULT_PROXY_BATCH_FLUSH_INTERVAL);
        authSecretId = conf.get(AGENT_MANAGER_AUTH_SECRET_ID);
        authSecretKey = conf.get(AGENT_MANAGER_AUTH_SECRET_KEY);

        this.sourcePath = sourcePath;
        this.inlongGroupId = inlongGroupId;

        this.dimensions = new HashMap<>();
        dimensions.put(KEY_PLUGIN_ID, this.getClass().getSimpleName());
        String metricName = String.join("-", this.getClass().getSimpleName(),
                String.valueOf(METRIC_INDEX.incrementAndGet()));
        this.metricItemSet = new AgentMetricItemSet(metricName);
        MetricRegister.register(metricItemSet);
        resendQueue = new LinkedBlockingQueue<>();
    }

    public void Start() throws Exception {
        createMessageSender(inlongGroupId);
        EXECUTOR_SERVICE.execute(flushResendQueue());
        started = true;
    }

    public void Stop() {
        LOGGER.info("stop send manager");
        shutdown = true;
        if (!started) {
            return;
        }
        while (resendRunning) {
            AgentUtils.silenceSleepInMs(1);
        }
        closeMessageSender();
        LOGGER.info("stop send manager end");
    }

    private void closeMessageSender() {
        if (sender != null) {
            sender.close();
        }
    }

    private AgentMetricItem getMetricItem(Map<String, String> otherDimensions) {
        Map<String, String> dimensions = new HashMap<>();
        dimensions.put(KEY_PLUGIN_ID, this.getClass().getSimpleName());
        dimensions.putAll(otherDimensions);
        return this.metricItemSet.findMetricItem(dimensions);
    }

    private AgentMetricItem getMetricItem(String groupId, String streamId) {
        Map<String, String> dims = new HashMap<>();
        dims.put(KEY_INLONG_GROUP_ID, groupId);
        dims.put(KEY_INLONG_STREAM_ID, streamId);
        return getMetricItem(dims);
    }

    /**
     * createMessageSender
     *
     * @param tagName we use group id as tag name
     */
    private void createMessageSender(String tagName) throws Exception {

        ProxyClientConfig proxyClientConfig = new ProxyClientConfig(
                localhost, isLocalVisit, managerHost, managerPort, tagName, netTag, authSecretId, authSecretKey);
        proxyClientConfig.setTotalAsyncCallbackSize(totalAsyncBufSize);
        proxyClientConfig.setFile(isFile);
        proxyClientConfig.setAliveConnections(aliveConnectionNum);

        proxyClientConfig.setIoThreadNum(ioThreadNum);
        proxyClientConfig.setEnableBusyWait(enableBusyWait);
        proxyClientConfig.setProtocolType(ProtocolType.TCP);

        SHARED_FACTORY = new DefaultThreadFactory("agent-sender-manager-" + sourcePath,
                Thread.currentThread().isDaemon());

        DefaultMessageSender sender = new DefaultMessageSender(proxyClientConfig, SHARED_FACTORY);
        sender.setMsgtype(msgType);
        sender.setCompress(isCompress);
        this.sender = sender;
    }

    public void sendBatch(SenderMessage message) {
        while (!shutdown && !resendQueue.isEmpty()) {
            AgentUtils.silenceSleepInMs(retrySleepTime);
        }
        if (!shutdown) {
            sendBatchWithRetryCount(message, 0);
        }
    }

    /**
     * Send message to proxy by batch, use message cache.
     */
    private void sendBatchWithRetryCount(SenderMessage message, int retry) {
        boolean suc = false;
        while (!suc) {
            try {
                AgentSenderCallback cb = new AgentSenderCallback(message, retry);
                asyncSendByMessageSender(cb, message.getDataList(), message.getGroupId(),
                        message.getStreamId(), message.getDataTime(), SEQUENTIAL_ID.getNextUuid(),
                        maxSenderTimeout, TimeUnit.SECONDS, message.getExtraMap(), proxySend);
                getMetricItem(message.getGroupId(), message.getStreamId()).pluginSendCount.addAndGet(
                        message.getMsgCnt());
                suc = true;
            } catch (Exception exception) {
                suc = false;
                if (retry > maxSenderRetry) {
                    if (retry % 10 == 0) {
                        LOGGER.error("max retry reached, sample log Exception caught", exception);
                    }
                } else {
                    LOGGER.error("Exception caught", exception);
                }
                retry++;
                AgentUtils.silenceSleepInMs(retrySleepTime);
            }
        }
    }

    private void asyncSendByMessageSender(SendMessageCallback cb,
            List<byte[]> bodyList, String groupId, String streamId, long dataTime, String msgUUID,
            long timeout, TimeUnit timeUnit,
            Map<String, String> extraAttrMap, boolean isProxySend) throws ProxysdkException {
        sender.asyncSendMessage(cb, bodyList, groupId,
                streamId, dataTime, msgUUID,
                timeout, timeUnit, extraAttrMap, isProxySend);
    }

    /**
     * flushResendQueue
     *
     * @return thread runner
     */
    private Runnable flushResendQueue() {
        return () -> {
            AgentThreadFactory.nameThread(
                    "flushResendQueue-" + profile.getTaskId() + "-" + profile.getInstanceId());
            LOGGER.info("start flush resend queue {}:{}", inlongGroupId, sourcePath);
            resendRunning = true;
            while (!shutdown) {
                try {
                    AgentSenderCallback callback = resendQueue.poll(1, TimeUnit.SECONDS);
                    if (callback != null) {
                        sendBatchWithRetryCount(callback.message, callback.retry + 1);
                    }
                } catch (Exception ex) {
                    LOGGER.error("error caught", ex);
                } catch (Throwable t) {
                    ThreadUtils.threadThrowableHandler(Thread.currentThread(), t);
                } finally {
                    AgentUtils.silenceSleepInMs(batchFlushInterval);
                }
            }
            LOGGER.info("stop flush resend queue {}:{}", inlongGroupId, sourcePath);
            resendRunning = false;
        };
    }

    /**
     * put the data into resend queue and will be resent later.
     *
     * @param batchMessageCallBack
     */
    private void putInResendQueue(AgentSenderCallback batchMessageCallBack) {
        try {
            resendQueue.put(batchMessageCallBack);
        } catch (Throwable throwable) {
            LOGGER.error("putInResendQueue e = {}", throwable);
        }
    }

    public boolean sendFinished() {
        return true;
    }

    /**
     * sender callback
     */
    private class AgentSenderCallback implements SendMessageCallback {

        private final int retry;
        private final SenderMessage message;
        private final int msgCnt;

        AgentSenderCallback(SenderMessage message, int retry) {
            this.message = message;
            this.retry = retry;
            this.msgCnt = message.getDataList().size();
        }

        @Override
        public void onMessageAck(SendResult result) {
            String groupId = message.getGroupId();
            String streamId = message.getStreamId();
            String taskId = message.getTaskId();
            String instanceId = message.getInstanceId();
            long dataTime = message.getDataTime();
            if (result != null && result.equals(SendResult.OK)) {
                message.getOffsetAckList().forEach(ack -> ack.setHasAck(true));
                getMetricItem(groupId, streamId).pluginSendSuccessCount.addAndGet(msgCnt);
                AuditUtils.add(AuditUtils.AUDIT_ID_AGENT_SEND_SUCCESS, groupId, streamId,
                        dataTime, message.getMsgCnt(), message.getTotalSize());
            } else {
                LOGGER.warn("send groupId {}, streamId {}, taskId {}, instanceId {}, dataTime {} fail with times {}, "
                        + "error {}", groupId, streamId, taskId, instanceId, dataTime, retry, result);
                getMetricItem(groupId, streamId).pluginSendFailCount.addAndGet(msgCnt);
                putInResendQueue(new AgentSenderCallback(message, retry));
            }
        }

        @Override
        public void onException(Throwable e) {
            getMetricItem(message.getGroupId(), message.getStreamId()).pluginSendFailCount.addAndGet(msgCnt);
            LOGGER.error("exception caught", e);
        }
    }
}
