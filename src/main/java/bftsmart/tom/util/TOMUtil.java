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
package bftsmart.tom.util;

import bftsmart.consensus.app.SHA256Utils;
import bftsmart.reconfiguration.ViewTopology;

import java.io.*;
import java.security.*;
import java.util.Arrays;

public class TOMUtil {

	// private static final int BENCHMARK_PERIOD = 10000;

	// some message types
	public static final int RR_REQUEST = 0;
	public static final int RR_REPLY = 1;
	public static final int RR_DELIVERED = 2;

//	// 与领导者切换有关的类型；
//	public static final int STOP = 3;
//	public static final int STOPDATA = 4;
//	public static final int SYNC = 5;

	public static final int SM_REQUEST = 6;
	public static final int SM_REPLY = 7;
	public static final int SM_ASK_INITIAL = 11;
	public static final int SM_REPLY_INITIAL = 12;

	public static final int SM_TRANSACTION_REPLAY_REQUEST_INFO = 13;
	public static final int SM_TRANSACTION_REPLAY_REPLY_INFO = 14;

//	public static final int TRIGGER_LC_LOCALLY = 8;
	public static final int TRIGGER_SM_LOCALLY = 9;

	private static int signatureSize = -1;

	public static int getSignatureSize(ViewTopology controller) {
		if (signatureSize > 0) {
			return signatureSize;
		}

		byte[] signature = signMessage(controller.getStaticConf().getRSAPrivateKey(), BytesUtils.getBytes("a"));

		if (signature != null) {
			signatureSize = signature.length;
		}

		return signatureSize;
	}

	// ******* EDUARDO BEGIN **************//
	public static byte[] getBytes(Object o) {
		ByteArrayOutputStream bOut = new ByteArrayOutputStream();
		ObjectOutputStream obOut = null;
		try {
			obOut = new ObjectOutputStream(bOut);
			obOut.writeObject(o);
			obOut.flush();
			bOut.flush();
			obOut.close();
			bOut.close();
		} catch (IOException ex) {
			ex.printStackTrace();
			return null;
		}

		return bOut.toByteArray();
	}

	public static Object getObject(byte[] b) {
		if (b == null)
			return null;

		ByteArrayInputStream bInp = new ByteArrayInputStream(b);
		try {
			ObjectInputStream obInp = new ObjectInputStream(bInp);
			Object ret = obInp.readObject();
			obInp.close();
			bInp.close();
			return ret;
		} catch (Exception ex) {
			return null;
		}
	}
	// ******* EDUARDO END **************//

	/**
	 * Sign a message.
	 *
	 * @param key     the private key to be used to generate the signature
	 * @param message the message to be signed
	 * @return the signature
	 */
	public static byte[] signMessage(PrivateKey key, byte[] message) {
		try {
			Signature signatureEngine = Signature.getInstance("SHA1withRSA");
			signatureEngine.initSign(key);
			signatureEngine.update(message);
			
			byte[] result = signatureEngine.sign();
			return result;
		} catch (InvalidKeyException | NoSuchAlgorithmException | SignatureException e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	/**
	 * Verify the signature of a message.
	 *
	 * @param key       the public key to be used to verify the signature
	 * @param message   the signed message
	 * @param signature the signature to be verified
	 * @return true if the signature is valid, false otherwise
	 */
	public static boolean verifySignature(PublicKey key, byte[] message, byte[] signature) {

		boolean result = false;

		try {
			Signature signatureEngine = Signature.getInstance("SHA1withRSA");

			signatureEngine.initVerify(key);

			result = verifySignature(signatureEngine, message, signature);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return result;
	}

	/**
	 * Verify the signature of a message.
	 *
	 * @param initializedSignatureEngine a signature engine already initialized for
	 *                                   verification
	 * @param message                    the signed message
	 * @param signature                  the signature to be verified
	 * @return true if the signature is valid, false otherwise
	 */
	public static boolean verifySignature(Signature initializedSignatureEngine, byte[] message, byte[] signature)
			throws SignatureException {

		initializedSignatureEngine.update(message);
		return initializedSignatureEngine.verify(signature);
	}

	public static String byteArrayToString(byte[] b) {
		String s = "";
		for (int i = 0; i < b.length; i++) {
			s = s + b[i];
		}

		return s;
	}

	public static boolean equalsHash(byte[] h1, byte[] h2) {
		return Arrays.equals(h2, h2);
	}

	public static final byte[] computeHash(byte[] data) throws NoSuchAlgorithmException {

		byte[] result = null;

		SHA256Utils md = new SHA256Utils();
		result = md.hash(data);

		return result;
	}

}
