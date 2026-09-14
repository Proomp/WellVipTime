package com.wellsetups.wellviptime.configuration;

import java.io.IOException;
import java.nio.file.*;

/** Publish the copied data folder only after every file has copied successfully. */
public final class DataMigration {

    private DataMigration() {}

    public static boolean copyLegacy(Path target) throws IOException {
        Path old = target.resolveSibling("VipManager");
        if (!Files.isDirectory(old)) {
            return false;
        }
        if (Files.exists(target)) {
            try (var contents = Files.list(target)) {
                if (contents.findAny().isPresent()) {
                    return false;
                }
            }
            // Only an empty destination is removed.
            Files.delete(target);
        }
        Path stage = Files.createTempDirectory(target.getParent(), "WellVipTime-migration-");
        try (var paths = Files.walk(old)) {
            for (Path source : paths.toList()) {
                if (Files.isSymbolicLink(source)) {
                    throw new IOException(
                            "Migration requires ordinary files; symbolic link: " + source);
                }
                Path destination = stage.resolve(old.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination);
                }
            }
        }
        try {
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(stage, target);
        }
        return true;
    }
}
