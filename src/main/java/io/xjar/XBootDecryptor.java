package io.xjar;

import io.xjar.key.XKey;
import org.apache.commons.compress.archivers.jar.JarArchiveEntry;
import org.apache.commons.compress.archivers.jar.JarArchiveInputStream;
import org.apache.commons.compress.archivers.jar.JarArchiveOutputStream;

import java.io.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Deflater;

/**
 * ZIP压缩包解密器
 *
 * @author Payne 646742615@qq.com
 * 2018/11/22 15:27
 */
public class XBootDecryptor extends XEntryDecryptor<JarArchiveEntry> implements XDecryptor, XConstants {
    private final int level;

    public XBootDecryptor(XDecryptor xDecryptor) {
        this(xDecryptor, (Collection<XEntryFilter<JarArchiveEntry>>) null);
    }

    public XBootDecryptor(XDecryptor xEncryptor, XEntryFilter<JarArchiveEntry>... filters) {
        this(xEncryptor, Arrays.asList(filters));
    }

    public XBootDecryptor(XDecryptor xDecryptor, Collection<XEntryFilter<JarArchiveEntry>> filters) {
        this(xDecryptor, Deflater.DEFLATED, filters);
    }

    public XBootDecryptor(XDecryptor xDecryptor, int level) {
        this(xDecryptor, level, (Collection<XEntryFilter<JarArchiveEntry>>) null);
    }

    public XBootDecryptor(XDecryptor xEncryptor, int level, XEntryFilter<JarArchiveEntry>... filters) {
        this(xEncryptor, level, Arrays.asList(filters));
    }

    public XBootDecryptor(XDecryptor xDecryptor, int level, Collection<XEntryFilter<JarArchiveEntry>> filters) {
        super(xDecryptor, filters);
        this.level = level;
    }

    @Override
    public void decrypt(XKey key, File src, File dest) throws IOException {
        try (
                FileInputStream fis = new FileInputStream(src);
                FileOutputStream fos = new FileOutputStream(dest)
        ) {
            decrypt(key, fis, fos);
        }
    }

    @Override
    public void decrypt(XKey key, InputStream in, OutputStream out) throws IOException {
        JarArchiveInputStream zis = null;
        JarArchiveOutputStream zos = null;
        Set<String> indexes = new LinkedHashSet<>();
        try {
            zis = new JarArchiveInputStream(in);
            zos = new JarArchiveOutputStream(out);
            zos.setLevel(level);
            NoCloseInputStream nis = new NoCloseInputStream(zis);
            NoCloseOutputStream nos = new NoCloseOutputStream(zos);
            XJarDecryptor xJarDecryptor = new XJarDecryptor(xDecryptor, level, xAlwaysFilter);
            JarArchiveEntry entry;
            Manifest manifest = null;
            while ((entry = zis.getNextJarEntry()) != null) {
                if (entry.getName().startsWith(XJAR_INF_DIR)
                        || entry.getName().startsWith(XJAR_SRC_DIR)
                        || entry.getName().endsWith(XJAR_INF_DIR + XENC_IDX_FILE)
                        || entry.getName().endsWith(XJAR_INF_DIR + XDEC_IDX_FILE)
                ) {
                    continue;
                }
                if (entry.isDirectory()) {
                    JarArchiveEntry jarArchiveEntry = new JarArchiveEntry(entry.getName());
                    jarArchiveEntry.setTime(entry.getTime());
                    zos.putArchiveEntry(jarArchiveEntry);
                } else if (entry.getName().endsWith(".jar")) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    CheckedOutputStream cos = new CheckedOutputStream(bos, new CRC32());
                    boolean filtered = filter(entry);
                    XDecryptor decryptor = filtered ? xJarDecryptor : xNopDecryptor;
                    decryptor.decrypt(key, nis, cos);
                    JarArchiveEntry jar = new JarArchiveEntry(entry.getName());
                    jar.setMethod(JarArchiveEntry.STORED);
                    jar.setSize(bos.size());
                    jar.setTime(entry.getTime());
                    jar.setCrc(cos.getChecksum().getValue());
                    zos.putArchiveEntry(jar);
                    ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
                    XKit.transfer(bis, nos);
                } else if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    boolean filtered = filter(entry);
                    if (filtered) indexes.add(entry.getName());
                    XDecryptor decryptor = filtered ? this : xNopDecryptor;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    try (InputStream dis = decryptor.decrypt(key, nis)) {
                        XKit.transfer(dis, bos);
                    }
                    ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
                    manifest = new Manifest(bis);
                    Attributes attributes = manifest.getMainAttributes();
                    String mainClass = attributes.getValue("Origin-Main-Class");
                    if (mainClass != null) {
                        attributes.putValue("Main-Class", mainClass);
                        attributes.remove(new Attributes.Name("Origin-Main-Class"));
                    }
                    JarArchiveEntry jarArchiveEntry = new JarArchiveEntry(entry.getName());
                    jarArchiveEntry.setTime(entry.getTime());
                    zos.putArchiveEntry(jarArchiveEntry);
                    manifest.write(nos);
                } else {
                    JarArchiveEntry jarArchiveEntry = new JarArchiveEntry(entry.getName());
                    jarArchiveEntry.setTime(entry.getTime());
                    zos.putArchiveEntry(jarArchiveEntry);
                    boolean filtered = filter(entry);
                    if (filtered) indexes.add(entry.getName());
                    XDecryptor decryptor = filtered ? this : xNopDecryptor;
                    try (OutputStream eos = decryptor.decrypt(key, nos)) {
                        XKit.transfer(nis, eos);
                    }
                }
                zos.closeArchiveEntry();
            }

            if (!indexes.isEmpty()) {
                String classpath = manifest != null && manifest.getMainAttributes() != null ? manifest.getMainAttributes().getValue("Spring-Boot-Classes") : null;
                JarArchiveEntry XJAR_INF = new JarArchiveEntry((classpath != null ? classpath : "") + XJAR_INF_DIR);
                XJAR_INF.setTime(System.currentTimeMillis());
                zos.putArchiveEntry(XJAR_INF);
                zos.closeArchiveEntry();

                JarArchiveEntry XDEC_IDX = new JarArchiveEntry((classpath != null ? classpath : "") + XJAR_INF_DIR + XDEC_IDX_FILE);
                XDEC_IDX.setTime(System.currentTimeMillis());
                zos.putArchiveEntry(XDEC_IDX);
                int idx = classpath != null ? classpath.length() : 0;
                for (String index : indexes) {
                    nos.write(index.substring(idx).getBytes());
                    nos.write(CRLF.getBytes());
                }
                zos.closeArchiveEntry();
            }

            zos.finish();
        } finally {
            XKit.close(zis);
            XKit.close(zos);
        }
    }

}