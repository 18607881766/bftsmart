package bftsmart.tom.leaderchange;

import bftsmart.tom.core.TOMLayer;
import org.apache.commons.collections4.map.LRUMap;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This thread serves as a manager for all timers of pending requests.
 *
 */
public class HeartBeatTimer {

    // 重复发送LeaderRequest的间隔时间
    private static final long RESEND_MILL_SECONDS = 10000;

    private static final long DELAY_MILL_SECONDS = 30000;

    private static final long LEADER_DELAY_MILL_SECONDS = 20000;

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(HeartBeatTimer.class);

    private final Map<Long, List<LeaderResponseMessage>> leaderResponseMap = new LRUMap<>(1024 * 8);

    private ScheduledExecutorService leaderTimer = Executors.newSingleThreadScheduledExecutor();

    private ScheduledExecutorService replicaTimer = Executors.newSingleThreadScheduledExecutor();

    private ScheduledExecutorService leaderResponseTimer = Executors.newSingleThreadScheduledExecutor();

    private RequestsTimer requestsTimer;

    private TOMLayer tomLayer; // TOM layer

    private volatile boolean isSendLeaderRequest = false;

    private volatile long lastSendLeaderRequestTime = -1L;

    private volatile InnerHeartBeatMessage innerHeartBeatMessage;

    private volatile long lastLeaderRequestSequence = -1L;

    private Lock lrLock = new ReentrantLock();

    private Lock hbLock = new ReentrantLock();

    public HeartBeatTimer() {

    }

    public void start() {
        leaderTimerStart();
        replicaTimerStart();
    }

    public void restart() {
        stopAll();
        start();
    }

    public void leaderTimerStart() {
        // stop Replica timer，and start leader timer
        if (leaderTimer == null) {
            leaderTimer = Executors.newSingleThreadScheduledExecutor();
        }
        leaderTimer.scheduleWithFixedDelay(new LeaderTimerTask(), LEADER_DELAY_MILL_SECONDS,
                tomLayer.controller.getStaticConf().getHeartBeatPeriod(), TimeUnit.MILLISECONDS);
    }

    public void replicaTimerStart() {
        if (replicaTimer == null) {
            replicaTimer = Executors.newSingleThreadScheduledExecutor();
        }
        replicaTimer.scheduleWithFixedDelay(new ReplicaTimerTask(), DELAY_MILL_SECONDS,
                tomLayer.controller.getStaticConf().getHeartBeatTimeout(), TimeUnit.MILLISECONDS);
    }

    public void stopAll() {
        if (replicaTimer != null) {
            replicaTimer.shutdownNow();
        }
        if (leaderTimer != null) {
            leaderTimer.shutdownNow();
        }
        replicaTimer = null;
        leaderTimer = null;
    }

    public void shutdown() {
        stopAll();
    }

    /**
     * 收到心跳消息
     * @param heartBeatMessage
     */
    public void receiveHeartBeatMessage(HeartBeatMessage heartBeatMessage) {
        hbLock.lock();
        try {
            // todo 此处逻辑有问题，需要再次考虑
            // 需要考虑是否每次都更新innerHeartBeatMessage
            if (heartBeatMessage.getLeader() == tomLayer.leader()) {
//                System.out.printf("I am proc %s , receive heart beat from %s , time = %s \r\n",
//                        tomLayer.controller.getStaticConf().getProcessId(), heartBeatMessage.getLeader(), System.currentTimeMillis());
                innerHeartBeatMessage = new InnerHeartBeatMessage(System.currentTimeMillis(), heartBeatMessage);
                if (heartBeatMessage.getLastRegency() != tomLayer.getSynchronizer().getLCManager().getLastReg()) {
                    sendLeaderRequestMessage();
                }
            } else {
                sendLeaderRequestMessage();
            }
        } finally {
            hbLock.unlock();
        }
    }

    /**
     * 收到领导者请求
     * @param requestMessage
     */
    public void receiveLeaderRequestMessage(LeaderRequestMessage requestMessage) {
        // 获取当前节点的领导者信息，然后应答给发送者
        int currLeader = tomLayer.leader();
        LeaderResponseMessage responseMessage = new LeaderResponseMessage(
                tomLayer.controller.getStaticConf().getProcessId(), currLeader, requestMessage.getSequence(),
                tomLayer.getSynchronizer().getLCManager().getLastReg());
        int[] to = new int[1];
        to[0] = requestMessage.getSender();
//        System.out.printf("I am proc %s , receive leader request from %s \r\n",
//                tomLayer.controller.getStaticConf().getProcessId(), requestMessage.getSender());
        tomLayer.getCommunication().send(to, responseMessage);
//        System.out.printf("I am proc %s , send leader[%s] response to %s \r\n",
//                tomLayer.controller.getStaticConf().getProcessId(), currLeader, to[0]);
    }

    /**
     * 收到领导者应答请求
     * @param responseMessage
     */
    public void receiveLeaderResponseMessage(LeaderResponseMessage responseMessage) {
        // 判断是否是自己发送的sequence
        lrLock.lock();
        try {
            long msgSeq = responseMessage.getSequence();
//            System.out.printf("I am proc %s , receive leader %s response from %s \r\n",
//                    tomLayer.controller.getStaticConf().getProcessId(), responseMessage.getLeader(), responseMessage.getSender());
            if (msgSeq == lastLeaderRequestSequence) {
                // 是当前节点发送的请求，则将其加入到Map中
                List<LeaderResponseMessage> responseMessages = leaderResponseMap.get(msgSeq);
                if (responseMessages == null) {
                    responseMessages = new ArrayList<>();
                    responseMessages.add(responseMessage);
                    leaderResponseMap.put(msgSeq, responseMessages);
                } else {
                    responseMessages.add(responseMessage);
                }
                // 判断收到的心跳信息是否满足
                NewLeader newLeader = newLeader(responseMessage.getSequence());
                if (newLeader != null) {
                    if (leaderResponseTimer != null) {
                        leaderResponseTimer.shutdownNow(); // 取消定时器
                        leaderResponseTimer = null;
                    }
                    // 表示满足条件，设置新的Leader与regency
                    // 如果我本身是领导者，又收到来自其他领导者的心跳，经过领导者查询之后需要取消一个领导者定时器
                    if ((tomLayer.leader() != newLeader.getNewLeader()) || (tomLayer.getSynchronizer().getLCManager().getLastReg() != newLeader.getLastRegency())) {
                        // 重置leader和regency
                        tomLayer.execManager.setNewLeader(newLeader.getNewLeader()); // 设置新的Leader
                        tomLayer.getSynchronizer().getLCManager().setNewLeader(newLeader.getNewLeader()); // 设置新的Leader
                        tomLayer.getSynchronizer().getLCManager().setNextReg(newLeader.getLastRegency());
                        tomLayer.getSynchronizer().getLCManager().setLastReg(newLeader.getLastRegency());
//                        System.out.printf("I am proc %s , set new leader %s last regency %s \r\n",
//                                tomLayer.controller.getStaticConf().getProcessId(), newLeader.getNewLeader(),
//                                newLeader.getLastRegency());
                        // 重启定时器
                        restart();
                    }
                    //重置last regency以后，所有在此之前添加的stop 重传定时器需要取消
                    tomLayer.getSynchronizer().removeSTOPretransmissions(tomLayer.getSynchronizer().getLCManager().getLastReg());
                    isSendLeaderRequest = false;
                }
            } else {
                // 收到的心跳信息有问题，打印日志
                LOGGER.error("I am proc {} , receive leader response from {}, last sequence {}, receive sequence {}",
                        tomLayer.controller.getStaticConf().getProcessId(), responseMessage.getSender(),
                        lastLeaderRequestSequence, msgSeq);
            }
        } finally {
            lrLock.unlock();
        }
    }

    public void setTomLayer(TOMLayer tomLayer) {
        this.tomLayer = tomLayer;
    }

    public void sendLeaderRequestMessage() {
        // 假设收到的消息不是当前的Leader，则需要发送获取其他节点Leader
        long sequence = System.currentTimeMillis();
        lrLock.lock();
        try {
            // 防止一段时间内重复发送多次心跳请求
            if (!isSendLeaderRequest || (sequence - lastSendLeaderRequestTime) > 60000L) {
                lastSendLeaderRequestTime = sequence;
                sendLeaderRequestMessage(sequence);
            }
        } finally {
            lrLock.unlock();
        }
    }

    private void sendLeaderRequestMessage(long sequence) {
        LeaderRequestMessage requestMessage = new LeaderRequestMessage(
                tomLayer.controller.getStaticConf().getProcessId(), sequence);
//        System.out.printf("I am %s, send leader request message \r\n", tomLayer.controller.getStaticConf().getProcessId());
        tomLayer.getCommunication().send(tomLayer.controller.getCurrentViewOtherAcceptors(), requestMessage);
        lastLeaderRequestSequence = sequence;
        isSendLeaderRequest = true;
        // 启动定时任务，判断心跳的应答处理
        if (leaderResponseTimer == null) {
            leaderResponseTimer = Executors.newSingleThreadScheduledExecutor();
            leaderResponseTimer.scheduleWithFixedDelay(new LeaderResponseTask(lastLeaderRequestSequence), 0, RESEND_MILL_SECONDS, TimeUnit.MILLISECONDS);
        } else {
            leaderResponseTimer.shutdownNow();
            if (leaderResponseTimer.isShutdown()) {
                // 重新开启新的任务
                leaderResponseTimer = Executors.newSingleThreadScheduledExecutor();
                leaderResponseTimer.scheduleWithFixedDelay(new LeaderResponseTask(lastLeaderRequestSequence), 0, RESEND_MILL_SECONDS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * 获取新的Leader
     * @return
     *     返回null表示未达成一致，否则表示达成一致
     */
    private NewLeader newLeader(long currentSequence) {
        // 从缓存中获取应答
        List<LeaderResponseMessage> leaderResponseMessages = leaderResponseMap.get(currentSequence);
        if (leaderResponseMessages == null || leaderResponseMessages.isEmpty()) {
            return null;
        } else {
            return newLeaderCheck(leaderResponseMessages);
        }
    }

    /**
     * 新领导者check过程
     * @param leaderResponseMessages
     * @return
     */
    private NewLeader newLeaderCheck(List<LeaderResponseMessage> leaderResponseMessages) {
        // 判断收到的应答结果是不是满足2f+1的规则
        Map<Integer, Integer> leader2Size = new HashMap<>();
        Map<Integer, Integer> regency2Size = new HashMap<>();
        // 防止重复
        Set<Integer> nodeSet = new HashSet<>();
        for (LeaderResponseMessage lrm : leaderResponseMessages) {
            int currentLeader = lrm.getLeader();
            int currentRegency = lrm.getLastRegency();
            int currentNode = lrm.getSender();
            if (!nodeSet.contains(currentNode)) {
                leader2Size.merge(currentLeader, 1, Integer::sum);
                regency2Size.merge(currentRegency, 1, Integer::sum);
                nodeSet.add(currentNode);
            }
        }
        // 获取leaderSize最大的Leader
        int leaderMaxSize = -1;
        int leaderMaxId = -1;
        for (Map.Entry<Integer, Integer> entry : leader2Size.entrySet()) {
            int currLeaderId = entry.getKey(), currLeaderSize = entry.getValue();
            if (currLeaderSize > leaderMaxSize) {
                leaderMaxId = currLeaderId;
                leaderMaxSize = currLeaderSize;
            }
        }

        // 获取leaderSize最大的Leader
        int regencyMaxSize = -1;
        int regencyMaxId = -1;
        for (Map.Entry<Integer, Integer> entry : regency2Size.entrySet()) {
            int currRegency = entry.getKey(), currRegencySize = entry.getValue();
            if (currRegencySize > regencyMaxSize) {
                regencyMaxId = currRegency;
                regencyMaxSize = currRegencySize;
            }
        }

        // 判断是否满足2f+1
        int compareLeaderSize = 2 * tomLayer.controller.getStaticConf().getF() + 1;
        int compareRegencySize = 2 * tomLayer.controller.getStaticConf().getF() + 1;
        if (leaderMaxSize >= compareLeaderSize && regencyMaxSize >= compareRegencySize) {
            return new NewLeader(leaderMaxId, regencyMaxId);
        }
        return null;
    }

    /**
     *
     */
    class LeaderTimerTask implements Runnable {

        @Override
        public void run() {
            try {
                // 再次判断是否是Leader
                if (tomLayer.isLeader()) {
                    if (!tomLayer.isConnectRemotesOK()) {
                        return;
                    }
                    // 如果是Leader则发送心跳信息给其他节点，当前节点除外
                    HeartBeatMessage heartBeatMessage = new HeartBeatMessage(tomLayer.controller.getStaticConf().getProcessId(),
                            tomLayer.controller.getStaticConf().getProcessId(), tomLayer.getSynchronizer().getLCManager().getLastReg());
                    tomLayer.getCommunication().send(tomLayer.controller.getCurrentViewOtherAcceptors(), heartBeatMessage);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    class LeaderResponseTask implements Runnable {

        private final long currentSequence;

        LeaderResponseTask(long currentSequence) {
            this.currentSequence = currentSequence;
        }

        @Override
        public void run() {
            lrLock.lock();
            try {
                NewLeader newLeader = newLeader(currentSequence);
                if (newLeader != null) {
                    // 满足，则更新当前节点的Leader
                    tomLayer.execManager.setNewLeader(newLeader.getNewLeader());
                    tomLayer.getSynchronizer().getLCManager().setNextReg(newLeader.getLastRegency());
                    tomLayer.getSynchronizer().getLCManager().setLastReg(newLeader.getLastRegency());
                    leaderResponseTimer.shutdownNow();
                    leaderResponseTimer = null;
                    isSendLeaderRequest = false;
                }
            } finally {
                lrLock.unlock();
            }
        }
    }

    class ReplicaTimerTask extends TimerTask {

        @Override
        public void run() {
            // 再次判断是否是Leader
            if (!tomLayer.isLeader()) {
                // 检查收到的InnerHeartBeatMessage是否超时
                hbLock.lock();
                try {
                    // 需要判断所有连接是否已经成功建立
                    if (!tomLayer.isConnectRemotesOK()) {
                        return;
                    }
                    if (innerHeartBeatMessage == null) {
                        // 此处触发超时
                        LOGGER.info("I am proc {} trigger hb timeout, because heart beat message is NULL !!!", tomLayer.controller.getStaticConf().getProcessId());
                        if (tomLayer.requestsTimer != null) {
                            tomLayer.requestsTimer.run_lc_protocol();
                        }
                    } else {
                        // 判断时间
                        long lastTime = innerHeartBeatMessage.getTime();
                        if (System.currentTimeMillis() - lastTime > tomLayer.controller.getStaticConf().getHeartBeatTimeout()) {
                            // 此处触发超时
                            LOGGER.info("I am proc {} trigger hb timeout, time = {}, last hb time = {}", tomLayer.controller.getStaticConf().getProcessId(), System.currentTimeMillis(), innerHeartBeatMessage.getTime());
                            if (tomLayer.requestsTimer != null) {
                                tomLayer.requestsTimer.run_lc_protocol();
                            }
                        }
                    }
                } finally {
                    hbLock.unlock();
                }
            }
        }
    }
    
    class InnerHeartBeatMessage {

        private long time;

        private HeartBeatMessage heartBeatMessage;

        public InnerHeartBeatMessage(long time, HeartBeatMessage heartBeatMessage) {
            this.time = time;
            this.heartBeatMessage = heartBeatMessage;
        }

        public long getTime() {
            return time;
        }

        public HeartBeatMessage getHeartBeatMessage() {
            return heartBeatMessage;
        }
    }

    class NewLeader {

        private int newLeader;

        private int lastRegency;

        public NewLeader(int newLeader, int lastRegency) {
            this.newLeader = newLeader;
            this.lastRegency = lastRegency;
        }

        public int getNewLeader() {
            return newLeader;
        }

        public int getLastRegency() {
            return lastRegency;
        }
    }
}
