package com.huajian.fieldrecorder.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Hashing {
    private Hashing() { }
    public static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return sha256(in); }
    }
    public static String sha256(InputStream in) throws IOException {
        final MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        StringBuilder s = new StringBuilder();
        for (byte b : md.digest()) s.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return s.toString();
    }
}
