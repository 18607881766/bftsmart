package bftsmart.communication.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.communication.DHPubKeyCertificate;
import bftsmart.communication.IllegalMessageException;
import bftsmart.communication.MacKey;
import bftsmart.communication.MacKeyGenerator;
import bftsmart.communication.MacMessageCodec;
import bftsmart.communication.MessageAuthenticationException;
import bftsmart.communication.SystemMessage;
import bftsmart.communication.SystemMessageCodec;
import bftsmart.communication.queue.MessageQueue;
import bftsmart.reconfiguration.ViewTopology;
import utils.io.BytesUtils;

/**
 * AbstractStreamConnection 实现了基于流的消息连接对象；
 * 
 * @author huanghaiquan
 *
 */
public abstract class AbstractStreamConnection implements MessageConnection {

	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractStreamConnection.class);

	// 发送队列为空时每次检查的超时时长（毫秒）；
	private static final long OUT_QUEUE_EMPTY_TIMEOUT = 5000;

	// 每次重建连接的等待超时时长（毫秒）；
	private static final long CONNECTION_REBUILD_TIMEOUT = 20 * 1000;

	/**
	 * 最大消息尺寸 100MB；
	 */
	private final int MAX_MESSAGE_SIZE = 100 * 1024 * 1024;

	private final int MAX_RETRY_COUNT;

	protected final String REALM_NAME;
	protected final int ME;
	protected final int REMOTE_ID;

	private final MacKeyGenerator MAC_KEY_GEN;

	protected ViewTopology viewTopology;
	private MessageQueue messageInQueue;
	private LinkedBlockingQueue<MessageSendingTask> outQueue;

	private SystemMessageCodec messageCodec;

	private volatile boolean doWork = false;

	private volatile Thread senderTread;

	private volatile Thread receiverThread;

	public AbstractStreamConnection(String realmName, ViewTopology viewTopology, int remoteId,
			MessageQueue messageInQueue) {
		this.REALM_NAME = realmName;
		this.ME = viewTopology.getCurrentProcessId();
		this.REMOTE_ID = remoteId;

		this.MAC_KEY_GEN = new MacKeyGenerator(viewTopology.getStaticConf().getRSAPublicKey(),
				viewTopology.getStaticConf().getRSAPrivateKey(), viewTopology.getStaticConf().getDHG(),
				viewTopology.getStaticConf().getDHP());

		this.messageCodec = new SystemMessageCodec();
		this.messageCodec.setUseMac(viewTopology.getStaticConf().isUseMACs());
		this.viewTopology = viewTopology;
		this.messageInQueue = messageInQueue;

		this.outQueue = new LinkedBlockingQueue<MessageSendingTask>(viewTopology.getStaticConf().getOutQueueSize());

		this.MAX_RETRY_COUNT = viewTopology.getStaticConf().getSendRetryCount();
		if (MAX_RETRY_COUNT < 1) {
			throw new IllegalArgumentException("Illegal SEND_RETRY_COUNT[" + MAX_RETRY_COUNT + "]!");
		}

		LOGGER.debug("Create stream connection from {} to {}!", ME, REMOTE_ID);
	}

	protected boolean isDoWork() {
		return doWork;
	}

	@Override
	public synchronized void start() {
		if (doWork) {
			return;
		}
		doWork = true;

		senderTread = new Thread(new Runnable() {
			public void run() {
				scheduleSending();
			}
		}, "Sender-Thread-To-Remote[" + REMOTE_ID + "]");
		senderTread.setDaemon(true);

		receiverThread = new Thread(new Runnable() {
			@Override
			public void run() {
				scheduleReceiving();
			}
		}, "Receiver-Thread-From-Remote[" + REMOTE_ID + "]");
		receiverThread.setDaemon(true);

		senderTread.start();
		receiverThread.start();

		LOGGER.debug("Start connection! --[Me={}][Remote={}]", ME, REMOTE_ID);
	}

	@Override
	public int getRemoteId() {
		return REMOTE_ID;
	}

	@Override
	public MacMessageCodec<SystemMessage> getMessageCodec() {
		return messageCodec;
	}

	/**
	 * Stop message sending and reception.
	 */
	@Override
	public synchronized void shutdown() {
		if (!doWork) {
			return;
		}
		LOGGER.info("SHUTDOWN for {}", REMOTE_ID);

		doWork = false;

		senderTread.interrupt();
		receiverThread.interrupt();

		senderTread = null;
		receiverThread = null;

		closeConnection();

		LOGGER.debug("Shutdown connection! --[Me={}][Remote={}]", ME, REMOTE_ID);
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
	public AsyncFuture<SystemMessage, Void> send(SystemMessage message, boolean retrySending,
			CompletedCallback<SystemMessage, Void> callback) {
		MessageSendingTask task = new MessageSendingTask(message, retrySending);
		task.setCallback(callback);

		if (!outQueue.offer(task)) {
			LOGGER.error("(ServerConnection.send) out queue for {} full (message discarded).", REMOTE_ID);

			task.error(new IllegalStateException(
					"(ServerConnection.send) out queue for {" + REMOTE_ID + "} full (message discarded)."));
		}

		return task;
	}

	/**
	 * 驻留后台线程，执行消息发送；
	 */
	private final void scheduleSending() {
		MessageSendingTask task;
		while (doWork) {
			try {
				// 检查发送队列；
				task = null;
				try {
					task = outQueue.take();
				} catch (InterruptedException ex) {
				}

				if (task != null) {
					// 处理发送任务；
					processSendingTask(task);
				}
			} catch (Exception e) {
				LOGGER.error("Error occurred while sending message to remote[" + REMOTE_ID + "]! --" + e.getMessage(),
						e);
			}
		}

		LOGGER.info("The sending task schedule of connection to remote[{}] stopped!", REMOTE_ID);
	}

	/**
	 * try to send a message through the socket if some problem is detected, a
	 * reconnection is done
	 */
	private final void processSendingTask(MessageSendingTask messageTask) {
//		byte[] outputBytes = generateOutputBytes(messageTask.getSource(), messageTask.USE_MAC);
		byte[] outputBytes = messageCodec.encode(messageTask.getSource());

		int retryCount = 0;
		DataOutputStream out = getOutputStream();
		do {
			try {
				// 检查连接；
				try {
					if (out == null) {
						out = rebuildOutputConnection(CONNECTION_REBUILD_TIMEOUT);
					}
				} catch (IOException e) {
					// 建立连接时发生网络IO错误；
					LOGGER.error("Error occurred while connecting to remote! --[Me=" + ME + "][Remote=" + REMOTE_ID
							+ "] " + e.getMessage(), e);
					out = null;
				}

				// 当连接未建立时：
				// 对于无需重试发送的消息，则直接丢弃；
				// 对于需要重试发送的消息，则一直等待直到连接重新建立为止；
				if (out == null) {
					if (!messageTask.RETRY) {
						// 抛弃连接；
						messageTask.error(new IllegalStateException("Connection has not been established!"));
						LOGGER.warn(
								"Discard the message because connection has not been established and the task has no retry indication! --[Me={}][Remote={}]",
								ME, REMOTE_ID);
						return;
					}

					if (retryCount >= MAX_RETRY_COUNT) {
						// 抛弃连接；
						messageTask.error(
								new IllegalStateException("Connection has not been established after retrying!"));
						LOGGER.warn(
								"Discard the message because connection has not been established after retrying! --[Me={}][Remote={}]",
								ME, REMOTE_ID);
						return;
					}

					retryCount++;
					continue;
				}

				IOException error = null;
				// if there is a need to reconnect, abort this method
				try {
					BytesUtils.writeInt(outputBytes.length, out);
					out.write(outputBytes);
					out.flush();
					messageTask.complete(null);
					return;
				} catch (IOException ex) {
					try {
						out.close();
					} catch (Exception e) {
					}
					out = null;
					error = ex;
				}

				// 写数据时发生网络IO错误；
				// 如果不重试发送失败的消息，则立即报告错误；
				if (!messageTask.RETRY) {
					messageTask.error(error);
					LOGGER.error(
							"Discard the message due to the io error and no retry indication! --" + error.getMessage(),
							error);
					return;
				}

				// 如果不重试发送失败的消息，则立即报告错误；
				if (retryCount++ >= MAX_RETRY_COUNT) {
					LOGGER.error("Discard the message due to the io error after retrying! --[Me=" + ME + "][Remote="
							+ REMOTE_ID + "]" + error.getMessage(), error);
					messageTask.error(error);
					return;
				}

				// 重试；
				out = null;
				retryCount++;

			} catch (Exception e) {
				// 发生了未知的错误，不必重试，直接丢弃消息；
				LOGGER.error("Discard the message due to the unknown error! --[Me=" + ME + "][Remote=" + REMOTE_ID + "]"
						+ e.getMessage(), e);
				messageTask.error(e);
				return;
			}
		} while (doWork && retryCount < MAX_RETRY_COUNT);

		messageTask.error(new IllegalStateException("Completed in unexpected state!"));
	}

	/**
	 * 驻留后台线程，执行消息接收；
	 */
	private void scheduleReceiving() {
		DataInputStream in = null;
		try {
			in = getInputStream();
		} catch (Exception e) {
			LOGGER.error("Unexpected error occurred while start receiving message from remote! --[Me=" + ME
					+ "][Remote=" + REMOTE_ID + "] " + e.getMessage(), e);
		}

		while (doWork) {
			// 检查连接；
			try {
				if (in == null) {
					in = rebuildInputConnection(CONNECTION_REBUILD_TIMEOUT);
				}
			} catch (Exception e) {
				// 建立连接时发生网络IO错误；重试建立连接；
				LOGGER.error("Error occurred while connecting to remote! --[Me=" + ME + "][Remote=" + REMOTE_ID + "] "
						+ e.getMessage(), e);
				continue;
			}
			if (in == null) {
				// 重试，直到建立连接；
				continue;
			}

			try {
				// read message;
				SystemMessage sm = null;
				try {
					sm = readMessage(in);
				} catch (IOException e) {
					// 接收消息时发生网络错误；需要重新建立连接；
					LOGGER.error("Error occurred while reading the input message! --[Me=" + ME + "][Remote=" + REMOTE_ID
							+ "] " + e.getMessage(), e);
					try {
						in.close();
					} catch (Exception e1) {
					}
					in = null;
					continue;
				}

				if (sm == null) {
					continue;
				}
				if (sm.getSender() == REMOTE_ID) {
					MessageQueue.SystemMessageType msgType = MessageQueue.SystemMessageType.typeOf(sm);
					if (!messageInQueue.offer(msgType, sm)) {
						LOGGER.error("Discard message because the input queue is full! [Me={}][Remote={}]", ME,
								REMOTE_ID);
					}
				} else {
					LOGGER.error(
							"Discard the received message from wrong sender!  --[Sender={}][ExpectedSender={}][Me={}]",
							sm.getSender(), REMOTE_ID, ME);
				}
			} catch (Exception e) {
				LOGGER.error("Unknown error occurred! --[Me=" + ME + "][Remote=" + REMOTE_ID + "] " + e.getMessage(),
						e);
			}
		} // End of: while (doWork);
	}// End of : private void scheduleReceivingTask()

	/**
	 * 从输入流读消息；
	 * <p>
	 * 
	 * 如果输入流发生错误，则抛出 {@link IOException}；
	 * 
	 * @param in
	 * @return
	 * @throws IOException
	 */
	private SystemMessage readMessage(DataInputStream in) throws IOException {
		// 读消息字节；
//		int length = in.readInt();
		int length = BytesUtils.readInt(in);
		if (length > MAX_MESSAGE_SIZE) {
			LOGGER.error("Illegal message size! --[MessageSize={}][Me={}]", length, ME);
			throw new IOException("Illegal message size[" + length + "]!");
		}
		byte[] encodedMessageBytes = new byte[length];
		int read = 0;
		do {
			read += in.read(encodedMessageBytes, read, length - read);
		} while (read < length);

		try {
			return messageCodec.decode(encodedMessageBytes);
		} catch (MessageAuthenticationException | IllegalMessageException e) {
			String errMsg = String.format("The MAC Validation of the received message fail! --[Me=%s][Remote=%s] %s",
					ME, REMOTE_ID, e.getMessage());
			LOGGER.error(errMsg, e);
			return null;
		}

	}

	/**
	 * 重建连接；
	 * <p>
	 * 
	 * @throws IOException
	 */
	protected abstract void rebuildConnection(long timeoutMillis) throws IOException;

	/**
	 * 关闭连接；此方法不抛出任何异常；
	 */
	protected abstract void closeConnection();

	/**
	 * 返回用于发送数据的输出流；
	 * <p>
	 *  如果连接未建立或者连接无效，则返回 null；
	 * 
	 * @return
	 */
	protected abstract DataOutputStream getOutputStream();

	/**
	 * 返回用于接收数据的输入流；
	 * <p>
	 * 
	 * 如果连接未建立或者连接无效，则返回 null；
	 * 
	 * @return
	 */
	protected abstract DataInputStream getInputStream();

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
			// 发送 DH key；
			DHPubKeyCertificate currentDHPubKeyCert = MAC_KEY_GEN.getDHPubKeyCertificate();
			sendDHKey(socketOutStream, currentDHPubKeyCert);

			// 接收 DH key;
			DHPubKeyCertificate remoteDHPubKeyCert = receiveDHKey(socketInStream);
			if (remoteDHPubKeyCert == null) {
				// 认证失败；
				LOGGER.error("The DHPubKey verification failed while establishing connection with remote[{}]!",
						REMOTE_ID);
				return false;
			}

			// 生成共享密钥
			MacKey macKey = MAC_KEY_GEN.exchange(remoteDHPubKeyCert);
			messageCodec.setMacKey(macKey);

			return true;

		} catch (Exception ex) {
			LOGGER.error("Error occurred while doing authenticateAndEstablishAuthKey with remote replica[" + REMOTE_ID
					+ "] ! --" + ex.getMessage(), ex);
			return false;
		}
	}

	private void sendDHKey(DataOutputStream socketOutStream, DHPubKeyCertificate currentDHPubKeyCert)
			throws IOException {
		byte[] encodedBytes = currentDHPubKeyCert.getEncodedBytes();

		// send my DH public key and signature
		socketOutStream.writeInt(encodedBytes.length);
		socketOutStream.write(encodedBytes);
	}

	private void resetMAC() {
		messageCodec.setMacKey(null);
	}

	/**
	 * 接收和验证“密钥交互公钥凭证”；
	 * <p>
	 * 如果验证失败，则返回 null;
	 * 
	 * @param socketInStream
	 * @return
	 * @throws IOException
	 */
	private DHPubKeyCertificate receiveDHKey(DataInputStream socketInStream) throws IOException {
		// receive remote DH public key and signature
		int remoteMacPubKeyCertLength = socketInStream.readInt();
		byte[] remoteMacPubKeyCertBytes = new byte[remoteMacPubKeyCertLength];
		int read = 0;
		do {
			read += socketInStream.read(remoteMacPubKeyCertBytes, read, remoteMacPubKeyCertLength - read);

		} while (read < remoteMacPubKeyCertLength);

		return MacKeyGenerator.resolveAndVerify(remoteMacPubKeyCertBytes,
				viewTopology.getStaticConf().getRSAPublicKey(REMOTE_ID));
	}

	/**
	 * 重建连接；
	 * <p>
	 * 
	 * 此方法将堵塞当前线程，直到重新建立了连接并成功返回一个新的输出流；
	 * 
	 * @return 输出流；
	 * @throws IOException
	 */
	private DataOutputStream rebuildOutputConnection(long timeoutMillis) throws IOException {
		reconnect(timeoutMillis);
		return getOutputStream();
	}

	/**
	 * 重建连接；
	 * <p>
	 * 
	 * 此方法将堵塞当前线程，直到重新建立了连接并成功返回一个新的输出流；
	 * 
	 * @return 输出流；
	 * @throws IOException
	 */
	private DataInputStream rebuildInputConnection(long timeoutMillis) throws IOException {
		reconnect(timeoutMillis);
		return getInputStream();
	}

	private synchronized void reconnect(long timeoutMillis) throws IOException {
		// TODO: 处理发送线程和接收线程可能会并发地引发重连的问题；
		rebuildConnection(timeoutMillis);

		DataOutputStream socketOutStream = getOutputStream();
		DataInputStream socketInStream = getInputStream();
		if (socketOutStream != null && socketInStream != null) {
			resetMAC();

			boolean success = authenticate(socketOutStream, socketInStream);
			if (!success) {
				closeConnection();
			}
		}
	}

	@Override
	public String toString() {
		return this.getClass().getName() + " To [" + REMOTE_ID + "]";
	}

	private static class MessageSendingTask extends AsyncFutureTask<SystemMessage, Void> {

		public final boolean RETRY;

		public MessageSendingTask(SystemMessage message, boolean retry) {
			super(message);
			this.RETRY = retry;
		}

	}
}