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
package bftsmart.communication.server;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.communication.SystemMessage;
import bftsmart.communication.queue.MessageQueue;
import bftsmart.reconfiguration.VMMessage;
import bftsmart.reconfiguration.ViewTopology;
import bftsmart.tom.ServiceReplica;
import bftsmart.tom.util.TOMUtil;

/**
 * This class represents a connection with other server.
 *
 * ServerConnections are created by ServerCommunicationLayer.
 *
 * @author alysson
 */
public class ServerSockectConnection implements MessageConnection {

	private static final Logger LOGGER = LoggerFactory.getLogger(ServerSockectConnection.class);

	// 重连周期
	private static final long RECONNECT_MILL_SECONDS = 5000L;
	private static final long POOL_INTERVAL = 5000;
	private final long RETRY_INTERVAL;
	private final int RETRY_COUNT;
	// private static final int SEND_QUEUE_SIZE = 50;

	private final String REALM_NAME;

	private ViewTopology viewTopology;
	private volatile Socket socket;
	private volatile DataOutputStream socketOutStream = null;
	private DataInputStream socketInStream = null;
	private int remoteId;
	private final boolean useSenderThread = true;
	private MessageQueue messageInQueue;
	private LinkedBlockingQueue<MessageSendingTask> outQueue;// = new
																// LinkedBlockingQueue<byte[]>(SEND_QUEUE_SIZE);
	private HashSet<Integer> noMACs = null; // this is used to keep track of data to be sent without a MAC.
	// It uses the reference id for that same data
//    private LinkedBlockingQueue<SystemMessage> inQueue;
	private SecretKey authKey = null;
	private Mac macSend;
	private Mac macReceive;
	private int macSize;
	private Object connectLock = new Object();
	/** Only used when there is no sender Thread */
	private Lock sendLock;
	private boolean doWork = true;
	private CountDownLatch latch = new CountDownLatch(1);

	public ServerSockectConnection(String realmName, ViewTopology viewTopology, Socket socket, int remoteId,
			MessageQueue messageInQueue) {
		this.REALM_NAME = realmName;
		this.viewTopology = viewTopology;
		this.socket = socket;

		this.remoteId = remoteId;

		this.messageInQueue = messageInQueue;

		this.outQueue = new LinkedBlockingQueue<MessageSendingTask>(viewTopology.getStaticConf().getOutQueueSize());

		this.noMACs = new HashSet<Integer>();
		// Connect to the remote process or just wait for the connection?
		if (isToConnect()) {
			// I have to connect to the remote server
			try {
				this.socket = new Socket(viewTopology.getStaticConf().getHost(remoteId),
						viewTopology.getStaticConf().getServerToServerPort(remoteId));
				SocketUtils.setSocketOptions(this.socket);
				new DataOutputStream(this.socket.getOutputStream())
						.writeInt(viewTopology.getStaticConf().getProcessId());

			} catch (UnknownHostException ex) {
				LOGGER.warn(
						"Error occurred while creating connection to remote[" + remoteId + "]! --" + ex.getMessage(),
						ex);
			} catch (IOException ex) {
				LOGGER.warn(
						"Error occurred while creating connection to remote[" + remoteId + "]! --" + ex.getMessage(),
						ex);
			}
		}
		// else I have to wait a connection from the remote server

		if (this.socket != null) {
			try {
				socketOutStream = new DataOutputStream(this.socket.getOutputStream());
				socketInStream = new DataInputStream(this.socket.getInputStream());
			} catch (IOException ex) {
				LOGGER.error("Error occurred while creating connection to {}", remoteId);
			}
		}

		// ******* EDUARDO BEGIN **************//
//		this.useSenderThread = controller.getStaticConf().isUseSenderThread();
		this.RETRY_INTERVAL = viewTopology.getStaticConf().getSendRetryInterval();
		this.RETRY_COUNT = viewTopology.getStaticConf().getSendRetryCount();

		LOGGER.info("I am proc {}, useSenderThread = {}", viewTopology.getStaticConf().getProcessId(),
				this.useSenderThread);

//		if (useSenderThread && (viewTopology.getStaticConf().getTTPId() != remoteId)) {
		if (useSenderThread) {
			new SenderThread(latch).start();
		} else {
			sendLock = new ReentrantLock();
		}
		authenticateAndEstablishAuthKey();

//		if (!viewTopology.getStaticConf().isTheTTP()) {
//			// disable TTP
//			if (viewTopology.getStaticConf().getTTPId() == remoteId) {
//				// Uma thread "diferente" para as msgs recebidas da TTP
//				new TTPReceiverThread(replica).start();
//			} else {
//				new ReceiverThread().start();
//			}
//		}
		
		new ReceiverThread().start();
		// ******* EDUARDO END **************//
	}

//    public ServerConnection(ServerViewController controller, Socket socket, int remoteId,
//                            LinkedBlockingQueue<SystemMessage> inQueue, ServiceReplica replica) {
//
//        this.controller = controller;
//
//        this.socket = socket;
//
//        this.remoteId = remoteId;
//
//        this.inQueue = inQueue;
//
//        this.outQueue = new LinkedBlockingQueue<byte[]>(controller.getStaticConf().getOutQueueSize());
//
//        this.noMACs = new HashSet<Integer>();
//        // Connect to the remote process or just wait for the connection?
//        if (isToConnect()) {
//            //I have to connect to the remote server
//            try {
//                this.socket = new Socket(controller.getStaticConf().getHost(remoteId),
//                        controller.getStaticConf().getServerToServerPort(remoteId));
//                ServersCommunicationLayer.setSocketOptions(this.socket);
//                new DataOutputStream(this.socket.getOutputStream()).writeInt(controller.getStaticConf().getProcessId());
//
//            } catch (UnknownHostException ex) {
//                ex.printStackTrace();
//            } catch (IOException ex) {
//                ex.printStackTrace();
//            }
//        }
//        //else I have to wait a connection from the remote server
//
//        if (this.socket != null) {
//            try {
//                socketOutStream = new DataOutputStream(this.socket.getOutputStream());
//                socketInStream = new DataInputStream(this.socket.getInputStream());
//            } catch (IOException ex) {
//                LOGGER.debug("Error creating connection to "+remoteId);
//                ex.printStackTrace();
//            }
//        }
//
//        //******* EDUARDO BEGIN **************//
//        this.useSenderThread = controller.getStaticConf().isUseSenderThread();
//
//        if (useSenderThread && (controller.getStaticConf().getTTPId() != remoteId)) {
//            new SenderThread(latch).start();
//        } else {
//            sendLock = new ReentrantLock();
//        }
//        authenticateAndEstablishAuthKey();
//
//        if (!controller.getStaticConf().isTheTTP()) {
//            if (controller.getStaticConf().getTTPId() == remoteId) {
//                //Uma thread "diferente" para as msgs recebidas da TTP
//                new TTPReceiverThread(replica).start();
//            } else {
//                new ReceiverThread().start();
//            }
//        }
//        //******* EDUARDO END **************//
//    }

	@Override
	public SecretKey getSecretKey() {
		return authKey;
	}

	/**
	 * Stop message sending and reception.
	 */
	@Override
	public void shutdown() {
		LOGGER.info("SHUTDOWN for {}", remoteId);

		doWork = false;
		closeSocket();

		outQueue.clear();
	}

	/**
	 * Used to send packets to the remote server.
	 */
	@Override
	public final AsyncFuture<byte[], Void> send(byte[] data, boolean useMAC, CompletedCallback<byte[], Void> callback) {
		return send(data, useMAC, true, callback);
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
	public final AsyncFuture<byte[], Void> send(byte[] data, boolean useMAC, boolean retrySending,
			CompletedCallback<byte[], Void> callback) {
		// 禁用使用内部线程，而是采用独立线程，并返回;

		if (!useMAC) {
			LOGGER.debug("(ServerConnection.send) Not sending defaultMAC {}", System.identityHashCode(data));
			noMACs.add(System.identityHashCode(data));
		}

		MessageSendingTask task = new MessageSendingTask(data, retrySending);
		task.setCallback(callback);

		if (!outQueue.offer(task)) {
			LOGGER.error("(ServerConnection.send) out queue for {} full (message discarded).", remoteId);

			task.error(new IllegalStateException(
					"(ServerConnection.send) out queue for {" + remoteId + "} full (message discarded)."));
		}

		return task;

//        if (useSenderThread) {
//            //only enqueue messages if there queue is not full
//            if (!useMAC) {
//                LOGGER.debug("(ServerConnection.send) Not sending defaultMAC {}", System.identityHashCode(data));
//                noMACs.add(System.identityHashCode(data));
//            }
//
//            if (!outQueue.offer(data)) {
//                LOGGER.error("(ServerConnection.send) out queue for {} full (message discarded).", remoteId);
//            }
//        } else {
//            sendLock.lock();
//            sendBytes(data, useMAC);
//            sendLock.unlock();
//        }

	}

	/**
	 * try to send a message through the socket if some problem is detected, a
	 * reconnection is done
	 */
	private final void sendBytes(MessageSendingTask messageTask, boolean useMAC) {
		int counter = 0;
		byte[] messageData = messageTask.getSource();
		Throwable error = null;
		do {
			try {
				// if there is a need to reconnect, abort this method
				if (socket != null && socketOutStream != null) {
					try {
						// do an extra copy of the data to be sent, but on a single out stream write
						byte[] mac = (useMAC && viewTopology.getStaticConf().getUseMACs() == 1)
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

	// ******* EDUARDO BEGIN **************//
	// return true of a process shall connect to the remote process, false otherwise
	private boolean isToConnect() {
		// remove TTP；
//		if (viewTopology.getStaticConf().getTTPId() == remoteId) {
//			// Need to wait for the connection request from the TTP, do not tray to connect
//			// to it
//			return false;
//		} else if (viewTopology.getStaticConf().getTTPId() == viewTopology.getStaticConf().getProcessId()) {
//			// If this is a TTP, one must connect to the remote process
//			return true;
//		}
		boolean ret = false;
		if (this.viewTopology.isInCurrentView()) {

			// in this case, the node with higher ID starts the connection
			if (viewTopology.getStaticConf().getProcessId() > remoteId) {
				ret = true;
			}

			/**
			 * JCS: I commented the code below to fix a bug, but I am not sure whether its
			 * completely useless or not. The 'if' above was taken from that same code (its
			 * the only part I understand why is necessary) I keep the code commented just
			 * to be on the safe side
			 */

			/**
			 * 
			 * boolean me =
			 * this.controller.isInLastJoinSet(controller.getStaticConf().getProcessId());
			 * boolean remote = this.controller.isInLastJoinSet(remoteId);
			 * 
			 * //either both endpoints are old in the system (entered the system in a
			 * previous view), //or both entered during the last reconfiguration if ((me &&
			 * remote) || (!me && !remote)) { //in this case, the node with higher ID starts
			 * the connection if (controller.getStaticConf().getProcessId() > remoteId) {
			 * ret = true; } //this process is the older one, and the other one entered in
			 * the last reconfiguration } else if (!me && remote) { ret = true;
			 * 
			 * } //else if (me && !remote) { //this process entered in the last reconfig and
			 * the other one is old //ret=false; //not necessary, as ret already is false
			 * //}
			 * 
			 */
		}
		return ret;
	}
	// ******* EDUARDO END **************//

	/**
	 * (Re-)establish connection between peers.
	 *
	 * @param newSocket socket created when this server accepted the connection
	 *                  (only used if processId is less than remoteId)
	 */
	protected void reconnect(Socket newSocket) {
		synchronized (connectLock) {
			if (socket != null && socket.isConnected()) {
				return;
			}

			try {
				// ******* EDUARDO BEGIN **************//
				if (isToConnect()) {
					socket = new Socket(viewTopology.getStaticConf().getHost(remoteId),
							viewTopology.getStaticConf().getServerToServerPort(remoteId));
					SocketUtils.setSocketOptions(socket);
					new DataOutputStream(socket.getOutputStream())
							.writeInt(viewTopology.getStaticConf().getProcessId());

					// ******* EDUARDO END **************//
				} else {
					socket = newSocket;
				}
			} catch (UnknownHostException ex) {
				LOGGER.warn(
						"Error occurred while reconnecting to remote replic[" + remoteId + "]! -- " + ex.getMessage(),
						ex);
			} catch (IOException ex) {
				LOGGER.warn(
						"Error occurred while reconnecting to remote replic[" + remoteId + "]! -- " + ex.getMessage(),
						ex);
			}

			if (socket != null) {
				try {
					socketOutStream = new DataOutputStream(socket.getOutputStream());
					socketInStream = new DataInputStream(socket.getInputStream());

					authKey = null;
					authenticateAndEstablishAuthKey();
				} catch (IOException ex) {
					LOGGER.error("Authentication fails while reconnect to remote replica[" + remoteId + "] ! --"
							+ ex.getMessage(), ex);
				}
			}
		}
	}

	// TODO!
	private void authenticateAndEstablishAuthKey() {
		if (authKey != null || socketOutStream == null || socketInStream == null) {
			return;
		}

		try {
			// if (conf.getProcessId() > remoteId) {
			// I asked for the connection, so I'm first on the auth protocol
			// DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
			// } else {
			// I received a connection request, so I'm second on the auth protocol
			// DataInputStream dis = new DataInputStream(socket.getInputStream());
			// }

			// Derive DH private key from replica's own RSA private key

			PrivateKey RSAprivKey = viewTopology.getStaticConf().getRSAPrivateKey();
			BigInteger DHPrivKey = new BigInteger(RSAprivKey.getEncoded());

			// Create DH public key
			BigInteger myDHPubKey = viewTopology.getStaticConf().getDHG().modPow(DHPrivKey,
					viewTopology.getStaticConf().getDHP());

			// turn it into a byte array
			byte[] bytes = myDHPubKey.toByteArray();

			byte[] signature = TOMUtil.signMessage(RSAprivKey, bytes);

			if (authKey == null && socketOutStream != null && socketInStream != null) {
				// send my DH public key and signature
				socketOutStream.writeInt(bytes.length);
				socketOutStream.write(bytes);

				socketOutStream.writeInt(signature.length);
				socketOutStream.write(signature);

				// receive remote DH public key and signature
				int dataLength = socketInStream.readInt();
				bytes = new byte[dataLength];
				int read = 0;
				do {
					read += socketInStream.read(bytes, read, dataLength - read);

				} while (read < dataLength);

				byte[] remote_Bytes = bytes;

				dataLength = socketInStream.readInt();
				bytes = new byte[dataLength];
				read = 0;
				do {
					read += socketInStream.read(bytes, read, dataLength - read);

				} while (read < dataLength);

				byte[] remote_Signature = bytes;

				// verify signature
				PublicKey remoteRSAPubkey = viewTopology.getStaticConf().getRSAPublicKey(remoteId);

				if (!TOMUtil.verifySignature(remoteRSAPubkey, remote_Bytes, remote_Signature)) {

					LOGGER.error("{} sent an invalid signature!", remoteId);
					shutdown();
					return;
				}

				BigInteger remoteDHPubKey = new BigInteger(remote_Bytes);

				// Create secret key
				BigInteger secretKey = remoteDHPubKey.modPow(DHPrivKey, viewTopology.getStaticConf().getDHP());

				LOGGER.info("[{}] ->  I am proc {}, -- Diffie-Hellman complete with proc id {}, with port {}",
						REALM_NAME, viewTopology.getStaticConf().getProcessId(), remoteId,
						viewTopology.getStaticConf().getServerToServerPort(remoteId));

				SecretKeyFactory fac = SecretKeyFactory.getInstance("PBEWithMD5AndDES");
				PBEKeySpec spec = new PBEKeySpec(secretKey.toString().toCharArray());

				// PBEKeySpec spec = new PBEKeySpec(PASSWORD.toCharArray());
				authKey = fac.generateSecret(spec);

				macSend = Mac.getInstance(MAC_ALGORITHM);
				macSend.init(authKey);
				macReceive = Mac.getInstance(MAC_ALGORITHM);
				macReceive.init(authKey);
				macSize = macSend.getMacLength();
				latch.countDown();
			}
		} catch (Exception ex) {
			LOGGER.error("Error occurred while doing authenticateAndEstablishAuthKey with remote replica[" + remoteId
					+ "] ! --" + ex.getMessage(), ex);
		}
	}

	/**
	 * 关闭连接；此方法不抛出任何异常；
	 */
	private void closeSocket() {
		Socket sk = socket;
		DataOutputStream os = socketOutStream;
		DataInputStream is = socketInStream;

		socket = null;
		socketOutStream = null;
		socketInStream = null;

		if (os != null) {
			try {
				os.close();
			} catch (Exception e) {
			}
		}
		if (is != null) {
			try {
				is.close();
			} catch (Exception e) {
			}
		}
		if (sk != null) {
			try {
				sk.close();
			} catch (Exception e) {
			}
		}
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
			reconnect(null);
		}
	}

	@Override
	public String toString() {
		return "ServerConnection[RemoteID: " + remoteId + "]-outQueue: " + outQueue;
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
			MessageSendingTask task = null;
			try {
				countDownLatch.await();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			while (doWork) {
				// get a message to be sent
				try {
					task = outQueue.poll(POOL_INTERVAL, TimeUnit.MILLISECONDS);
				} catch (InterruptedException ex) {
				}

				if (task != null) {
					// sendBytes(data, noMACs.contains(System.identityHashCode(data)));
					int ref = System.identityHashCode(task.getSource());
					boolean sendMAC = !noMACs.remove(ref);
					LOGGER.debug("(ServerConnection.run) {} MAC for data {}", (sendMAC ? "Sending" : "Not sending"),
							ref);
					sendBytes(task, sendMAC);
				}
			}

			LOGGER.debug("Sender for {} stopped!", remoteId);
		}
	}

	/**
	 * Thread used to receive packets from the remote server.
	 */
	protected class ReceiverThread extends Thread {

		public ReceiverThread() {
			super("Receiver for " + remoteId);
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
				if (socket != null && socketInStream != null) {
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

						byte hasMAC = socketInStream.readByte();
						if (viewTopology.getStaticConf().getUseMACs() == 1 && hasMAC == 1) {
							read = 0;
							do {
								read += socketInStream.read(receivedMac, read, macSize - read);
							} while (read < macSize);

							result = Arrays.equals(macReceive.doFinal(data), receivedMac);
						}

						if (result) {
							SystemMessage sm = (SystemMessage) (new ObjectInputStream(new ByteArrayInputStream(data))
									.readObject());
							sm.authenticated = (viewTopology.getStaticConf().getUseMACs() == 1 && hasMAC == 1);

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

	// ******* EDUARDO BEGIN: special thread for receiving messages indicating the
	// entrance into the system, coming from the TTP **************//
	// Simly pass the messages to the replica, indicating its entry into the system
	// TODO: Ask eduardo why a new thread is needed!!!
	// TODO2: Remove all duplicated code

	/**
	 * Thread used to receive packets from the remote server.
	 */
	protected class TTPReceiverThread extends Thread {

		private ServiceReplica replica;

		public TTPReceiverThread(ServiceReplica replica) {
			super("TTPReceiver for " + remoteId);
			this.replica = replica;
		}

		@Override
		public void run() {
			byte[] receivedMac = null;
			try {
				receivedMac = new byte[Mac.getInstance(MAC_ALGORITHM).getMacLength()];
			} catch (NoSuchAlgorithmException ex) {
			}

			while (doWork) {
				if (socket != null && socketInStream != null) {
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

						byte hasMAC = socketInStream.readByte();
						if (viewTopology.getStaticConf().getUseMACs() == 1 && hasMAC == 1) {

							LOGGER.debug("TTP CON USEMAC");
							read = 0;
							do {
								read += socketInStream.read(receivedMac, read, macSize - read);
							} while (read < macSize);

							result = Arrays.equals(macReceive.doFinal(data), receivedMac);
						}

						if (result) {
							SystemMessage sm = (SystemMessage) (new ObjectInputStream(new ByteArrayInputStream(data))
									.readObject());

							if (sm.getSender() == remoteId) {
								// LOGGER.debug("Mensagem recebia de: "+remoteId);
								/*
								 * if (!inQueue.offer(sm)) { bftsmart.tom.util.LOGGER.
								 * debug("(ReceiverThread.run) in queue full (message from " + remoteId +
								 * " discarded).");
								 * LOGGER.debug("(ReceiverThread.run) in queue full (message from " + remoteId +
								 * " discarded)."); }
								 */
								this.replica.joinMsgReceived((VMMessage) sm);
							}
						} else {
							// TODO: violation of authentication... we should do something
							LOGGER.warn("WARNING: Violation of authentication in message received from {}", remoteId);
						}
					} catch (ClassNotFoundException ex) {
						ex.printStackTrace();
					} catch (IOException ex) {
						// ex.printStackTrace();
						if (doWork) {
							LOGGER.error(
									"[ServerConnection.TTPReceiverThread] I will close socket and waitAndConnect connect with {}",
									remoteId);
							closeSocket();
							waitAndConnect();
						}
					}
				} else {
					LOGGER.error("[ServerConnection.TTPReceiverThread] I will waitAndConnect connect with {}",
							remoteId);
					waitAndConnect();
				}
			}
		}
	}
	// ******* EDUARDO END **************//

	private static class MessageSendingTask extends AsyncFutureTask<byte[], Void> {

		private boolean retry;

		public MessageSendingTask(byte[] message, boolean retry) {
			super(message);
			this.retry = retry;
		}

		public boolean isRetry() {
			return retry;
		}

	}
}