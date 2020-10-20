/**
 * Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and
 * the authors indicated in the @author tags
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package bftsmart.tom;

import bftsmart.communication.ServerCommunicationSystem;
import bftsmart.consensus.app.PreComputeBatchExecutable;
import bftsmart.consensus.messages.MessageFactory;
import bftsmart.consensus.roles.Acceptor;
import bftsmart.consensus.roles.Proposer;
import bftsmart.reconfiguration.ReconfigureReply;
import bftsmart.reconfiguration.ServerViewController;
import bftsmart.reconfiguration.VMMessage;
import bftsmart.reconfiguration.util.TOMConfiguration;
import bftsmart.reconfiguration.views.*;
import bftsmart.tom.core.ExecutionManager;
import bftsmart.tom.core.ReplyManager;
import bftsmart.tom.core.TOMLayer;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tom.leaderchange.CertifiedDecision;
import bftsmart.tom.leaderchange.HeartBeatTimer;
import bftsmart.tom.server.*;
import bftsmart.tom.server.defaultservices.DefaultReplier;
import bftsmart.tom.util.ShutdownHookThread;
import bftsmart.tom.util.TOMUtil;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class receives messages from DeliveryThread and manages the execution
 * from the application and reply to the clients. For applications where the
 * ordered messages are executed one by one, ServiceReplica receives the batch
 * decided in a consensus, deliver one by one and reply with the batch of
 * replies. In cases where the application executes the messages in batches, the
 * batch of messages is delivered to the application and ServiceReplica doesn't
 * need to organize the replies in batches.
 */
public class ServiceReplica {

	class MessageContextPair {

		TOMMessage message;
		MessageContext msgCtx;

		MessageContextPair(TOMMessage message, MessageContext msgCtx) {
			this.message = message;
			this.msgCtx = msgCtx;
		}
	}

	// replica ID
	private int id;
	// Server side comunication system
	private ServerCommunicationSystem cs = null;
	private ReplyManager repMan = null;
	private ServerViewController SVController;
	private ReentrantLock waitTTPJoinMsgLock = new ReentrantLock();
	private Condition canProceed = waitTTPJoinMsgLock.newCondition();
	private Executable executor = null;
	private Recoverable recoverer = null;
	private TOMLayer tomLayer = null;
	private boolean tomStackCreated = false;
	private ReplicaContext replicaCtx = null;
	private Replier replier = null;
	private RequestVerifier verifier = null;
//	private HeartBeatTimer heartBeatTimer = null;

	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ServiceReplica.class);

	/**
	 * Constructor
	 *
	 * @param id
	 *            Replica ID
	 * @param executor
	 *            Executor
	 * @param recoverer
	 *            Recoverer
	 */
	public ServiceReplica(int id, Executable executor, Recoverable recoverer) {
		this(id, "", executor, recoverer, null, new DefaultReplier());
	}

	/**
	 * Constructor
	 *
	 * @param id
	 *            Replica ID
	 * @param executor
	 *            Executor
	 * @param recoverer
	 *            Recoverer
	 * @param verifier
	 *            Requests verifier
	 */
	public ServiceReplica(int id, Executable executor, Recoverable recoverer, RequestVerifier verifier) {
		this(id, "", executor, recoverer, verifier, new DefaultReplier());
	}

	/**
	 * Constructor
	 * 
	 * @param id
	 *            Replica ID
	 * @param executor
	 *            Executor
	 * @param recoverer
	 *            Recoverer
	 * @param verifier
	 *            Requests verifier
	 * @param replier
	 *            Replier
	 */
	public ServiceReplica(int id, Executable executor, Recoverable recoverer, RequestVerifier verifier,
                          Replier replier) {
		this(id, "", executor, recoverer, verifier, replier);
	}

	/**
	 * Constructor
	 *
	 * @param id
	 *            Process ID
	 * @param configHome
	 *            Configuration directory for JBP
	 * @param executor
	 *            Executor
	 * @param recoverer
	 *            Recoverer
	 * @param verifier
	 *            Requests verifier
	 * @param replier
	 *            Replier
	 */
	public ServiceReplica(int id, String configHome, Executable executor, Recoverable recoverer,
                          RequestVerifier verifier, Replier replier) {
		this(new ServerViewController(id, configHome), executor, recoverer, verifier, replier);
		// this.id = id;
		// this.SVController = new ServerViewController(id, configHome);
		// this.executor = executor;
		// this.recoverer = recoverer;
		// this.replier = (replier != null ? replier : new DefaultReplier());
		// this.verifier = verifier;
		// this.init();
		// this.recoverer.setReplicaContext(replicaCtx);
		// this.replier.setReplicaContext(replicaCtx);
	}

	public ServiceReplica(int id, String systemConfig, String hostsConfig, String keystoreHome, String runtimeDir,
                          View initView, Executable executor, Recoverable recoverer) {
		this(new ServerViewController(new TOMConfiguration(id, systemConfig, hostsConfig, keystoreHome),
				new FileSystemViewStorage(initView, new File(runtimeDir, "view"))), executor, recoverer, null,
				new DefaultReplier());
	}

	public ServiceReplica(TOMConfiguration config, Executable executor, Recoverable recoverer ) {
		this(new ServerViewController(config, new MemoryBasedViewStorage()),
				executor, recoverer, null, new DefaultReplier());
	}

	public ServiceReplica(TOMConfiguration config, String runtimeDir, Executable executor,
                          Recoverable recoverer ) {
		this(new ServerViewController(config, new FileSystemViewStorage(null, new File(runtimeDir, "view"))),
				executor, recoverer, null, new DefaultReplier());
	}
	public ServiceReplica(TOMConfiguration config, View initView, String runtimeDir, Executable executor,
                          Recoverable recoverer, RequestVerifier verifier, Replier replier) {
		this(new ServerViewController(config, new FileSystemViewStorage(initView, new File(runtimeDir, "view"))),
				executor, recoverer, verifier, replier);
	}

	public ServiceReplica(TOMConfiguration config, ViewStorage viewStorage, Executable executor, Recoverable recoverer,
                          RequestVerifier verifier, Replier replier) {
		this(new ServerViewController(config, viewStorage), executor, recoverer, verifier, replier);
	}

	public ServiceReplica(TOMConfiguration config, Executable executor, Recoverable recoverer, int lastCid) {
		this(new ServerViewController(config, new MemoryBasedViewStorage()),
				executor, recoverer, null, new DefaultReplier(), lastCid);
	}

	public ServiceReplica(TOMConfiguration config, Executable executor, Recoverable recoverer, int lastCid, View lastView) {
		this(new ServerViewController(config, new MemoryBasedViewStorage(lastView)),
				executor, recoverer, null, new DefaultReplier(), lastCid);
	}

	/**
	 * Constructor
	 *
	 * @param id
	 *            Process ID
	 * @param configHome
	 *            Configuration directory for JBP
	 * @param executor
	 *            Executor
	 * @param recoverer
	 *            Recoverer
	 * @param verifier
	 *            Requests verifier
	 * @param replier
	 *            Replier
	 *
	 * @param lastCid
	 */
	protected ServiceReplica(ServerViewController viewController, Executable executor, Recoverable recoverer,
							 RequestVerifier verifier, Replier replier, int lastCid) {
		this.id = viewController.getStaticConf().getProcessId();
		this.SVController = viewController;
		this.executor = executor;
		this.recoverer = recoverer;
		this.replier = (replier != null ? replier : new DefaultReplier());
		this.verifier = verifier;
		this.init();
		this.tomLayer.getStateManager().setLastCID(lastCid);
		this.tomLayer.setLastExec(lastCid);
		this.recoverer.setReplicaContext(replicaCtx);
		this.replier.setReplicaContext(replicaCtx);
	}


//	public ServiceReplica(int id, String configHome, Executable executor, Recoverable recoverer,
//						  RequestVerifier verifier, Replier replier, HeartBeatTimer heartBeatTimer) {
//
//		this(new ServerViewController(id, configHome), executor, recoverer, verifier, replier, heartBeatTimer);
//	}

//    /**
//     * Constructor
//     *
//     * @param id
//     *            Process ID
//     * @param configHome
//     *            Configuration directory for JBP
//     * @param executor
//     *            Executor
//     * @param recoverer
//     *            Recoverer
//     * @param verifier
//     *            Requests verifier
//     * @param replier
//     *            Replier
//     * @param heartBeatTimer
//     *            HeartBeatTimer
//     */
//    protected ServiceReplica(ServerViewController viewController, Executable executor, Recoverable recoverer,
//                             RequestVerifier verifier, Replier replier, HeartBeatTimer heartBeatTimer) {
//        this.id = viewController.getStaticConf().getProcessId();
//        this.SVController = viewController;
//        this.executor = executor;
//        this.recoverer = recoverer;
//        this.replier = (replier != null ? replier : new DefaultReplier());
//        this.verifier = verifier;
//        this.heartBeatTimer = heartBeatTimer;
//        this.init();
//        this.recoverer.setReplicaContext(replicaCtx);
//        this.replier.setReplicaContext(replicaCtx);
//    }

    /**
	 * Constructor
	 *
	 * @param id
	 *            Process ID
	 * @param configHome
	 *            Configuration directory for JBP
	 * @param executor
	 *            Executor
	 * @param recoverer
	 *            Recoverer
	 * @param verifier
	 *            Requests verifier
	 * @param replier
	 *            Replier
	 */
	protected ServiceReplica(ServerViewController viewController, Executable executor, Recoverable recoverer,
                             RequestVerifier verifier, Replier replier) {
		this.id = viewController.getStaticConf().getProcessId();
		this.SVController = viewController;
		this.executor = executor;
		this.recoverer = recoverer;
		this.replier = (replier != null ? replier : new DefaultReplier());
		this.verifier = verifier;
		this.init();
		this.recoverer.setReplicaContext(replicaCtx);
		this.replier.setReplicaContext(replicaCtx);
	}

	public void setReplyController(Replier replier) {
		this.replier = replier;
	}

	public Executable getExecutor() {
		return executor;
	}

	public Replier getReplier() {
		return replier;
	}

	public ReplyManager getRepMan() {
		return repMan;
	}

//	public HeartBeatTimer getHeartBeatTimer() {
//	    return heartBeatTimer;
//    }

	// this method initializes the object
	private void init() {
		try {
			cs = new ServerCommunicationSystem(this.SVController, this);
		} catch (Exception ex) {
//			Logger.getLogger(ServiceReplica.class.getName()).log(Level.SEVERE, null, ex);
			LOGGER.error("Unable to build a communication system.");
			throw new RuntimeException("Unable to build a communication system.", ex);
		}

		if (this.SVController.isInCurrentView()) {
			LOGGER.info("-- In current view: {}", this.SVController.getCurrentView());
			initTOMLayer(); // initiaze the TOM layer
		} else {
			LOGGER.error("-- Not in current view: {}", this.SVController.getCurrentView());

			// Not in the initial view, just waiting for the view where the join has been
			// executed
			LOGGER.error("-- Waiting for the TTP: {}", this.SVController.getCurrentView());
			waitTTPJoinMsgLock.lock();
			try {
				canProceed.awaitUninterruptibly();
			} finally {
				waitTTPJoinMsgLock.unlock();
			}

		}
		initReplica();
	}

	public void joinMsgReceived(VMMessage msg) {
		ReconfigureReply r = msg.getReply();

		if (r.getView().isMember(id)) {
			this.SVController.processJoinResult(r);

			initTOMLayer(); // initiaze the TOM layer
			cs.updateServersConnections();
			this.cs.joinViewReceived();
			waitTTPJoinMsgLock.lock();
			canProceed.signalAll();
			waitTTPJoinMsgLock.unlock();
		}
	}

	private void initReplica() {
		cs.start();
		repMan = new ReplyManager(SVController.getStaticConf().getNumRepliers(), cs);
	}

	/**
	 * This message delivers a readonly message, i.e., a message that was not
	 * ordered to the replica and gather the reply to forward to the client
	 *
	 * @param message
	 *            the request received from the delivery thread
	 */
	public final void receiveReadonlyMessage(TOMMessage message, MessageContext msgCtx) {
		byte[] response = null;

		// This is used to deliver the requests to the application and obtain a reply to
		// deliver
		// to the clients. The raw decision does not need to be delivered to the
		// recoverable since
		// it is not associated with any consensus instance, and therefore there is no
		// need for
		// applications to log it or keep any proof.
		if (executor instanceof FIFOExecutable) {
			response = ((FIFOExecutable) executor).executeUnorderedFIFO(message.getContent(), msgCtx,
					message.getSender(), message.getOperationId());
		} else {
			if (message.getViewID() == SVController.getCurrentViewId()) {
				response = executor.executeUnordered(message.getContent(), msgCtx);
			} else if (message.getViewID() < SVController.getCurrentViewId()) {
				View view = SVController.getCurrentView();
				List<NodeNetwork> addressesTemp = new ArrayList<>();
				for(int i = 0; i < view.getProcesses().length;i++) {
					int cpuId = view.getProcesses()[i];
					NodeNetwork inetSocketAddress = view.getAddress(cpuId);
					if (inetSocketAddress.getHost().equals("0.0.0.0")) {
						// proc docker env
						String host = SVController.getStaticConf().getOuterHostConfig().getHost(cpuId);
						NodeNetwork tempSocketAddress = new NodeNetwork(host, inetSocketAddress.getConsensusPort(), inetSocketAddress.getMonitorPort());
						LOGGER.info("I am proc {}, tempSocketAddress.getAddress().getHostAddress() = {}", SVController.getStaticConf().getProcessId(), host);
						addressesTemp.add(tempSocketAddress);
					} else {
						LOGGER.info("I am proc {}, tempSocketAddress.getAddress().getHostAddress() = {}", SVController.getStaticConf().getProcessId(), inetSocketAddress.toUrl());
						addressesTemp.add(inetSocketAddress);
					}
				}

				View replyView = new View(view.getId(), view.getProcesses(), view.getF(),addressesTemp.toArray(new NodeNetwork[addressesTemp.size()]));
				response = TOMUtil.getBytes(replyView);
			}
		}

		if (message.getReqType() == TOMMessageType.UNORDERED_HASHED_REQUEST && message.getReplyServer() != this.id) {
			try {
				response = TOMUtil.computeHash(response);
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
			}
		}

		// Generate the messages to send back to the clients
		message.reply = new TOMMessage(id, message.getSession(), message.getSequence(), message.getOperationId(),
				response, SVController.getCurrentViewId(), message.getReqType());

		if (SVController.getStaticConf().getNumRepliers() > 0) {
			repMan.send(message);
		} else {
			cs.send(new int[] { message.getSender() }, message.reply);
		}
	}

	public void kill() {

		Thread t = new Thread() {

			@Override
			public void run() {
				if (tomLayer != null) {
					tomLayer.shutdown();
				}
			}
		};
		t.start();
	}

	public void restart() {
		Thread t = new Thread() {

			@Override
			public void run() {
				if (tomLayer != null && cs != null) {
					tomLayer.shutdown();

					try {
						cs.join();
						cs.getServersConn().join();
						tomLayer.join();
						tomLayer.getDeliveryThread().join();

					} catch (InterruptedException ex) {
//						Logger.getLogger(ServiceReplica.class.getName()).log(Level.SEVERE, null, ex);
						LOGGER.error("restart exception!");
					}

					tomStackCreated = false;
					tomLayer = null;
					cs = null;

					init();
					recoverer.setReplicaContext(replicaCtx);
					replier.setReplicaContext(replicaCtx);

				}
			}
		};
		t.start();
	}

	public void receiveMessages(int consId[], int regencies[], int leaders[], CertifiedDecision[] cDecs,
			TOMMessage[][] requests, List<byte[]> asyncResponseLinkedList) {
		int numRequests = 0;
		int consensusCount = 0;
		List<TOMMessage> toBatch = new ArrayList<>();
		List<MessageContext> msgCtxts = new ArrayList<>();
		boolean noop = true;

		for (TOMMessage[] requestsFromConsensus : requests) {

			TOMMessage firstRequest = requestsFromConsensus[0];
			int requestCount = 0;
			noop = true;
			for (TOMMessage request : requestsFromConsensus) {

				LOGGER.debug("(ServiceReplica.receiveMessages) Processing TOMMessage from client {} with sequence number {} for session {} decided in consensus {}"
						, request.getSender(), request.getSequence(), request.getSession(), consId[consensusCount]);

				LOGGER.info("(ServiceReplica.receiveMessages) request view id = {}, curr view id = {}, request type = {}", request.getViewID(), SVController.getCurrentViewId(), request.getReqType());

				// 暂时没有节点间的视图ID同步过程，在处理RECONFIG这类更新视图的操作时先不考虑视图ID落后的情况
				if (request.getViewID() == SVController.getCurrentViewId() || request.getReqType() == TOMMessageType.RECONFIG) {

					if (request.getReqType() == TOMMessageType.ORDERED_REQUEST) {

						noop = false;

						numRequests++;
						MessageContext msgCtx = new MessageContext(request.getSender(), request.getViewID(),
								request.getReqType(), request.getSession(), request.getSequence(),
								request.getOperationId(), request.getReplyServer(), request.serializedMessageSignature,
								firstRequest.timestamp, request.numOfNonces, request.seed, regencies[consensusCount],
								leaders[consensusCount], consId[consensusCount],
								cDecs[consensusCount].getConsMessages(), firstRequest, false);

						if (requestCount + 1 == requestsFromConsensus.length) {

							msgCtx.setLastInBatch();
						}
						request.deliveryTime = System.nanoTime();
						if (executor instanceof PreComputeBatchExecutable) {

							LOGGER.debug("(ServiceReplica.receiveMessages) Batching request from {}", request.getSender());

							// This is used to deliver the content decided by a consensus instance directly
							// to
							// a Recoverable object. It is useful to allow the application to create a log
							// and
							// store the proof associated with decisions (which are needed by replicas
							// that are asking for a state transfer).
							if (this.recoverer != null)
								this.recoverer.Op(msgCtx.getConsensusId(), request.getContent(), msgCtx);

							// deliver requests and contexts to the executor later
							msgCtxts.add(msgCtx);
							toBatch.add(request);
						} else if (executor instanceof FIFOExecutable) {

							LOGGER.debug("(ServiceReplica.receiveMessages) Delivering request from {} via FifoExecutable", request.getSender());

							// This is used to deliver the content decided by a consensus instance directly
							// to
							// a Recoverable object. It is useful to allow the application to create a log
							// and
							// store the proof associated with decisions (which are needed by replicas
							// that are asking for a state transfer).
							if (this.recoverer != null)
								this.recoverer.Op(msgCtx.getConsensusId(), request.getContent(), msgCtx);

							// This is used to deliver the requests to the application and obtain a reply to
							// deliver
							// to the clients. The raw decision is passed to the application in the line
							// above.
							byte[] response = ((FIFOExecutable) executor).executeOrderedFIFO(request.getContent(),
									msgCtx, request.getSender(), request.getOperationId());

							// Generate the messages to send back to the clients
							request.reply = new TOMMessage(id, request.getSession(), request.getSequence(),
									request.getOperationId(), response, SVController.getCurrentViewId(),
									request.getReqType());
							LOGGER.debug("(ServiceReplica.receiveMessages) sending reply to {}", request.getSender());
							replier.manageReply(request, msgCtx);
						} else if (executor instanceof SingleExecutable) {

							LOGGER.debug("(ServiceReplica.receiveMessages) Delivering request from {} via SingleExecutable", request.getSender());

							// This is used to deliver the content decided by a consensus instance directly
							// to
							// a Recoverable object. It is useful to allow the application to create a log
							// and
							// store the proof associated with decisions (which are needed by replicas
							// that are asking for a state transfer).
							if (this.recoverer != null)
								this.recoverer.Op(msgCtx.getConsensusId(), request.getContent(), msgCtx);

							// This is used to deliver the requests to the application and obtain a reply to
							// deliver
							// to the clients. The raw decision is passed to the application in the line
							// above.
							byte[] response = ((SingleExecutable) executor).executeOrdered(request.getContent(),
									msgCtx);

							// Generate the messages to send back to the clients
							request.reply = new TOMMessage(id, request.getSession(), request.getSequence(),
									request.getOperationId(), response, SVController.getCurrentViewId(),
									request.getReqType());
							LOGGER.debug("(ServiceReplica.receiveMessages) sending reply to {}", request.getSender());
							replier.manageReply(request, msgCtx);
						} else {
							throw new UnsupportedOperationException("Non-existent interface");
						}
					} else if (request.getReqType() == TOMMessageType.RECONFIG) {
						SVController.enqueueUpdate(request);
					} else {
						throw new RuntimeException("Should never reach here!");
					}
				} else if (request.getViewID() < SVController.getCurrentViewId()) { // message sender had an old view,
																					// resend the message to
																					// him (but only if it came from
																					// consensus an not state transfer)
					View view = SVController.getCurrentView();

					List<NodeNetwork> addressesTemp = new ArrayList<>();

					for(int i = 0; i < view.getProcesses().length;i++) {
						int cpuId = view.getProcesses()[i];
						NodeNetwork inetSocketAddress = view.getAddress(cpuId);

						if (inetSocketAddress.getHost().equals("0.0.0.0")) {
							// proc docker env
							String host = SVController.getStaticConf().getOuterHostConfig().getHost(cpuId);

							NodeNetwork tempSocketAddress = new NodeNetwork(host, inetSocketAddress.getConsensusPort(), inetSocketAddress.getMonitorPort());
							LOGGER.info("I am proc {}, tempSocketAddress.getAddress().getHostAddress() = {}", SVController.getStaticConf().getProcessId(), host);
							addressesTemp.add(tempSocketAddress);
						} else {
							LOGGER.info("I am proc {}, tempSocketAddress.getAddress().getHostAddress() = {}", SVController.getStaticConf().getProcessId(), inetSocketAddress.toUrl());
							addressesTemp.add(inetSocketAddress);
						}
					}

					View replyView = new View(view.getId(), view.getProcesses(), view.getF(),addressesTemp.toArray(new NodeNetwork[addressesTemp.size()]));
					LOGGER.info("I am proc {}, view = {}, hashCode = {}, reply View = {}", this.SVController.getStaticConf().getProcessId(), view, view.hashCode(), replyView);

					tomLayer.getCommunication().send(new int[] { request.getSender() },
							new TOMMessage(SVController.getStaticConf().getProcessId(), request.getSession(),
									request.getSequence(), request.getOperationId(),
									TOMUtil.getBytes(replyView), SVController.getCurrentViewId(),
									request.getReqType()));
				}
				requestCount++;
			}

			// This happens when a consensus finishes but there are no requests to deliver
			// to the application. This can happen if a reconfiguration is issued and is the
			// only
			// operation contained in the batch. The recoverer must be notified about this,
			// hence the invocation of "noop"
			if (noop && this.recoverer != null) {

				LOGGER.debug("(ServiceReplica.receiveMessages) I am proc {}, host = {}, port = {}. Delivering a no-op to the recoverer", this.SVController.getStaticConf().getProcessId()
				, this.SVController.getStaticConf().getRemoteAddress(this.SVController.getStaticConf().getProcessId()).getHost(), this.SVController.getStaticConf().getRemoteAddress(this.SVController.getStaticConf().getProcessId()).getConsensusPort());

				LOGGER.debug("I am proc {} , host = {}, port = {}.--- A consensus instance finished, but there were no commands to deliver to the application.", this.SVController.getStaticConf().getProcessId()
						, this.SVController.getStaticConf().getRemoteAddress(this.SVController.getStaticConf().getProcessId()).getHost(), this.SVController.getStaticConf().getRemoteAddress(this.SVController.getStaticConf().getProcessId()).getConsensusPort());
				LOGGER.debug("I am proc {} , host = {}, port = {}.--- Notifying recoverable about a blank consensus.", this.SVController.getStaticConf().getProcessId()
						, this.SVController.getStaticConf().getRemoteAddress(this.SVController.getStaticConf().getProcessId()).getHost(), this.SVController.getStaticConf().getRemoteAddress(this.SVController.getStaticConf().getProcessId()).getConsensusPort());

				byte[][] batch = null;
				MessageContext[] msgCtx = null;
				if (requestsFromConsensus.length > 0) {
					// Make new batch to deliver
					batch = new byte[requestsFromConsensus.length][];
					msgCtx = new MessageContext[requestsFromConsensus.length];

					// Put messages in the batch
					int line = 0;
					for (TOMMessage m : requestsFromConsensus) {
						batch[line] = m.getContent();

						msgCtx[line] = new MessageContext(m.getSender(), m.getViewID(), m.getReqType(), m.getSession(),
								m.getSequence(), m.getOperationId(), m.getReplyServer(), m.serializedMessageSignature,
								firstRequest.timestamp, m.numOfNonces, m.seed, regencies[consensusCount],
								leaders[consensusCount], consId[consensusCount],
								cDecs[consensusCount].getConsMessages(), firstRequest, true);
						msgCtx[line].setLastInBatch();

						line++;
					}
				}

				this.recoverer.noOp(consId[consensusCount], batch, msgCtx);

				// MessageContext msgCtx = new MessageContext(-1, -1, null, -1, -1, -1, -1,
				// null, // Since it is a noop, there is no need to pass info about the
				// client...
				// -1, 0, 0, regencies[consensusCount], leaders[consensusCount],
				// consId[consensusCount], cDecs[consensusCount].getConsMessages(), //... but
				// there is still need to pass info about the consensus
				// null, true); // there is no command that is the first of the batch, since it
				// is a noop
				// msgCtx.setLastInBatch();

				// this.recoverer.noOp(msgCtx.getConsensusId(), msgCtx);
			}

			consensusCount++;
		}

		if (executor instanceof PreComputeBatchExecutable && numRequests > 0) {
			// Make new batch to deliver
			byte[][] batch = new byte[numRequests][];

			ReplyContext replyContext = new ReplyContext()
					.buildId(id)
					.buildCurrentViewId(SVController.getCurrentViewId())
					.buildNumRepliers(SVController.getStaticConf().getNumRepliers())
					.buildRepMan(repMan)
					.buildReplier(replier);

			List<ReplyContextMessage> replyContextMessages = new ArrayList<>();

			// Put messages in the batch
			int line = 0;
			for (TOMMessage m : toBatch) {
                replyContextMessages.add(new ReplyContextMessage(replyContext, m));
				batch[line] = m.getContent();
				line++;
			}

			MessageContext[] msgContexts = new MessageContext[msgCtxts.size()];
			msgContexts = msgCtxts.toArray(msgContexts);

			//Deliver the batch and wait for replies
			byte[][] replies = ((PreComputeBatchExecutable) executor).executeBatch(batch, msgContexts, replyContextMessages);

			//Send the replies back to the client
			for (int index = 0; index < toBatch.size(); index++) {
				TOMMessage request = toBatch.get(index);
				request.reply = new TOMMessage(id, request.getSession(), request.getSequence(),
						request.getOperationId(), asyncResponseLinkedList.get(index), SVController.getCurrentViewId(),
						request.getReqType());

				if (SVController.getStaticConf().getNumRepliers() > 0) {
					LOGGER.debug("(ServiceReplica.receiveMessages) sending reply to {} with sequence number {} and operation ID {} via ReplyManager"
							, request.getSender(), request.getSequence(), request.getOperationId());
					repMan.send(request);
				} else {
					LOGGER.debug("(ServiceReplica.receiveMessages) sending reply to {} with sequence number {} and operation ID {}"
							, request.getSender(), request.getSequence(), request.getOperationId());
					replier.manageReply(request, msgContexts[index]);
					// cs.send(new int[]{request.getSender()}, request.reply);
				}
			}

			// DEBUG
			LOGGER.debug("BATCHEXECUTOR END");
		}
	}

	/**
	 * This method initializes the object
	 *
	 * @param cs
	 *            Server side communication System
	 * @param conf
	 *            Total order messaging configuration
	 */
	private void initTOMLayer() {
		if (tomStackCreated) { // if this object was already initialized, don't do it again
			return;
		}

		if (!SVController.isInCurrentView()) {
			throw new RuntimeException("I'm not an acceptor!");
		}

		// Assemble the total order messaging layer
		MessageFactory messageFactory = new MessageFactory(id);

		Acceptor acceptor = new Acceptor(cs, messageFactory, SVController);
		cs.setAcceptor(acceptor);

		Proposer proposer = new Proposer(cs, messageFactory, SVController);

		ExecutionManager executionManager = new ExecutionManager(SVController, acceptor, proposer, id);

		acceptor.setExecutionManager(executionManager);

		tomLayer = new TOMLayer(executionManager, this, recoverer, acceptor, cs, SVController, verifier);

		executionManager.setTOMLayer(tomLayer);

		SVController.setTomLayer(tomLayer);

		cs.setTOMLayer(tomLayer);
		cs.setRequestReceiver(tomLayer);

		acceptor.setTOMLayer(tomLayer);

		if (SVController.getStaticConf().isShutdownHookEnabled()) {
			Runtime.getRuntime().addShutdownHook(new ShutdownHookThread(tomLayer));
		}
		tomLayer.start(); // start the layer execution
		tomStackCreated = true;

		replicaCtx = new ReplicaContext(cs, SVController);
	}

	/**
	 * Obtains the current replica context (getting access to several information
	 * and capabilities of the replication engine).
	 *
	 * @return this replica context
	 */
	public final ReplicaContext getReplicaContext() {
		return replicaCtx;
	}

	public ServerCommunicationSystem getServerCommunicationSystem() {

		return cs;
	}

	public void setCommunicationSystem(ServerCommunicationSystem cs) {
		this.cs = cs;
	}

	public int getId() {
		return id;
	}

	public TOMLayer getTomLayer() {
		return tomLayer;
	}
}
