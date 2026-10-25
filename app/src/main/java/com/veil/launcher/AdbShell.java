package com.veil.launcher;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * A tiny adb client for {@code 127.0.0.1:5555}, inspired by Tigris and the dadb library: the box
 * talks to its own adbd, so the launcher can run a few system commands — reboot, power off, read
 * props — without a computer and without root. The RSA keypair is generated once and kept in the
 * app's files dir; the very first connection makes the box show the standard "Allow USB
 * debugging?" dialog.
 *
 * <p>Portions of the ADB protocol framing here are derived from
 * <a href="https://github.com/moz1u/dadb">dadb</a>, licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). This file, like the rest of the project, is
 * distributed under Apache-2.0; the ADB wire protocol itself is public knowledge from AOSP.
 */
final class AdbShell {

    private static final String TAG = "TvHome.Adb";
    private static final int A_CNXN = 0x4E584E43, A_AUTH = 0x48545541,
            A_OPEN = 0x4E45504F, A_OKAY = 0x59414B4F, A_WRTE = 0x45545257, A_CLSE = 0x45534C43;
    private static final int A_VERSION = 0x01000001, MAX_PAYLOAD = 4 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    interface Result {
        /** Called on the calling thread's executor: output is the shell's stdout (may be empty). */
        void done(boolean ok, String output);
    }

    private AdbShell() {
    }

    /** Runs one shell command; may take seconds (handshake + authorisation wait). */
    static void run(Context ctx, String command, Result result) {
        try {
            String out = shell(ctx, command);
            result.done(true, out);
        } catch (WaitingForAuthException wait) {
            result.done(false, "AUTH_WAIT");
        } catch (Exception e) {
            Log.w(TAG, "shell failed: " + e);
            result.done(false, e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    private static final class WaitingForAuthException extends Exception {
    }

    private static File keyFile(Context ctx) {
        return new File(ctx.getFilesDir(), "adb_key");
    }

    private static synchronized KeyPair loadOrCreateKeys(Context ctx) throws Exception {
        File f = keyFile(ctx);
        if (f.isFile()) {
            byte[] pkcs8 = readAll(f);
            PrivateKey priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            File pub = new File(ctx.getFilesDir(), "adb_key.pub");
            PublicKey pubk = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(readAll(pub)));
            return new KeyPair(pubk, priv);
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair pair = gen.generateKeyPair();
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(pair.getPrivate().getEncoded());
        }
        try (FileOutputStream fo = new FileOutputStream(new File(ctx.getFilesDir(), "adb_key.pub"))) {
            fo.write(pair.getPublic().getEncoded());
        }
        return pair;
    }

    private static byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    /** Signs an adbd token with our private key: RSA-SHA1, no padding hashing — raw RSA over SHA1(token). */
    private static byte[] signToken(PrivateKey priv, byte[] token) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(token);
        Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, priv);
        return cipher.doFinal(digest);
    }

    /** The adbd public-key blob: base64(X509 DER) + user@host + NUL, line-wrapped at 64 chars. */
    private static byte[] publicKeyBlob(PublicKey pub) throws Exception {
        StringBuilder b64 = new StringBuilder(Base64.encodeToString(pub.getEncoded(), Base64.NO_WRAP));
        int i = 64;
        while (i < b64.length()) {   // adb expects 64-char lines
            b64.insert(i, "\n");
            i += 65;
        }
        b64.append(" veil@tv\u0000");
        return b64.toString().getBytes("UTF-8");
    }

    private static String shell(Context ctx, String command) throws Exception {
        KeyPair keys = loadOrCreateKeys(ctx);
        try (Socket socket = new Socket("127.0.0.1", 5555)) {
            socket.setSoTimeout(15_000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            send(out, A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\0".getBytes("UTF-8"));
            Frame f = read(in);

            // Authorise: every TOKEN gets a SIGNATURE; the second token means adbd doesn't know
            // us yet, so the public key goes out once — that is what pops the "Allow?" dialog.
            int tokens = 0;
            boolean pubkeySent = false;
            while (f.cmd == A_AUTH) {
                if (f.arg0 == AUTH_TOKEN) {
                    tokens++;
                    send(out, A_AUTH, AUTH_SIGNATURE, 0, signToken(keys.getPrivate(), f.data));
                    if (tokens >= 2 && !pubkeySent) {
                        send(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys.getPublic()));
                        pubkeySent = true;
                    }
                }
                f = read(in);
                if (f.cmd == A_AUTH && f.arg0 == AUTH_TOKEN && pubkeySent) {
                    // Waiting on the "Allow USB debugging?" dialog; keep signing tokens as they come.
                    while (f.cmd == A_AUTH) {
                        if (f.arg0 == AUTH_TOKEN) {
                            send(out, A_AUTH, AUTH_SIGNATURE, 0, signToken(keys.getPrivate(), f.data));
                        }
                        f = read(in);
                    }
                }
            }
            if (f.cmd != A_CNXN) throw new Exception("handshake: cmd=0x" + Integer.toHexString(f.cmd));

            int localId = 1;
            send(out, A_OPEN, localId, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
            f = read(in);
            if (f.cmd == A_CLSE) throw new Exception("stream refused");
            if (f.cmd != A_OKAY) throw new Exception("open: cmd=0x" + Integer.toHexString(f.cmd));
            int remoteId = f.arg0;

            ByteArrayOutputStream all = new ByteArrayOutputStream();
            while (true) {
                f = read(in);
                if (f.cmd == A_WRTE && f.arg1 == localId) {
                    all.write(f.data);
                    send(out, A_OKAY, localId, remoteId, null);
                } else if (f.cmd == A_CLSE) {
                    break;
                } else if (f.cmd == A_AUTH) {
                    throw new WaitingForAuthException();
                }
            }
            return all.toString("UTF-8");
        }
    }

    private static void send(DataOutputStream out, int cmd, int arg0, int arg1, byte[] data) throws Exception {
        if (data == null) data = new byte[0];
        out.writeInt(Integer.reverseBytes(cmd));
        out.writeInt(Integer.reverseBytes(arg0));
        out.writeInt(Integer.reverseBytes(arg1));
        out.writeInt(Integer.reverseBytes(data.length));
        out.writeInt(Integer.reverseBytes(data.length == 0 ? 0 : checksum(data)));
        out.writeInt(Integer.reverseBytes(cmd ^ 0xFFFFFFFF));
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private static Frame read(DataInputStream in) throws Exception {
        int cmd = Integer.reverseBytes(in.readInt());
        int arg0 = Integer.reverseBytes(in.readInt());
        int arg1 = Integer.reverseBytes(in.readInt());
        int len = Integer.reverseBytes(in.readInt());
        int csum = Integer.reverseBytes(in.readInt());
        int magic = Integer.reverseBytes(in.readInt());
        if (cmd != (magic ^ 0xFFFFFFFF)) throw new Exception("bad frame magic");
        byte[] data = new byte[len];
        if (len > 0) in.readFully(data);
        if (len > 0 && checksum(data) != csum) throw new Exception("bad frame checksum");
        return new Frame(cmd, arg0, arg1, data);
    }

    private static int checksum(byte[] data) {
        long sum = 0;
        for (byte b : data) sum += (b & 0xFF);
        return (int) (sum & 0xFFFFFFFFL);
    }

    private static final class Frame {
        final int cmd, arg0, arg1;
        final byte[] data;

        Frame(int cmd, int arg0, int arg1, byte[] data) {
            this.cmd = cmd;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }
}
