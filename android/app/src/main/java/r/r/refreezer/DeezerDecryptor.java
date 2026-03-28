package r.r.refreezer;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class DeezerDecryptor {
    private static final String TAG = "DeezerDecryptor";
    private static final int CHUNK_SIZE = 2048;
    private static final byte[] IV = {0, 1, 2, 3, 4, 5, 6, 7};
    private static final String SECRET = "g4el58wc0zvf9na1";

    private final Cipher cipher;

    /**
     * Constructor initializes the key and cipher for the given track ID.
     *
     * @param trackId Track ID used to generate decryption key
     * @throws Exception If there is an issue initializing the cipher
     */
    public DeezerDecryptor(String trackId) throws Exception {
        this.cipher = Cipher.getInstance("Blowfish/CBC/NoPadding");
        SecretKeySpec secretKey = new SecretKeySpec(getKey(trackId), "Blowfish");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(IV));
    }

    /**
     * Decrypts a file by reading it in chunks and decrypting every 3rd chunk of exactly 2048 bytes.
     *
     * @param inputFilename  The input file to decrypt
     * @param outputFilename The output file to write the decrypted data
     * @throws IOException If an I/O error occurs
     */
    public void decryptFile(String inputFilename, String outputFilename) throws IOException {
        decryptFile(new File(inputFilename), new File(outputFilename));
    }

    /**
     * Decrypts a file by reading it in chunks and decrypting every 3rd chunk of exactly 2048 bytes.
     *
     * @param inputFile  Encrypted input file
     * @param outputFile Decrypted output file
     * @throws IOException If an I/O error occurs
     */
    public void decryptFile(File inputFile, File outputFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(inputFile);
             FileOutputStream fos = new FileOutputStream(outputFile)) {

            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            int chunkCounter = 0;

            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] outputChunk;

                // Only every 3rd full chunk of exactly 2048 bytes should be decrypted
                if (isEncryptedChunk(chunkCounter, bytesRead)) {
                    byte[] fullChunk = Arrays.copyOf(buffer, CHUNK_SIZE);
                    outputChunk = decryptChunk(fullChunk);
                } else {
                    outputChunk = Arrays.copyOf(buffer, bytesRead);
                }

                fos.write(outputChunk, 0, bytesRead);
                chunkCounter++;
            }
        }
    }

    /**
     * Returns true when the Deezer encrypted stream rules say this chunk should be decrypted:
     * every 3rd chunk, and only when the chunk is exactly 2048 bytes.
     */
    public static boolean isEncryptedChunk(int chunkCounter, int bytesRead) {
        return bytesRead == CHUNK_SIZE && (chunkCounter % 3) == 0;
    }

    /**
     * Convenience helper for future temp-file workflows.
     * Example output:
     *   /path/file.ENC -> /path/file.ENC.DEC
     */
    public static String buildTempDecryptedPath(String inputFilename) {
        return inputFilename + ".DEC";
    }

    /**
     * Converts a byte array to a hexadecimal string.
     *
     * @param bytes Byte array to convert
     * @return Hexadecimal string representation of the byte array
     */
    public static String bytesToHex(byte[] bytes) {
        final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
        char[] hexChars = new char[bytes.length * 2];

        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }

        return new String(hexChars);
    }

    /**
     * Generates the Track decryption key based on the provided track ID and a secret.
     *
     * @param id Track ID used to generate decryption key
     * @return Decryption key for Track
     */
    static byte[] getKey(String id) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(id.getBytes(StandardCharsets.UTF_8));
            byte[] md5id = md5.digest();
            String idmd5 = bytesToHex(md5id).toLowerCase();

            byte[] keyBytes = new byte[16];
            for (int i = 0; i < 16; i++) {
                int s0 = idmd5.charAt(i);
                int s1 = idmd5.charAt(i + 16);
                int s2 = SECRET.charAt(i);
                keyBytes[i] = (byte) (s0 ^ s1 ^ s2);
            }

            return keyBytes;
        } catch (Exception e) {
            Log.e(TAG, "Error generating decryption key", e);
            return new byte[0];
        }
    }

    /**
     * Decrypts a 2048-byte chunk of data using the pre-initialized Blowfish cipher.
     *
     * @param data 2048-byte chunk of data to decrypt
     * @return Decrypted 2048-byte chunk
     */
    private byte[] decryptChunk(byte[] data) {
        try {
            return cipher.doFinal(data);
        } catch (Exception e) {
            Log.e(TAG, "Chunk decryption failed", e);
            // Safer fallback: return original chunk instead of empty array
            return Arrays.copyOf(data, data.length);
        }
    }

    /**
     * Decrypts a 2048-byte chunk of data using the Blowfish algorithm in CBC mode with no padding.
     *
     * @param key  Track key
     * @param data 2048-byte chunk of data to decrypt
     * @return Decrypted 2048-byte chunk
     */
    public static byte[] decryptChunk(byte[] key, byte[] data) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(key, "Blowfish");
            Cipher cipher = Cipher.getInstance("Blowfish/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(IV));
            return cipher.doFinal(data);
        } catch (Exception e) {
            Log.e(TAG, "Static chunk decryption failed", e);
            // Safer fallback: return original chunk instead of empty array
            return Arrays.copyOf(data, data.length);
        }
    }
}
