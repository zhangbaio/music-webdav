package com.example.musicwebdav.application.service;

import com.example.musicwebdav.domain.model.AudioMetadata;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetadataFallbackServiceTest {

    private MetadataFallbackService metadataFallbackService;

    @BeforeEach
    void setUp() {
        metadataFallbackService = new MetadataFallbackService();
    }

    @Test
    void shouldKeepExistingMetadataWhenFieldsPresent() {
        AudioMetadata metadata = new AudioMetadata();
        metadata.setTitle("  Shape of You ");
        metadata.setArtist(" Ed Sheeran ");
        metadata.setAlbum(" Divide ");
        metadata.setAlbumArtist("Ed Sheeran");
        metadata.setHasCover(true);

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "pop/shape-of-you.mp3");

        Assertions.assertEquals("Shape of You", result.getTitle());
        Assertions.assertEquals("Ed Sheeran", result.getArtist());
        Assertions.assertEquals("Divide", result.getAlbum());
        Assertions.assertEquals("Ed Sheeran", result.getAlbumArtist());
        Assertions.assertTrue(result.getHasCover());
    }

    @Test
    void shouldFallbackFromFileNamePatternWhenTagsMissing() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "粤语/陈奕迅 - 孤勇者.flac");

        Assertions.assertEquals("孤勇者", result.getTitle());
        Assertions.assertEquals("陈奕迅", result.getArtist());
        Assertions.assertEquals("Unknown Album", result.getAlbum());
        Assertions.assertEquals("陈奕迅", result.getAlbumArtist());
        Assertions.assertFalse(result.getHasCover());
    }

    @Test
    void shouldFallbackToBaseNameWhenNoPatternMatched() {
        AudioMetadata metadata = new AudioMetadata();
        metadata.setArtist("Aimer");

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "jpop/brave_shine.m4a");

        Assertions.assertEquals("brave_shine", result.getTitle());
        Assertions.assertEquals("Aimer", result.getArtist());
        Assertions.assertEquals("Unknown Album", result.getAlbum());
        Assertions.assertEquals("Aimer", result.getAlbumArtist());
    }

    @Test
    void shouldFallbackToUnknownTrackWhenPathMissing() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, null);

        Assertions.assertEquals("unknown-track", result.getTitle());
        Assertions.assertEquals("Unknown Artist", result.getArtist());
        Assertions.assertEquals("Unknown Album", result.getAlbum());
        Assertions.assertEquals("Unknown Artist", result.getAlbumArtist());
    }
}
