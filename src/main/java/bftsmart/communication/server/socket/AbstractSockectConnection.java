/**
 Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package bftsmart.communication.server.socket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.communication.SystemMessage;
import bftsmart.communication.queue.MessageQueue;
import bftsmart.communication.server.AsyncFuture;
import bftsmart.communication.server.AsyncFutureTask;
import bftsmart.communication.server.CompletedCallback;
import bftsmart.communication.server.MessageConnection;
import bftsmart.reconfiguration.ViewTopology;
import bftsmart.tom.util.TOMUtil;
import utils.io.RuntimeIOException;

/**
 * This class represents a connection with other server.
 *
 * ServerConnections are created by ServerCommunicationLayer.
 *
 * @author alysson
 */
public abstract class AbstractSockectConnection implements MessageConnection {

	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractSockectConnection.class);

	// 重连周期
	private static final long RECONNECT_MILL_SECONDS = 5000L;
	private static final long POOL_INTERVAL = 5000;
	private final long RETRY_INTERVAL;
	private final int RETRY_COUNT;
	// private static final int SEND_QUEUE_SIZE = 50;

	protected final String REALM_NAME;
	protected final int me;
	protected final int remoteId;

	protected ViewTopology viewTopology;
	private MessageQueue messageInQueue;
	private LinkedBlockingQueue<MessageSendingTask> outQueue;// = new
																// LinkedBlockingQueue<byte[]>(SEND_QUEUE_SIZE);
	private SecretKey authKey = null;
	private Mac macSend;
	private Mac macReceive;
	private int macSize;
	private Object connectLock = new Object();
	/** Only used when there is no sender Thread */
	private volatile boolean doWork = true;
	private CountDownLatch latch = new CountDownLatch(1);

	private Thread senderTread;

	private Thread receiverThread;

	public AbstractSockectConnection(String realmName, ViewTopology viewTopology, int remoteId,
			MessageQueue messageInQueue) {
		this.REALM_NAME = realmName;
		this.me = viewTopology.getCurrentProcessId();
		this.remoteId = remoteId;

		this.viewTopology = viewTopology;
		this.messageInQueue = messageInQueue;

		this.outQueue = new LinkedBlockingQueue<MessageSendingTask>(viewTopology.getStaticConf().getOutQueueSize());

		this.RETRY_INTERVAL = viewTopology.getStaticConf().getSendRetryInterval();
		this.RETRY_COUNT = viewTopology.getStaticConf().getSendRetryCount();

		LOGGER.info("I am proc {}", viewTopology.getStaticConf().getProcessId());
	}

	@Override
	public synchronized void start() {
		senderTread = new SenderThread(latch);
		receiverThread = new ReceiverThread();

		senderTread.start();
		receiverThread.start();
	}

	@Override
	public int getRemoteId() {
		return remoteId;
	}

	@Override
	public SecretKey getSecretKey() {
		return authKey;
	}

	/**
	 * Stop message sending and reception.
	 */
	@Override
	public synchronized void shutdown() {
		if (!doWork) {
			return;
		}
		LOGGER.info("SHUTDOWN for {}", remoteId);

		doWork = false;

		senderTread.interrupt();
		receiverThread.interrupt();

		senderTread = null;
		receiverThread = null;

		closeSocket();
	}

	@Override
	public void clearOutQueue() {
		outQueue.clear();
	}

	/**
	 * Used to send packets to the remote server.
	 */
	/**
	 * @param data         要发送的数据；
	 * @param useMAC       是否使用 MAC；
	 * @param retrySending 当发送失败时，是否要重试；
	 * @param callback     发送完成回调；
	 * @return
	 * @throws InterruptedException
	 */
	@Override
	public AsyncFuture<SystemMessage, Void> send(SystemMessage message, boolean useMAC, boolean retrySending,
			CompletedCallback<SystemMessage, Void> callback) {
		MessageSendingTask task = new MessageSendingTask(message, useMAC, retrySending);
		task.setCallback(callback);

		if (!outQueue.offer(task)) {
			LOGGER.error("(ServerConnection.send) out queue for {} full (message discarded).", remoteId);

			task.error(new IllegalStateException(
					"(ServerConnection.send) out queue for {" + remoteId + "} full (message discarded)."));
		}

		return task;
	}

	private byte[] serializeMessage(SystemMessage message) {
		// 首先判断消息类型
		ByteArrayOutputStream bOut = new ByteArrayOutputStream(248);
		try {
			new ObjectOutputStream(bOut).writeObject(message);
		} catch (IOException ex) {
			throw new RuntimeIOException(ex.getMessage(), ex);
		}

		return bOut.toByteArray();
	}

	/**
	 * try to send a message through the socket if some problem is detected, a
	 * reconnection is done
	 */
	private final void processSendingTask(MessageSendingTask messageTask) {
		int counter = 0;
		SystemMessage message = messageTask.getSource();
		boolean taskUseMAC = messageTask.useMac;

		byte[] messageData = serializeMessage(message);
		if (!taskUseMAC) {
			int hashCodeOfMessage = System.identityHashCode(messageData);
			LOGGER.debug("(ServerConnection.send) Not sending defaultMAC {}", hashCodeOfMessage);
		}

		do {
			try {
				Throwable error = null;
				// if there is a need to reconnect, abort this method
				DataOutputStream socketOutStream = getSocketOutputStream();
				if (socketOutStream != null) {
					try {
						// do an extra copy of the data to be sent, but on a single out stream write
						byte[] mac = (taskUseMAC && viewTopology.getStaticConf().isUseMACs())
								? macSend.doFinal(messageData)
								: null;
						byte[] data = new byte[5 + messageData.length + ((mac != null) ? mac.length : 0)];
						int value = messageData.length;

						System.arraycopy(new byte[] { (byte) (value >>> 24), (byte) (value >>> 16),
								(byte) (value >>> 8), (byte) value }, 0, data, 0, 4);
						System.arraycopy(messageData, 0, data, 4, messageData.length);
						if (mac != null) {
							// System.arraycopy(mac,0,data,4+messageData.length,mac.length);
							System.arraycopy(new byte[] { (byte) 1 }, 0, data, 4 + messageData.length, 1);
							System.arraycopy(mac, 0, data, 5 + messageData.length, mac.length);
						} else {
							System.arraycopy(new byte[] { (byte) 0 }, 0, data, 4 + messageData.length, 1);
						}

						socketOutStream.write(data);

						messageTask.complete(null);
						return;
					} catch (IOException ex) {
						error = ex;
					}
				}

				// TODO: 如果连接未完成时，应该等待连接；
				if (socketOutStream == null) {
					// 连接未完成；
					error = new IllegalStateException("The sockect connection is not ready!");
				}

				// 如果不重试发送失败的消息，则立即报告错误；
				if (!messageTask.isRetry()) {
					counter = RETRY_COUNT;
					messageTask.error(error);
				}

				LOGGER.error(
						"[ServerConnection.sendBytes] current proc id: {}. Close socket and waitAndConnect connect with {}",
						viewTopology.getStaticConf().getProcessId(), remoteId);
				closeSocket();

				LOGGER.error("[ServerConnection.sendBytes] current proc id: {}, Wait and reonnect with {}",
						viewTopology.getStaticConf().getProcessId(), remoteId);
				waitAndConnect();

				if (messageTask.isRetry() && counter++ >= RETRY_COUNT) {
					LOGGER.error("[ServerConnection.sendBytes] fails, and the fail times is out of the max retry count["
							+ RETRY_COUNT + "]!", viewTopology.getStaticConf().getProcessId(), remoteId);

					messageTask.error(error);
					return;
				}
			} catch (Exception e) {
				messageTask.error(e);
				return;
			}
		} while (doWork && counter < RETRY_COUNT);

		messageTask.error(new IllegalStateException("Completed in unexpected state!"));
	}

	/**
	 * 尝试激活连接；
	 * <p>
	 */
	protected abstract void ensureConnection();

	/**
	 * 关闭连接；此方法不抛出任何异常；
	 */
	protected abstract void closeSocket();

	/**
	 * 返回网络输出流；
	 * 
	 * @return
	 */
	protected abstract DataOutputStream getSocketOutputStream();

	/**
	 * 返回网络输入流；
	 * 
	 * @return
	 */
	protected abstract DataInputStream getSocketInputStream();

	/**
	 * (Re-)establish connection between peers.
	 *
	 * @param newSocket socket created when this server accepted the connection
	 *                  (only used if processId is less than remoteId)
	 */
	private void reconnect() {
		synchronized (connectLock) {
			ensureConnection();

			DataOutputStream socketOutStream = getSocketOutputStream();
			DataInputStream socketInStream = getSocketInputStream();
			if (socketOutStream != null && socketInStream != null) {
				authKey = null;
				boolean success = authenticate(socketOutStream, socketInStream);
				if (!success) {
					shutdown();
				}
			}
		}
	}

	/**
	 * 认证连接；
	 * 
	 * @param socketOutStream
	 * @param socketInStream
	 * @return
	 */
	private boolean authenticate(DataOutputStream socketOutStream, DataInputStream socketInStream) {
		if (socketOutStream == null || socketInStream == null) {
			return false;
		}

		try {
			// Derive DH private key from replica's own RSA private key
			PrivateKey RSAprivKey = viewTopology.getStaticConf().getRSAPrivateKey();
			BigInteger DHPrivKey = new BigInteger(RSAprivKey.getEncoded());

			// 发送 DH key；
			sendDHKey(socketOutStream, RSAprivKey, DHPrivKey);

			// 接收 DH key;
			BigInteger remoteDHPubKey = receiveDHKey(socketInStream);

			// 初始化 MAC;
			initMAC(DHPrivKey, remoteDHPubKey);

			return true;

		} catch (Exception ex) {
			LOGGER.error("Error occurred while doing authenticateAndEstablishAuthKey with remote replica[" + remoteId
					+ "] ! --" + ex.getMessage(), ex);
			return false;
		}
	}

	private void initMAC(BigInteger DHPrivKey, BigInteger remoteDHPubKey)
			throws NoSuchAlgorithmException, InvalidKeySpecException, InvalidKeyException {
		// Create secret key
		BigInteger secretKey = remoteDHPubKey.modPow(DHPrivKey, viewTopology.getStaticConf().getDHP());

		LOGGER.info("[{}] ->  I am proc {}, -- Diffie-Hellman complete with proc id {}, with port {}", REALM_NAME,
				viewTopology.getStaticConf().getProcessId(), remoteId,
				viewTopology.getStaticConf().getServerToServerPort(remoteId));

		SecretKeyFactory fac = SecretKeyFactory.getInstance(SECRET_KEY_ALGORITHM);
		PBEKeySpec spec = new PBEKeySpec(secretKey.toString().toCharArray());

		// PBEKeySpec spec = new PBEKeySpec(PASSWORD.toCharArray());
		authKey = fac.generateSecret(spec);

		macSend = Mac.getInstance(MAC_ALGORITHM);
		macSend.init(authKey);
		macReceive = Mac.getInstance(MAC_ALGORITHM);
		macReceive.init(authKey);
		macSize = macSend.getMacLength();
	}

	/**
	 * @param socketInStream
	 * @return
	 * @throws IOException
	 */
	private BigInteger receiveDHKey(DataInputStream socketInStream) throws IOException {
		// receive remote DH public key and signature
		int remoteDHPubKeyLength = socketInStream.readInt();
		byte[] remoteDHPubKeyBytes = new byte[remoteDHPubKeyLength];
		int read = 0;
		do {
			read += socketInStream.read(remoteDHPubKeyBytes, read, remoteDHPubKeyLength - read);

		} while (read < remoteDHPubKeyLength);

		int remoteSignatureLength = socketInStream.readInt();
		byte[] remoteSignatureBytes = new byte[remoteSignatureLength];
		read = 0;
		do {
			read += socketInStream.read(remoteSignatureBytes, read, remoteSignatureLength - read);

		} while (read < remoteSignatureLength);

		// verify signature
		PublicKey remoteRSAPubkey = viewTopology.getStaticConf().getRSAPublicKey(remoteId);
		if (!TOMUtil.verifySignature(remoteRSAPubkey, remoteDHPubKeyBytes, remoteSignatureBytes)) {
			LOGGER.error("Authentication fail by invalid signature from remote[{}]! ", remoteId);
			throw new SecurityException("Authentication fail by invalid signature from remote[" + remoteId + "]! ");
		}

		BigInteger remoteDHPubKey = new BigInteger(remoteDHPubKeyBytes);
		return remoteDHPubKey;
	}

	private void sendDHKey(DataOutputStream socketOutStream, PrivateKey RSAprivKey, BigInteger DHPrivKey)
			throws IOException {
		// Create DH public key
		BigInteger myDHPubKey = viewTopology.getStaticConf().getDHG().modPow(DHPrivKey,
				viewTopology.getStaticConf().getDHP());

		// turn it into a byte array
		byte[] myDHPubKeyBytes = myDHPubKey.toByteArray();

		byte[] signature = TOMUtil.signMessage(RSAprivKey, myDHPubKeyBytes);

		// send my DH public key and signature
		socketOutStream.writeInt(myDHPubKeyBytes.length);
		socketOutStream.write(myDHPubKeyBytes);

		socketOutStream.writeInt(signature.length);
		socketOutStream.write(signature);
	}

	private void waitAndConnect() {
		waitAndConnect(RETRY_INTERVAL);
	}

	/**
	 * 等待指定时间
	 *
	 * @param timeout
	 */
	private void waitAndConnect(long timeout) {
		if (doWork) {
			try {
				Thread.sleep(timeout);
			} catch (InterruptedException ie) {
			}
			reconnect();
		}
	}

	@Override
	public String toString() {
		return this.getClass().getName() + " To [" + remoteId + "]";
	}

	/**
	 * Thread used to send packets to the remote server.
	 */
	private class SenderThread extends Thread {

		private CountDownLatch countDownLatch;

		public SenderThread(CountDownLatch countDownLatch) {
			super("Sender for " + remoteId);
			this.countDownLatch = countDownLatch;
		}

		@Override
		public void run() {
			MessageSendingTask task;
			try {
				countDownLatch.await();
			} catch (InterruptedException e) {
			}
			while (doWork) {
				try {
					task = null;
					try {
						task = outQueue.poll(POOL_INTERVAL, TimeUnit.MILLISECONDS);
					} catch (InterruptedException ex) {
					}

					if (task != null) {
						processSendingTask(task);
					}
				} catch (Exception e) {
					LOGGER.error(
							"Error occurred while sending message to remote[" + remoteId + "]! --" + e.getMessage(), e);
				}
			}

			LOGGER.info("The sender thread of connection to remote[{}] stopped!", remoteId);
		}
	}

	/**
	 * Thread used to receive packets from the remote server.
	 */
	protected class ReceiverThread extends Thread {

		public ReceiverThread() {
			super("MESSAGE RECEIVER [" + remoteId + "]");
		}

		@Override
		public void run() {
			byte[] receivedMac = null;
			try {
				receivedMac = new byte[Mac.getInstance(MAC_ALGORITHM).getMacLength()];
			} catch (NoSuchAlgorithmException ex) {
				ex.printStackTrace();
			}

			while (doWork) {
				DataInputStream socketInStream = getSocketInputStream();
				if (socketInStream != null) {
					try {
						// read data length
						int dataLength = socketInStream.readInt();
						byte[] data = new byte[dataLength];

						// read data
						int read = 0;
						do {
							read += socketInStream.read(data, read, dataLength - read);
						} while (read < dataLength);

						// read mac
						boolean result = true;

						byte macFlag = socketInStream.readByte();
						boolean hasMAC = (macFlag == 1);
						if (viewTopology.getStaticConf().isUseMACs() && hasMAC) {
							read = 0;
							do {
								read += socketInStream.read(receivedMac, read, macSize - read);
							} while (read < macSize);

							result = Arrays.equals(macReceive.doFinal(data), receivedMac);
						}

						if (result) {
							SystemMessage sm = (SystemMessage) (new ObjectInputStream(new ByteArrayInputStream(data))
									.readObject());
							sm.authenticated = (viewTopology.getStaticConf().isUseMACs() && hasMAC);

							if (sm.getSender() == remoteId) {

								MessageQueue.SystemMessageType msgType = MessageQueue.SystemMessageType.typeOf(sm);

								if (!messageInQueue.offer(msgType, sm)) {
									LOGGER.error("(ReceiverThread.run) in queue full (message from {} discarded).",
											remoteId);
								}
							}
						} else {
							// TODO: violation of authentication... we should do something
							LOGGER.warn("WARNING: Violation of authentication in message received from {}", remoteId);
						}
					} catch (ClassNotFoundException ex) {
						// invalid message sent, just ignore;
					} catch (IOException ex) {
						if (doWork) {
							LOGGER.warn(
									"[ServerConnection.ReceiverThread] I will close socket and waitAndConnect connect with {}",
									remoteId);
							closeSocket();
							waitAndConnect(RECONNECT_MILL_SECONDS);
						}
					}
				} else {
					LOGGER.warn("[ServerConnection.ReceiverThread] I will waitAndConnect connect with {}", remoteId);
					waitAndConnect(RECONNECT_MILL_SECONDS);
				}
			}
		}
	}

	private static class MessageSendingTask extends AsyncFutureTask<SystemMessage, Void> {

		private boolean retry;

		private boolean useMac;

		public MessageSendingTask(SystemMessage message, boolean useMac, boolean retry) {
			super(message);
			this.useMac = useMac;
			this.retry = retry;
		}

		public boolean isRetry() {
			return retry;
		}

	}
}
