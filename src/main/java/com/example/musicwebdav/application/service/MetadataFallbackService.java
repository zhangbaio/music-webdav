package com.example.musicwebdav.application.service;

import com.example.musicwebdav.domain.model.AudioMetadata;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MetadataFallbackService {

    private static final String UNKNOWN_ARTIST = "Unknown Artist";
    private static final String UNKNOWN_ALBUM = "Unknown Album";

    private static final Pattern DASH_PATTERN = Pattern.compile("^\\s*(.+?)\\s*-\\s*(.+?)\\s*$");

    private static final Set<String> GENERIC_DIR_NAMES = new HashSet<>(Arrays.asList(
            "music", "audio", "songs", "download", "downloads",
            "华语", "粤语", "日语", "韩语", "英语", "欧美", "日韩",
            "pop", "rock", "jazz", "classical", "hiphop", "hip-hop", "r&b",
            "歌曲", "下载", "我的音乐", "音乐", "全部", "其他", "未分类",
            "mp3", "flac", "lossless", "无损", "有损", "hi-res",
            "ost", "soundtrack", "合集", "精选", "热门"
    ));

    public AudioMetadata applyFallback(AudioMetadata input, String relativePath) {
        AudioMetadata metadata = input == null ? new AudioMetadata() : input;

        // Handle null/empty path early — no pattern matching possible
        if (relativePath == null || relativePath.trim().isEmpty()) {
            if (!StringUtils.hasText(metadata.getTitle())) {
                metadata.setTitle("unknown-track");
            }
            if (!StringUtils.hasText(metadata.getArtist())) {
                metadata.setArtist(UNKNOWN_ARTIST);
            }
            if (!StringUtils.hasText(metadata.getAlbum())) {
                metadata.setAlbum(UNKNOWN_ALBUM);
            }
            if (!StringUtils.hasText(metadata.getAlbumArtist())) {
                metadata.setAlbumArtist(metadata.getArtist());
            }
            if (metadata.getHasCover() == null) {
                metadata.setHasCover(false);
            }
            return metadata;
        }

        String fileBaseName = extractFileBaseName(relativePath);
        String parentDir = extractParentDirName(relativePath);
        String grandparentDir = extractGrandparentDirName(relativePath);

        String guessedArtist = null;
        String guessedTitle = null;
        String guessedAlbum = null;

        // Try filename pattern matching: "A - B" or "A-B"
        String[] parsed = parseFilenameSegments(fileBaseName, DASH_PATTERN);

        if (parsed != null) {
            String segA = parsed[0];
            String segB = parsed[1];
            // Disambiguate: which segment is artist, which is title
            String[] resolved = disambiguate(segA, segB, parentDir);
            guessedArtist = resolved[0];
            guessedTitle = resolved[1];
        }

        // Infer artist from directory if not resolved from filename
        if (!StringUtils.hasText(guessedArtist)) {
            guessedArtist = inferArtistFromDir(parentDir, grandparentDir);
        }

        // Infer album from directory
        guessedAlbum = inferAlbumFromDir(parentDir, grandparentDir, guessedArtist);

        // Apply fallbacks to metadata fields
        if (!StringUtils.hasText(metadata.getTitle())) {
            metadata.setTitle(StringUtils.hasText(guessedTitle) ? guessedTitle : fileBaseName);
        } else {
            metadata.setTitle(metadata.getTitle().trim());
        }

        if (!StringUtils.hasText(metadata.getArtist())) {
            metadata.setArtist(StringUtils.hasText(guessedArtist) ? guessedArtist : UNKNOWN_ARTIST);
        } else {
            metadata.setArtist(metadata.getArtist().trim());
        }

        if (!StringUtils.hasText(metadata.getAlbum())) {
            metadata.setAlbum(StringUtils.hasText(guessedAlbum) ? guessedAlbum : UNKNOWN_ALBUM);
        } else {
            metadata.setAlbum(metadata.getAlbum().trim());
        }

        if (!StringUtils.hasText(metadata.getAlbumArtist())) {
            metadata.setAlbumArtist(metadata.getArtist());
        } else {
            metadata.setAlbumArtist(metadata.getAlbumArtist().trim());
        }

        if (metadata.getHasCover() == null) {
            metadata.setHasCover(false);
        }
        return metadata;
    }

    private String[] parseFilenameSegments(String fileBaseName, Pattern pattern) {
        Matcher matcher = pattern.matcher(fileBaseName);
        if (matcher.matches()) {
            String a = safe(matcher.group(1));
            String b = safe(matcher.group(2));
            if (StringUtils.hasText(a) && StringUtils.hasText(b)) {
                return new String[]{a, b};
            }
        }
        return null;
    }

    /**
     * Disambiguate two segments from filename to determine artist and title.
     * Strategies:
     * 1. If one segment matches the parent directory name, it's the artist.
     * 2. Default: first=artist, second=title (standard "Artist - Title" convention).
     */
    private String[] disambiguate(String segA, String segB, String parentDir) {
        if (StringUtils.hasText(parentDir) && !isGenericDirName(parentDir)) {
            if (parentDir.equalsIgnoreCase(segB)) {
                return new String[]{segB, segA};
            }
            // segA matches parent or no match → default order
        }
        return new String[]{segA, segB};
    }

    private String inferArtistFromDir(String parentDir, String grandparentDir) {
        if (StringUtils.hasText(parentDir) && !isGenericDirName(parentDir)) {
            return parentDir;
        }
        if (StringUtils.hasText(grandparentDir) && !isGenericDirName(grandparentDir)) {
            return grandparentDir;
        }
        return null;
    }

    private String inferAlbumFromDir(String parentDir, String grandparentDir, String artist) {
        // Pattern: artist/album/song.mp3
        if (StringUtils.hasText(parentDir) && !isGenericDirName(parentDir)
                && !parentDir.equalsIgnoreCase(artist)) {
            // parentDir is likely album if it's not the artist
            return parentDir;
        }
        return null;
    }

    String extractFileBaseName(String relativePath) {
        String filename = relativePath == null ? "unknown-track" : relativePath;
        int slashIndex = filename.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex < filename.length() - 1) {
            filename = filename.substring(slashIndex + 1);
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0) {
            filename = filename.substring(0, dotIndex);
        }
        String safe = safe(filename);
        return StringUtils.hasText(safe) ? safe : "unknown-track";
    }

    String extractParentDirName(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash <= 0) {
            return null;
        }
        String dirPart = relativePath.substring(0, lastSlash);
        int prevSlash = dirPart.lastIndexOf('/');
        String dirName = prevSlash >= 0 ? dirPart.substring(prevSlash + 1) : dirPart;
        return safe(dirName);
    }

    String extractGrandparentDirName(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash <= 0) {
            return null;
        }
        String dirPart = relativePath.substring(0, lastSlash);
        int prevSlash = dirPart.lastIndexOf('/');
        if (prevSlash <= 0) {
            return null;
        }
        String grandparentPart = dirPart.substring(0, prevSlash);
        int gpSlash = grandparentPart.lastIndexOf('/');
        String gpName = gpSlash >= 0 ? grandparentPart.substring(gpSlash + 1) : grandparentPart;
        return safe(gpName);
    }

    boolean isGenericDirName(String dirName) {
        if (dirName == null || dirName.trim().isEmpty()) {
            return true;
        }
        return GENERIC_DIR_NAMES.contains(dirName.trim().toLowerCase(Locale.ROOT));
    }

    private String safe(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
