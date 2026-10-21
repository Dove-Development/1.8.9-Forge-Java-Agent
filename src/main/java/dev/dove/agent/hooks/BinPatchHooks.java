package dev.dove.agent.hooks;

import dev.dove.agent.DoveAgent;
import dev.dove.agent.util.AgentLog;
import org.apache.commons.compress.harmony.unpack200.Pack200UnpackerAdapter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.Deflater;

/**
 * Forge ships its binary patches as {@code binpatches.pack.lzma}: Pack200 inside LZMA. Pack200 was
 * removed in Java 14, so the unpack step runs on Commons Compress instead, and its result is cached
 * on disk because unpacking is the slow part of every launch.
 */
public final class BinPatchHooks {

    public static final String OWNER = "dev/dove/agent/hooks/BinPatchHooks";

    private BinPatchHooks() {
    }

    /**
     * Replaces {@code Pack200.newUnpacker().unpack(in, out)} in Forge's ClassPatchManager. Like the
     * JDK's version it reads all of {@code in} and leaves {@code out} open.
     */
    public static void unpack(Object ignoredUnpacker, InputStream in, JarOutputStream out) throws IOException {
        long start = System.nanoTime();
        byte[] packed = in.readAllBytes();
        in.close();

        Path cacheFile = DoveAgent.options().cacheDir().resolve("binpatches-" + sha1(packed) + ".jar");
        byte[] jar = readCache(cacheFile);
        boolean cached = jar != null;
        if (!cached) {
            jar = unpackPack200(packed);
            writeCache(cacheFile, jar);
        }

        // Forge reads this straight back in memory; compressing it again would be wasted time.
        out.setLevel(Deflater.NO_COMPRESSION);
        int count = 0;
        try (JarInputStream entries = new JarInputStream(new ByteArrayInputStream(jar))) {
            for (JarEntry entry = entries.getNextJarEntry(); entry != null; entry = entries.getNextJarEntry()) {
                out.putNextEntry(new JarEntry(entry.getName()));
                entries.transferTo(out);
                out.closeEntry();
                count++;
            }
        }
        AgentLog.info("Loaded " + count + " Forge binary patches " + (cached ? "from cache" : "with Pack200")
                + " in " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    private static byte[] unpackPack200(byte[] packed) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(packed.length * 4);
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            // Constructed directly: Pack200.newUnpacker() finds it by reflection, which the jar's
            // minimizer can't see.
            new Pack200UnpackerAdapter().unpack(new ByteArrayInputStream(packed), jar);
        }
        return bytes.toByteArray();
    }

    private static byte[] readCache(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            AgentLog.warn("Ignoring unreadable binary patch cache " + file + ": " + e);
            return null;
        }
    }

    private static void writeCache(Path file, byte[] jar) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            Files.write(temp, jar);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Only costs time on the next launch.
            AgentLog.warn("Cannot cache binary patches in " + file + ": " + e);
        }
    }

    private static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
