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
    /**
     * Manifest of the revoke-footprint snapshot (4.26): same flags dir, own rows. Line format
     * {@code rv_name|relative|size} — column 1 carries the {@code rv_} prefix, so it does NOT
     * satisfy {@link #parseManifest}'s encoded-name check; the revoke manifest is parsed
     * off-device only, restore never reads it.
     */
    static final String REVOKE_MANIFEST_NAME = "keeper-revoke-manifest.txt";

    private KeeperCodec() {}

    static String encodeName(String relativePath) {
        return relativePath.replace('/', '_').replace('\\', '_');
    }

    /**
     * Rows of the revoke capture are namespaced: {@code rv_} + encoded. They must never be
     * able to overwrite an authorization snapshot's payload row — the manifest is the only
     * trusted name map, but a name collision would silently replace evidence with current
     * state. Prefixed rows also let one Download export deliver both snapshots side by side.
     */
    static String revokeName(String encodedName) {
        return "rv_" + encodedName;
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
