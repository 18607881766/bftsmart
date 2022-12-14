package test.bftsmart.leaderchange;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import bftsmart.communication.MessageHandler;
import bftsmart.communication.ServerCommunicationSystem;
import bftsmart.communication.ServerCommunicationSystemImpl;
import bftsmart.tom.ServiceReplica;
import bftsmart.tom.leaderchange.HeartBeatMessage;
import bftsmart.tom.leaderchange.LCMessage;
import bftsmart.tom.leaderchange.LeaderResponseMessage;

/**
 * @Author: zhangshuang
 * @Date: 2020/3/4 11:33 AM
 * Version 1.0
 */
public class HeartBeatForOtherSizeTest_ {

    private static final ExecutorService nodeStartPools = Executors.newCachedThreadPool();

    private ServiceReplica[] serviceReplicas;

    private TestNodeServer[] serverNodes;

//    private HeartBeatTimer[] mockHbTimers;

    private ServerCommunicationSystemImpl[] serverCommunicationSystems;

    /**
     * leader超时重启，然后下一个被选中的leader也如此，检查是否可正常
     */
    @Test
    public void leaderHbTimeoutAndRestartAndNextLeaderRepeatTest() {

        int nodeNum = 5;

        initNode(nodeNum);

        // 重新设置leader的消息处理方式
        MessageHandler mockMessageHandler = spy(serverCommunicationSystems[0].getMessageHandler());

        // mock messageHandler对消息应答的处理
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] objs = invocationOnMock.getArguments();
                if (objs == null || objs.length != 1) {
                    invocationOnMock.callRealMethod();
                } else {
                    Object obj = objs[0];
                    if (obj instanceof LCMessage) {
                        // 走我们设计的逻辑，即不处理
                        System.out.println("0 receive leader change message !");
                    } else if (obj instanceof LeaderResponseMessage) {
                        System.out.println("0 receive leader response message !");
                        invocationOnMock.callRealMethod();
                    } else if (obj instanceof HeartBeatMessage) {
                        System.out.printf("0 receive heart beat message from %s !\r\n", ((HeartBeatMessage) obj).getSender());
                        invocationOnMock.callRealMethod();
                    } else {
                        invocationOnMock.callRealMethod();
                    }
                }
                return null;
            }
        }).when(mockMessageHandler).processData(any());

        serverCommunicationSystems[0].setMessageHandler(mockMessageHandler);

        // 领导者心跳停止
        stopLeaderHeartBeat(serviceReplicas);

        System.out.println("-- stopLeaderHeartBeat --");

        try {
            // 休眠20s
            Thread.sleep(60000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 重启之前领导者心跳服务
        restartLeaderHeartBeat(serviceReplicas, 0);

        System.out.println("-- restartLeaderHeartBeat --");

        // 重置mock操作
        reset(mockMessageHandler);

        try {
            Thread.sleep(30000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 当前领导者异常，当前领导者为peer1
        mockMessageHandler = spy(serverCommunicationSystems[1].getMessageHandler());

        // mock messageHandler对消息应答的处理
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] objs = invocationOnMock.getArguments();
                if (objs == null || objs.length != 1) {
                    invocationOnMock.callRealMethod();
                } else {
                    Object obj = objs[0];
                    if (obj instanceof LCMessage) {
                        // 走我们设计的逻辑，即不处理
                        System.out.println("1 receive leader change message !");
                    } else if (obj instanceof LeaderResponseMessage) {
                        System.out.println("1 receive leader response message !");
                        invocationOnMock.callRealMethod();
                    } else if (obj instanceof HeartBeatMessage) {
                        System.out.printf("1 receive heart beat message from %s !\r\n", ((HeartBeatMessage) obj).getSender());
                        invocationOnMock.callRealMethod();
                    } else {
                        invocationOnMock.callRealMethod();
                    }
                }
                return null;
            }
        }).when(mockMessageHandler).processData(any());

        serverCommunicationSystems[1].setMessageHandler(mockMessageHandler);

        // 领导者心跳停止
        stopLeaderHeartBeat(serviceReplicas);

        System.out.println("-- stopLeaderHeartBeat --");

        try {
            // 休眠20s
            Thread.sleep(60000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 重启之前领导者心跳服务
        restartLeaderHeartBeat(serviceReplicas, 1);

        System.out.println("-- restartLeaderHeartBeat --");

        // 重置mock操作
        reset(mockMessageHandler);

        try {
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 测试5个节点循环进行一次领导者切换，看最终是否可以恢复正常
     */
    @Test
    public void test5NodeLeaderChange() {

        int nodeNums = 5;

        initNode(nodeNums);

        for (int i = 0; i < nodeNums; i++) {

            final int index = i;

            // 重新设置leader的消息处理方式
            MessageHandler mockMessageHandler = spy(serverCommunicationSystems[i].getMessageHandler());

            // mock messageHandler对消息应答的处理
            doAnswer(new Answer() {
                @Override
                public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                    Object[] objs = invocationOnMock.getArguments();
                    if (objs == null || objs.length != 1) {
                        invocationOnMock.callRealMethod();
                    } else {
                        Object obj = objs[0];
                        if (obj instanceof LCMessage) {
                            // 走我们设计的逻辑，即不处理
                            System.out.println(index + " receive leader change message !");
                        } else if (obj instanceof LeaderResponseMessage) {
                            System.out.println(index + " receive leader response message !");
                            invocationOnMock.callRealMethod();
                        } else if (obj instanceof HeartBeatMessage) {
                            System.out.printf(index + " receive heart beat message from %s !\r\n", ((HeartBeatMessage) obj).getSender());
                            invocationOnMock.callRealMethod();
                        } else {
                            invocationOnMock.callRealMethod();
                        }
                    }
                    return null;
                }
            }).when(mockMessageHandler).processData(any());

            serverCommunicationSystems[index].setMessageHandler(mockMessageHandler);

            // 领导者心跳停止
            stopLeaderHeartBeat(serviceReplicas);

            System.out.printf("-- stop %s LeaderHeartBeat -- \r\n", index);

            try {
                // 休眠40s，等待领导者切换完成
                Thread.sleep(40000);
            } catch (Exception e) {
                e.printStackTrace();
            }

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 5 nodes , 第5个节点前期一直处于网络异常中， 其余四个节点循环当领导者，当领导者再次选为0时，regency已经进行了很多轮，
     * 此时第5个节点网络恢复，虽然当前领导者与心跳来源的领导者一致，但regency已经千差万别，需要更新处理；
     */
    @Test
    public void test5NodeLeaderChangeOneUnleaderException() {

        int nodeNums = 5;

        initNode(nodeNums);

        MessageHandler mockMessageHandler4 = spy(serverCommunicationSystems[4].getMessageHandler());

        // mock messageHandler4对消息应答的处理, 让节点4网络异常
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] objs = invocationOnMock.getArguments();
                if (objs == null || objs.length != 1) {
                    invocationOnMock.callRealMethod();
                } else {
                    Object obj = objs[0];
                    if ((obj instanceof LCMessage) || (obj instanceof LeaderResponseMessage) || (obj instanceof HeartBeatMessage) ) {
                        // 走我们设计的逻辑，即不处理
                    } else {
                        invocationOnMock.callRealMethod();
                    }
                }
                return null;
            }
        }).when(mockMessageHandler4).processData(any());

        serverCommunicationSystems[4].setMessageHandler(mockMessageHandler4);

        // 停止节点5因为心跳超时而重传STOP消息
        serviceReplicas[4].getTomLayer().heartBeatTimer.stopAll();


        //让0,1,2,3节点依次当领导者，并进行网络异常与恢复的模拟操作
        for (int i = 0; i < nodeNums - 1; i++) {

            MessageHandler mockMessageHandler = stopNode(i);

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, i);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", i);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 第5个节点网络恢复
        System.out.println("-- Node 5 network recovery --");
        reset(mockMessageHandler4);

        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 测试6个节点循环进行一次领导者切换，看最终是否可以恢复正常
     */
    @Test
    public void test6NodeLeaderChange() {

        int nodeNums = 6;

        initNode(nodeNums);

        for (int i = 0; i < nodeNums; i++) {

            final int index = i;

            // 重新设置leader的消息处理方式
            MessageHandler mockMessageHandler = spy(serverCommunicationSystems[i].getMessageHandler());

            // mock messageHandler对消息应答的处理
            doAnswer(new Answer() {
                @Override
                public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                    Object[] objs = invocationOnMock.getArguments();
                    if (objs == null || objs.length != 1) {
                        invocationOnMock.callRealMethod();
                    } else {
                        Object obj = objs[0];
                        if (obj instanceof LCMessage) {
                            // 走我们设计的逻辑，即不处理
                            System.out.println(index + " receive leader change message !");
                        } else if (obj instanceof LeaderResponseMessage) {
                            System.out.println(index + " receive leader response message !");
                            invocationOnMock.callRealMethod();
                        } else if (obj instanceof HeartBeatMessage) {
                            System.out.printf(index + " receive heart beat message from %s !\r\n", ((HeartBeatMessage) obj).getSender());
                            invocationOnMock.callRealMethod();
                        } else {
                            invocationOnMock.callRealMethod();
                        }
                    }
                    return null;
                }
            }).when(mockMessageHandler).processData(any());

            serverCommunicationSystems[index].setMessageHandler(mockMessageHandler);

            // 领导者心跳停止
            stopLeaderHeartBeat(serviceReplicas);

            System.out.printf("-- stop %s LeaderHeartBeat -- \r\n", index);

            try {
                // 休眠40s，等待领导者切换完成
                Thread.sleep(40000);
            } catch (Exception e) {
                e.printStackTrace();
            }

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void test6NodeLeaderChangeBut1ContinueException() {

        int nodeNums = 6;

        initNode(nodeNums);

        // 停掉第一个节点
        stopNode(0);

        /**
         * 预期结果：
         * 第二个节点触发异常，则会选举三，直到最后一个节点异常，则重新选举1，但是因为1一直异常，所以一会儿超时会选举2
         */
        for (int i = 1; i < nodeNums; i++) {

            final int index = i;

            MessageHandler mockMessageHandler = stopNode(index);

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void test6NodeLeaderChangeBut2ContinueException() {

        int nodeNums = 6;

        initNode(nodeNums);

        // 停掉第5个节点
        MessageHandler mockMessageHandler4 = spy(serverCommunicationSystems[4].getMessageHandler());

        // mock messageHandler4对消息应答的处理, 让节点4网络异常
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] objs = invocationOnMock.getArguments();
                if (objs == null || objs.length != 1) {
                    invocationOnMock.callRealMethod();
                } else {
                    Object obj = objs[0];
                    if ((obj instanceof LCMessage) || (obj instanceof LeaderResponseMessage) || (obj instanceof HeartBeatMessage) ) {
                        // 走我们设计的逻辑，即不处理
                    } else {
                        invocationOnMock.callRealMethod();
                    }
                }
                return null;
            }
        }).when(mockMessageHandler4).processData(any());

        serverCommunicationSystems[4].setMessageHandler(mockMessageHandler4);

        // 停止节点4因为心跳超时而重传STOP消息
        serviceReplicas[4].getTomLayer().heartBeatTimer.stopAll();

        // 停掉第6个节点
        MessageHandler mockMessageHandler5 = spy(serverCommunicationSystems[5].getMessageHandler());

        // mock messageHandler5对消息应答的处理, 让节点5网络异常
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] objs = invocationOnMock.getArguments();
                if (objs == null || objs.length != 1) {
                    invocationOnMock.callRealMethod();
                } else {
                    Object obj = objs[0];
                    if ((obj instanceof LCMessage) || (obj instanceof LeaderResponseMessage) || (obj instanceof HeartBeatMessage) ) {
                        // 走我们设计的逻辑，即不处理
                    } else {
                        invocationOnMock.callRealMethod();
                    }
                }
                return null;
            }
        }).when(mockMessageHandler5).processData(any());

        serverCommunicationSystems[5].setMessageHandler(mockMessageHandler5);

        // 停止节点4因为心跳超时而重传STOP消息
        serviceReplicas[5].getTomLayer().heartBeatTimer.stopAll();

        /**
         * 预期结果：
         * 第1个节点触发异常，则会选举2，直到第4个节点异常，则重新选举5，但是因为第5，6个节点一直异常，所以一会儿超时会重新选举0
         */
        for (int i = 0; i < nodeNums - 2; i++) {

            final int index = i;

            MessageHandler mockMessageHandler = stopNode(index);

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 第5个节点网络恢复
        System.out.println("-- Node 5 network recovery --");
        reset(mockMessageHandler4);

        // 第6个节点网络恢复
        System.out.println("-- Node 6 network recovery --");
        reset(mockMessageHandler5);

        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 测试7个节点循环进行一次领导者切换，看最终是否可以恢复正常
     */
    @Test
    public void test7NodeLeaderChange() {

        int nodeNums = 7;

        initNode(nodeNums);

        for (int i = 0; i < nodeNums; i++) {

            final int index = i;

            // 重新设置leader的消息处理方式
            MessageHandler mockMessageHandler = spy(serverCommunicationSystems[i].getMessageHandler());

            // mock messageHandler对消息应答的处理
            doAnswer(new Answer() {
                @Override
                public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                    Object[] objs = invocationOnMock.getArguments();
                    if (objs == null || objs.length != 1) {
                        invocationOnMock.callRealMethod();
                    } else {
                        Object obj = objs[0];
                        if (obj instanceof LCMessage) {
                            // 走我们设计的逻辑，即不处理
                            System.out.println(index + " receive leader change message !");
                        } else if (obj instanceof LeaderResponseMessage) {
                            System.out.println(index + " receive leader response message !");
                            invocationOnMock.callRealMethod();
                        } else if (obj instanceof HeartBeatMessage) {
                            System.out.printf(index + " receive heart beat message from %s !\r\n", ((HeartBeatMessage) obj).getSender());
                            invocationOnMock.callRealMethod();
                        } else {
                            invocationOnMock.callRealMethod();
                        }
                    }
                    return null;
                }
            }).when(mockMessageHandler).processData(any());

            serverCommunicationSystems[index].setMessageHandler(mockMessageHandler);

            // 领导者心跳停止
            stopLeaderHeartBeat(serviceReplicas);

            System.out.printf("-- stop %s LeaderHeartBeat -- \r\n", index);

            try {
                // 休眠40s，等待领导者切换完成
                Thread.sleep(40000);
            } catch (Exception e) {
                e.printStackTrace();
            }

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, index);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", index);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 7 nodes , 第7个节点前期一直处于网络异常中， 其余6个节点循环当领导者，当领导者再次选为0时，regency已经进行了很多轮，
     * 此时第7个节点网络恢复，虽然当前领导者与心跳来源的领导者一致，但regency已经千差万别，需要更新处理；
     */
    @Test
    public void test7NodeLeaderChangeOneUnleaderException() {

        int nodeNums = 7;

        initNode(nodeNums);

        MessageHandler mockMessageHandler6 = spy(serverCommunicationSystems[6].getMessageHandler());

        // mock messageHandler6对消息应答的处理, 让节点7网络异常
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] objs = invocationOnMock.getArguments();
                if (objs == null || objs.length != 1) {
                    invocationOnMock.callRealMethod();
                } else {
                    Object obj = objs[0];
                    if ((obj instanceof LCMessage) || (obj instanceof LeaderResponseMessage) || (obj instanceof HeartBeatMessage) ) {
                        // 走我们设计的逻辑，即不处理
                    } else {
                        invocationOnMock.callRealMethod();
                    }
                }
                return null;
            }
        }).when(mockMessageHandler6).processData(any());

        serverCommunicationSystems[6].setMessageHandler(mockMessageHandler6);

        // 停止节点7因为心跳超时而重传STOP消息
        serviceReplicas[6].getTomLayer().heartBeatTimer.stopAll();


        //让1，2，3，4，5，6节点依次当领导者，并进行网络异常与恢复的模拟操作
        for (int i = 0; i < nodeNums - 1; i++) {

            MessageHandler mockMessageHandler = stopNode(i);

            // 重启之前领导者心跳服务
            restartLeaderHeartBeat(serviceReplicas, i);

            System.out.printf("-- restart %s LeaderHeartBeat -- \r\n", i);

            // 重置mock操作
            reset(mockMessageHandler);

            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 第7个节点网络恢复
        System.out.println("-- Node 7 network recovery --");
        reset(mockMessageHandler6);

        try {
            System.out.println("-- total node has complete change --");
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private MessageHandler stopNode(final int index) {
        // 第一个节点持续异常
        // 重新设置leader的消息处理方式
        MessageHandler mockMessageHandler = spy(serverCommunicationSystems[index].getMessageHandler());

        // mock messageHandler对消息应答的处理
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] objs = invocationOnMock.getArguments();
                if (objs == null || objs.length != 1) {
                    invocationOnMock.callRealMethod();
                } else {
                    Object obj = objs[0];
                    if (obj instanceof LCMessage) {
                        // 走我们设计的逻辑，即不处理
                    } else if (obj instanceof LeaderResponseMessage) {
                        invocationOnMock.callRealMethod();
                    } else if (obj instanceof HeartBeatMessage) {
                        invocationOnMock.callRealMethod();
                    } else {
                        invocationOnMock.callRealMethod();
                    }
                }
                return null;
            }
        }).when(mockMessageHandler).processData(any());

        serverCommunicationSystems[index].setMessageHandler(mockMessageHandler);

        // 领导者心跳停止
        stopLeaderHeartBeat(serviceReplicas);

        System.out.printf("-- stop %s LeaderHeartBeat -- \r\n", index);

        try {
            // 休眠40s，等待领导者切换完成
            Thread.sleep(40000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return mockMessageHandler;
    }


    private void initNode(int nodeSize) {

        // 首先删除view，然后修改配置文件
        try {
            String path = HeartBeatForOtherSizeTest_.class.getResource("/").toURI().getPath();
            String dirPath = new File(path).getParentFile().getParentFile().getPath() + File.separator + "config";
            // 删除view
            new File(dirPath + File.separator + "currentView").delete();
            // 删除system文件
            new File(dirPath + File.separator + "system.config").delete();

            // 根据nodeSize，重新copy一份system.config文件
            File needSystemConfig = new File(dirPath + File.separator + "system_" + nodeSize + ".config");

            // copy一份system.config
            FileUtils.copyFile(needSystemConfig, new File(dirPath + File.separator + "system.config"));
        } catch (Exception e) {
            e.printStackTrace();
        }

        CountDownLatch servers = new CountDownLatch(nodeSize);

        serviceReplicas = new ServiceReplica[nodeSize];

        serverNodes = new TestNodeServer[nodeSize];

//        mockHbTimers = new HeartBeatTimer[nodeSize];

        serverCommunicationSystems = new ServerCommunicationSystemImpl[nodeSize];

        //start nodeSize node servers
        for (int i = 0; i < nodeSize ; i++) {
            serverNodes[i] = new TestNodeServer(i, null, null, null);
            TestNodeServer node = serverNodes[i];
            nodeStartPools.execute(() -> {
                node.startNode(null);
                servers.countDown();
            });
        }

        try {
            servers.await();
            Thread.sleep(1000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (int i = 0; i < nodeSize; i++) {
            serviceReplicas[i] = serverNodes[i].getReplica();
//            mockHbTimers[i] = serviceReplicas[i].getHeartBeatTimer();
            serverCommunicationSystems[i] = (ServerCommunicationSystemImpl) serviceReplicas[i].getServerCommunicationSystem();
        }
    }

    private void stopLeaderHeartBeat(ServiceReplica[] serviceReplicas) {

        int leadId = serviceReplicas[0].getTomLayer().getExecManager().getCurrentLeader();

        serviceReplicas[leadId].getTomLayer().heartBeatTimer.stopAll();
    }

    private void restartLeaderHeartBeat(ServiceReplica[] serviceReplicas, int node) {

        int leadId = serviceReplicas[node].getTomLayer().getExecManager().getCurrentLeader();

        System.out.printf("my new leader = %s \r\n", leadId);

        serviceReplicas[leadId].getTomLayer().heartBeatTimer.restart();
    }
}
