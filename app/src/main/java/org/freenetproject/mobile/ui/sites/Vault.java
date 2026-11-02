package org.freenetproject.mobile.ui.sites;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts files at rest with AES-256-GCM, in chunks so that large files fit in memory.
 *
 * The data key is itself encrypted by a key the Android Keystore keeps: if that key is lost,
 * for example after a device restore, the files cannot be read any more.
 */
final class Vault {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "freenet-sites";
    private static final String GCM = "AES/GCM/NoPadding";
    private static final byte[] MAGIC = {'F', 'N', 'E', '1'};
    private static final int CHUNK = 1024 * 1024;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey dataKey;
    private final SecureRandom random = new SecureRandom();

    Vault(Context context) throws IOException {
        try {
            dataKey = loadDataKey(new File(context.getFilesDir(), "vault.key"));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Encryption key not available: " + e.getMessage(), e);
        }
    }

    private SecretKey loadDataKey(File file) throws GeneralSecurityException, IOException {
        SecretKey master = masterKey();
        Cipher cipher = Cipher.getInstance(GCM);
        // An empty file can only be a key that never reached the disk: nothing was encrypted with it.
        if (file.isFile() && file.length() > 0) {
            byte[] stored = Files.readAllBytes(file.toPath());
            cipher.init(Cipher.DECRYPT_MODE, master,
                    new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            byte[] raw = cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
            return new SecretKeySpec(raw, "AES");
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        cipher.init(Cipher.ENCRYPT_MODE, master); // the Keystore picks the IV
        byte[] body = cipher.doFinal(raw);
        ByteBuffer stored = ByteBuffer.allocate(IV_BYTES + body.length);
        stored.put(cipher.getIV()).put(body);
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(stored.array());
            out.getFD().sync();
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return new SecretKeySpec(raw, "AES");
    }

    private static SecretKey masterKey() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return (SecretKey) store.getKey(ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    /** Chunk number and last-chunk mark are authenticated: nothing can be reordered or cut. */
    private static byte[] chunkData(long index, boolean last) {
        return ByteBuffer.allocate(9).putLong(index).put((byte) (last ? 1 : 0)).array();
    }

    private static int fill(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        int read;
        while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
            total += read;
        }
        return total;
    }

    void encrypt(InputStream in, OutputStream rawOut) throws IOException {
        try {
            DataOutputStream out = new DataOutputStream(rawOut);
            out.write(MAGIC);
            byte[] current = new byte[CHUNK];
            byte[] next = new byte[CHUNK];
            int size = fill(in, current);
            long index = 0;
            while (true) {
                int nextSize = fill(in, next);
                boolean last = nextSize == 0;
                byte[] iv = new byte[IV_BYTES];
                random.nextBytes(iv);
                Cipher cipher = Cipher.getInstance(GCM);
                cipher.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, iv));
                cipher.updateAAD(chunkData(index, last));
                byte[] body = cipher.doFinal(current, 0, size);
                out.writeInt(body.length);
                out.write(iv);
                out.write(body);
                if (last) {
                    out.flush();
                    return;
                }
                byte[] swap = current;
                current = next;
                next = swap;
                size = nextSize;
                index++;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption failed", e);
        }
    }

    /** Files saved before encryption existed are passed through as they are. */
    void decrypt(InputStream source, OutputStream out) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(source);
        buffered.mark(MAGIC.length);
        byte[] head = new byte[MAGIC.length];
        int got = fill(buffered, head);
        if (got < MAGIC.length || !Arrays.equals(head, MAGIC)) {
            buffered.reset();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = buffered.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return;
        }
        try {
            DataInputStream in = new DataInputStream(buffered);
            int length = in.readInt();
            long index = 0;
            while (true) {
                byte[] iv = new byte[IV_BYTES];
                in.readFully(iv);
                if (length < 0 || length > CHUNK + TAG_BITS / 8) {
                    throw new IOException("Damaged file");
                }
                byte[] body = new byte[length];
                in.readFully(body);
                byte[] four = new byte[4];
                boolean last = fill(in, four) == 0;
                Cipher cipher = Cipher.getInstance(GCM);
                cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, iv));
                cipher.updateAAD(chunkData(index, last));
                out.write(cipher.doFinal(body));
                if (last) {
                    return;
                }
                length = ByteBuffer.wrap(four).getInt();
                index++;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Damaged file or wrong key", e);
        }
    }

    byte[] readAll(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            decrypt(in, out);
        }
        return out.toByteArray();
    }

    /** Writes through a temporary file, so a crash cannot leave half a page. */
    void writeAll(File file, byte[] content) throws IOException {
        File temporary = new File(file.getPath() + ".tmp");
        try (InputStream in = new ByteArrayInputStream(content);
             FileOutputStream out = new FileOutputStream(temporary)) {
            encrypt(in, out);
            out.getFD().sync();
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Replaces an encrypted file with its plain content. */
    void writePlain(File file) throws IOException {
        File temporary = new File(file.getPath() + ".tmp");
        Files.write(temporary.toPath(), readAll(file));
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    void decryptTree(File from, File to) throws IOException {
        Files.createDirectories(to.toPath());
        File[] children = from.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                decryptTree(child, target);
            } else {
                try (InputStream in = new FileInputStream(child);
                     OutputStream out = new FileOutputStream(target)) {
                    decrypt(in, out);
                }
            }
        }
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        if (!file.delete() && file.exists()) {
            Log.w("Freenet", "Cannot delete " + file);
        }
    }
}
