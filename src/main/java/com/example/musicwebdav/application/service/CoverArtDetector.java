package com.example.musicwebdav.application.service;

import com.example.musicwebdav.domain.model.WebDavFileObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CoverArtDetector {

    private static final Set<String> COVER_FILENAMES = new HashSet<>(Arrays.asList(
            "cover.jpg", "cover.jpeg", "cover.png",
            "album.jpg", "album.jpeg", "album.png",
            "folder.jpg", "folder.jpeg", "folder.png",
            "front.jpg", "front.jpeg", "front.png",
            "artwork.jpg", "artwork.jpeg", "artwork.png"
    ));

    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "bmp", "gif", "webp"
    ));

    /**
     * Detect cover art in a list of files within a directory.
     * Returns the relative path of the cover image, or null if none found.
     */
    public String detectCoverInDirectory(List<WebDavFileObject> filesInDir) {
        if (filesInDir == null || filesInDir.isEmpty()) {
            return null;
        }

        // First pass: exact name match (cover.jpg, album.jpg, etc.)
        for (WebDavFileObject file : filesInDir) {
            String filename = extractFilename(file.getRelativePath());
            if (filename != null && COVER_FILENAMES.contains(filename.toLowerCase(Locale.ROOT))) {
                return file.getRelativePath();
            }
        }

        // Second pass: any image file as fallback
        for (WebDavFileObject file : filesInDir) {
            String ext = extractExtension(file.getRelativePath());
            if (ext != null && IMAGE_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
                return file.getRelativePath();
            }
        }

        return null;
    }

    private String extractFilename(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        int slashIdx = relativePath.lastIndexOf('/');
        return slashIdx >= 0 ? relativePath.substring(slashIdx + 1) : relativePath;
    }

    private String extractExtension(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        int dotIdx = relativePath.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx >= relativePath.length() - 1) {
            return null;
        }
        return relativePath.substring(dotIdx + 1);
    }
}
