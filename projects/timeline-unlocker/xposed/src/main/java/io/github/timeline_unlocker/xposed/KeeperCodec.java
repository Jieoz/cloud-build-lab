package io.github.timeline_unlocker.xposed;

/**
 * Pure: encoding between Maps-internal relative paths and flat Download row names, plus the
 * snapshot manifest line format. Unit-tested.
 *
 * <p>MediaStore display names cannot contain '/', so {@code files/phenotype/shared/x.pb} becomes
 * {@code files__phenotype__shared__x.pb}. The manifest is one line per file:
 * {@code encoded|relative|size} — kept next to the payload so a partial snapshot is detectable.</p>
 */
final class KeeperCodec {

    static final String MANIFEST_NAME = "keeper-manifest.txt";

    private KeeperCodec() {}

    static String encodeName(String relativePath) {
        return relativePath.replace('/', '_').replace('\\', '_');
    }

    // No decodeName: encoded names are ambiguous when a real filename contains '_', so the
    // manifest's second column (the true relative path) is the only restore source.

    /** One manifest line; {@code size} is advisory (read back to verify the copy). */
    static String manifestLine(String relativePath, long size) {
        return encodeName(relativePath) + "|" + relativePath + "|" + size;
    }

    /** Parses one manifest line; null when malformed. */
    static long[] parseManifest(String line, StringBuilder relativeOut) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 3) return null;
        if (!parts[0].equals(encodeName(parts[1]))) return null;
        long size;
        try {
            size = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        relativeOut.setLength(0);
        relativeOut.append(parts[1]);
        return new long[]{size};
    }
}
