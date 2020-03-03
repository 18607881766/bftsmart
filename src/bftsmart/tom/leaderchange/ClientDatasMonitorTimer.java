package bftsmart.tom.leaderchange;

import bftsmart.clientsmanagement.ClientsManager;
import bftsmart.reconfiguration.ServerViewController;

import java.util.Timer;
import java.util.TimerTask;

public class ClientDatasMonitorTimer {

    private ServerViewController controller;
    private RequestsTimer requestsTimer;
    private ClientsManager clientsManager;

    private long timeout;

    private long clientDatasMaxCount;

    private Timer timer = new Timer("client datas monitor timer");

    private ClientDataMonitorTimerTask clientDataMonitorTimerTask = null;


    public ClientDatasMonitorTimer(ServerViewController controller, RequestsTimer requestsTimer, ClientsManager clientsManager) {
        this.controller = controller;
        this.requestsTimer = requestsTimer;
        this.clientsManager = clientsManager;
        this.timeout = this.controller.getStaticConf().getClientDatasMonitorTimeout();//default 20s
        this.clientDatasMaxCount = this.controller.getStaticConf().getClientDatasMaxCount();//default 200k
        startTimer();
    }

    public void startTimer() {
        if (clientDataMonitorTimerTask == null) {

            clientDataMonitorTimerTask = new ClientDataMonitorTimerTask(clientsManager, controller);
            timer.schedule(clientDataMonitorTimerTask, timeout);

        }
    }

    public void stopTimer() {
        if (clientDataMonitorTimerTask != null) {
            clientDataMonitorTimerTask.cancel();
            clientDataMonitorTimerTask = null;
        }
    }

    class ClientDataMonitorTimerTask extends TimerTask {

       private ClientsManager clientsManager;
       private ServerViewController controller;


        public ClientDataMonitorTimerTask(ClientsManager clientsManager, ServerViewController controller) {
            this.clientsManager = clientsManager;
            this.controller = controller;
        }

        @Override
        /**
         * This is the code for the TimerTask. It clears obsolete datas.
         */
        public void run() {

            if (this.clientsManager.getClientDatasTotal() > clientDatasMaxCount) {
                this.clientsManager.clearObsoleteRequests();
            }
            stopTimer();
            startTimer();
//            System.out.println("I am proc " + this.controller.getStaticConf().getProcessId() + " , there was nothing to do , now is " + System.currentTimeMillis());
        }
    }

}
