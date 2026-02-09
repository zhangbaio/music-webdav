package com.example.musicwebdav.application.service;

import com.example.musicwebdav.domain.model.AudioMetadata;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MetadataFallbackService {

    private static final String UNKNOWN_ARTIST = "Unknown Artist";
    private static final String UNKNOWN_ALBUM = "Unknown Album";
    private static final Pattern ARTIST_TITLE_PATTERN = Pattern.compile("^\\s*(.+?)\\s*-\\s*(.+?)\\s*$");

    public AudioMetadata applyFallback(AudioMetadata input, String relativePath) {
        AudioMetadata metadata = input == null ? new AudioMetadata() : input;
        String fileBaseName = extractFileBaseName(relativePath);
        String guessedArtist = null;
        String guessedTitle = null;

        Matcher matcher = ARTIST_TITLE_PATTERN.matcher(fileBaseName);
        if (matcher.matches()) {
            guessedArtist = safe(matcher.group(1));
            guessedTitle = safe(matcher.group(2));
        }

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
            metadata.setAlbum(UNKNOWN_ALBUM);
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

    private String extractFileBaseName(String relativePath) {
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

    private String safe(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
