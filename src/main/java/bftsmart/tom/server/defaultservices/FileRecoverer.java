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
package bftsmart.tom.server.defaultservices;

import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;

public class FileRecoverer {

	private byte[] ckpHash;
	private int ckpLastConsensusId;
	private int logLastConsensusId;
	
	private int replicaId;
	private String defaultDir;

	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(FileRecoverer.class);


	public FileRecoverer(int replicaId, String defaultDir) {
		this.replicaId = replicaId;
		this.defaultDir = defaultDir;
		ckpLastConsensusId = 0;
		logLastConsensusId = 0;
	}
	
	/**
	 * Reads all log messages from the last log file created
	 * @return an array with batches of messages executed for each consensus
	 */
//	public CommandsInfo[] getLogState() {
//		String lastLogFilename = getLatestFile(".log");
//		if(lastLogFilename != null)
//			return getLogState(0, lastLogFilename);
//		return null;
//	}

	public CommandsInfo[] getLogState(int index, String logPath) {
		RandomAccessFile log = null;

		LOGGER.info("GETTING LOG FROM {}", logPath);
		if ((log = openLogFile(logPath)) != null) {

			CommandsInfo[] logState = recoverLogState(log, index);

			try {
				log.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

			return logState;
		}
		LOGGER.info("Open log file fail, return null!");
		return null;
	}

	/**
	 * Recover portions of the log for collaborative state transfer.
	 * @param start the index for which the commands start to be collected
	 * @param number the number of commands retrieved
	 * @return The commands for the period selected
	 */
	public CommandsInfo[] getLogState(long pointer, int startOffset,  int number, String logPath) {
		RandomAccessFile log = null;

		LOGGER.debug("GETTING LOG FROM {}", logPath);
		if ((log = openLogFile(logPath)) != null) {

			CommandsInfo[] logState = recoverLogState(log, pointer, startOffset, number);

			try {
				log.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

			return logState;
		}

		return null;
	}

	public byte[] getCkpState(String ckpPath) {
		RandomAccessFile ckp = null;

		LOGGER.debug("GETTING CHECKPOINT FROM {}", ckpPath);
		if ((ckp = openLogFile(ckpPath)) != null) {

			byte[] ckpState = recoverCkpState(ckp);

			try {
				ckp.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

			return ckpState;
		}

		return null;
	}

	public void recoverCkpHash(String ckpPath) {
		RandomAccessFile ckp = null;

		LOGGER.debug("GETTING HASH FROM CHECKPOINT {}", ckpPath);
		if ((ckp = openLogFile(ckpPath)) != null) {
			byte[] ckpHash = null;
			try {
				int ckpSize = ckp.readInt();
				ckp.skipBytes(ckpSize);
				int hashLength = ckp.readInt();
				ckpHash = new byte[hashLength];
				ckp.read(ckpHash);
				LOGGER.debug("--- Last ckp size: {}, Last ckp hash: {}", ckpSize, Arrays.toString(ckpHash));
			} catch (Exception e) {
				e.printStackTrace();
				LOGGER.error("State recover was aborted due to an unexpected exception");
			}
			this.ckpHash = ckpHash;
		}
	}

	private byte[] recoverCkpState(RandomAccessFile ckp) {
		byte[] ckpState = null;
		try {
			long ckpLength = ckp.length();
			boolean mayRead = true;
			while (mayRead) {
				try {
					if (ckp.getFilePointer() < ckpLength) {
						int size = ckp.readInt();
						if (size > 0) {
							ckpState = new byte[size];//ckp state
							int read = ckp.read(ckpState);
							if (read == size) {
								int hashSize = ckp.readInt();
								if (hashSize > 0) {
									ckpHash = new byte[hashSize];//ckp hash
									read = ckp.read(ckpHash);
									if (read == hashSize) {
										mayRead = false;
									}else{
										ckpHash = null;
										ckpState = null;
									}
								}
							} else {
								mayRead = false;
								ckp = null;
							}
						} else {
							mayRead = false;
						}
					} else {
						mayRead = false;
					}
				} catch (Exception e) {
					e.printStackTrace();
					ckp = null;
					mayRead = false;
				}
			}
			if (ckp.readInt() == 0) {
				ckpLastConsensusId = ckp.readInt();
				LOGGER.debug("LAST CKP read from file: {}", ckpLastConsensusId);
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error("State recover was aborted due to an unexpected exception");
		}

		return ckpState;
	}

	public void transferLog(SocketChannel sChannel, int index, String logPath) {
		RandomAccessFile log = null;

		LOGGER.debug("GETTING STATE FROM LOG {}", logPath);
		if ((log = openLogFile(logPath)) != null) {
			transferLog(log, sChannel, index);
		}
	}

	private void transferLog(RandomAccessFile logFile, SocketChannel sChannel, int index) {
		try {
			long totalBytes = logFile.length();
			LOGGER.debug("---Called transferLog. total bytes {}, sChannel is null ? {}", totalBytes, (sChannel == null));
			FileChannel fileChannel = logFile.getChannel();
			long bytesTransfered = 0;
			while(bytesTransfered < totalBytes) {
				long bufferSize = 65536;
				if(totalBytes  - bytesTransfered < bufferSize) {
					bufferSize = (int)(totalBytes - bytesTransfered);
					if(bufferSize <= 0)
						bufferSize = (int)totalBytes;
				}
				long bytesSent = fileChannel.transferTo(bytesTransfered, bufferSize, sChannel);
				if(bytesSent > 0) {
					bytesTransfered += bytesSent;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error("State recover was aborted due to an unexpected exception");
		}
	}

	public void transferCkpState(SocketChannel sChannel, String ckpPath) {
		RandomAccessFile ckp = null;

		LOGGER.debug("GETTING CHECKPOINT FROM {}", ckpPath);
		if ((ckp = openLogFile(ckpPath)) != null) {

			transferCkpState(ckp, sChannel);

			try {
				ckp.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void transferCkpState(RandomAccessFile ckp, SocketChannel sChannel) {
		try {
			long milliInit = System.currentTimeMillis();
			LOGGER.debug("--- Sending checkpoint. ckp length {}, sChannel is null? {}", ckp.length(), (sChannel == null));
			FileChannel fileChannel = ckp.getChannel();
			long totalBytes = ckp.length();
			long bytesTransfered = 0;
			while(bytesTransfered < totalBytes) {
				long bufferSize = 65536;
				if(totalBytes  - bytesTransfered < bufferSize) {
					bufferSize = (int)(totalBytes - bytesTransfered);
					if(bufferSize <= 0)
						bufferSize = (int)totalBytes;
				}
				long bytesRead = fileChannel.transferTo(bytesTransfered, bufferSize, sChannel);
				if(bytesRead > 0) {
					bytesTransfered += bytesRead;
				}
			}
			LOGGER.debug("---Took {} milliseconds to transfer the checkpoint", (System.currentTimeMillis() - milliInit));
			fileChannel.close();
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error("State recover was aborted due to an unexpected exception");
		}
	}

	public byte[] getCkpStateHash() {
		return ckpHash;
	}

	public int getCkpLastConsensusId() {
		return ckpLastConsensusId;
	}

	public int getLogLastConsensusId() {
		return logLastConsensusId;
	}

	private RandomAccessFile openLogFile(String file) {
		try {
			return new RandomAccessFile(file, "r");
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private CommandsInfo[] recoverLogState(RandomAccessFile log, int endOffset) {
		try {
			long logLength = log.length();
			ArrayList<CommandsInfo> state = new ArrayList<CommandsInfo>();
			int recoveredBatches = 0;
			boolean mayRead = true;
			LOGGER.info("filepointer: {}, loglength {}, endoffset {}", log.getFilePointer(), logLength, endOffset);
			while (mayRead) {
				try {
					if (log.getFilePointer() < logLength) {
						int size = log.readInt();
						if (size > 0) {
							byte[] bytes = new byte[size];
							int read = log.read(bytes);
							if (read == size) {
								ByteArrayInputStream bis = new ByteArrayInputStream(
										bytes);
								ObjectInputStream ois = new ObjectInputStream(
										bis);
								state.add((CommandsInfo) ois.readObject());
								if (++recoveredBatches == endOffset) {
									LOGGER.info("read all {} log messages", endOffset);
									return state.toArray(new CommandsInfo[state.size()]);
								}
							} else {
								mayRead = false;
								LOGGER.info("STATE CLEAR");
								state.clear();
							}
						} else {
							logLastConsensusId = log.readInt();
							LOGGER.info("ELSE 1. Recovered batches: {}", recoveredBatches);
							LOGGER.info("logLastConsensusId: {}", logLastConsensusId);
							return state.toArray(new CommandsInfo[state.size()]);
						}
					} else {
						LOGGER.info("ELSE 2 {}", recoveredBatches);
						mayRead = false;
					}
				} catch (Exception e) {
					LOGGER.info("Will clear state!, error = {}", e.getMessage());
					e.printStackTrace();
					state.clear();
					mayRead = false;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error("State recover was aborted due to an unexpected exception");
		}

		LOGGER.info("Will not get here!");
		return null;
	}

	/**
	 * Searches the log file and retrieves the portion selected.
	 * @param log The log file
	 * @param start The offset to start retrieving commands
	 * @param number The number of commands retrieved
	 * @return The commands for the period selected
	 */
	private CommandsInfo[] recoverLogState(RandomAccessFile log, long pointer, int startOffset, int number) {
		try {
			long logLength = log.length();
			ArrayList<CommandsInfo> state = new ArrayList<CommandsInfo>();
			int recoveredBatches = 0;
			boolean mayRead = true;

			log.seek(pointer);

			int index = 0;
			while(index < startOffset) {
				int size = log.readInt();
				byte[] bytes = new byte[size];
				log.read(bytes);
				index++;
			}

			while (mayRead) {

				try {
					if (log.getFilePointer() < logLength) {
						int size = log.readInt();

						if (size > 0) {
							byte[] bytes = new byte[size];
							int read = log.read(bytes);
							if (read == size) {
								ByteArrayInputStream bis = new ByteArrayInputStream(
										bytes);
								ObjectInputStream ois = new ObjectInputStream(
										bis);

								state.add((CommandsInfo) ois.readObject());

								if (++recoveredBatches == number) {
									return state.toArray(new CommandsInfo[state.size()]);
								}
							} else {
								LOGGER.debug("recoverLogState (pointer,offset,number) STATE CLEAR");
								mayRead = false;
								state.clear();
							}
						} else {
							LOGGER.debug("recoverLogState (pointer,offset,number) ELSE 1");
							mayRead = false;
						}
					} else {
						LOGGER.debug("recoverLogState (pointer,offset,number) ELSE 2 {}", recoveredBatches);
						mayRead = false;
					}
				} catch (Exception e) {
					e.printStackTrace();
					state.clear();
					mayRead = false;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error("State recover was aborted due to an unexpected exception");
		}

		return null;
	}

	public String getLatestFile(String extention) {
		File directory = new File(defaultDir);
		String latestFile = null;
		if (directory.isDirectory()) {
			File[] serverLogs = directory.listFiles(new FileListFilter(
					replicaId, extention));
			long timestamp = 0;
			for (File f : serverLogs) {
				String[] nameItems = f.getName().split("\\.");
				long filets = new Long(nameItems[1]).longValue();
				if(filets > timestamp) {
					timestamp = filets;
					latestFile = f.getAbsolutePath();
				}
			}
		}
		return latestFile;
	}

	private class FileListFilter implements FilenameFilter {

		private int id;
		private String extention;

		public FileListFilter(int id, String extention) {
			this.id = id;
			this.extention = extention;
		}

		public boolean accept(File directory, String filename) {
			boolean fileOK = false;

			if (id >= 0) {
				if (filename.startsWith(id + ".")
						&& filename.endsWith(extention)) {
					fileOK = true;
				}
			}

			return fileOK;
		}
	}

}
