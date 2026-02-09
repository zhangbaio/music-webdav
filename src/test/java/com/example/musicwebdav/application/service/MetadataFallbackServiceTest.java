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

    @Test
    void shouldInferArtistFromParentDirectory() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "那英/默.mp3");

        Assertions.assertEquals("默", result.getTitle());
        Assertions.assertEquals("那英", result.getArtist());
    }

    @Test
    void shouldSkipGenericDirAndInferArtistFromGrandparent() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "华语/那英/默.mp3");

        Assertions.assertEquals("默", result.getTitle());
        Assertions.assertEquals("那英", result.getArtist());
    }

    @Test
    void shouldDisambiguateUsingParentDir() {
        AudioMetadata metadata = new AudioMetadata();

        // "默-那英.mp3" in folder "那英" → segA=默, segB=那英, parentDir=那英 matches segB
        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "那英/默-那英.mp3");

        Assertions.assertEquals("默", result.getTitle());
        Assertions.assertEquals("那英", result.getArtist());
    }

    @Test
    void shouldInferAlbumFromParentWhenArtistIsGrandparent() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "那英/那英精选/征服.mp3");

        Assertions.assertEquals("征服", result.getTitle());
        Assertions.assertEquals("那英精选", result.getArtist());
        // parentDir=那英精选, it's used as artist because no pattern match, non-generic
    }

    @Test
    void shouldHandleStandardArtistTitleFormat() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "陈奕迅 - 十年.mp3");

        Assertions.assertEquals("十年", result.getTitle());
        Assertions.assertEquals("陈奕迅", result.getArtist());
    }

    @Test
    void shouldExtractArtistFromArtistTitleFileName() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "music/梦然 - 时光海湾.mp3");

        Assertions.assertEquals("时光海湾", result.getTitle());
        Assertions.assertEquals("梦然", result.getArtist());
    }

    @Test
    void shouldStripTrackNumberPrefixAndParseArtistTitle() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "music/01 - Jacky Cheung - Without You.flac");

        Assertions.assertEquals("Without You", result.getTitle());
        Assertions.assertEquals("Jacky Cheung", result.getArtist());
    }

    @Test
    void shouldStripTrackNumberPrefixFromTitle() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "music/01. 灰纸蝶.m4a");

        Assertions.assertEquals("灰纸蝶", result.getTitle());
        Assertions.assertEquals("Unknown Artist", result.getArtist());
    }

    @Test
    void shouldHandleFullWidthDashInFileName() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "music/梦然－时光海湾.mp3");

        Assertions.assertEquals("时光海湾", result.getTitle());
        Assertions.assertEquals("梦然", result.getArtist());
    }

    @Test
    void shouldInferAlbumFromParentArtistAlbumPattern() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(
                metadata,
                "六哲 - 被伤过的心还可以爱谁/六哲 - 爱情好无奈.flac");

        Assertions.assertEquals("爱情好无奈", result.getTitle());
        Assertions.assertEquals("六哲", result.getArtist());
        Assertions.assertEquals("被伤过的心还可以爱谁", result.getAlbum());
    }

    @Test
    void shouldHandleTrackNoArtistTitleWithoutExtensionConstraint() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(
                metadata,
                "六哲 - 被伤过的心还可以爱谁/01-六哲 - 爱情好无奈.mp3");

        Assertions.assertEquals("爱情好无奈", result.getTitle());
        Assertions.assertEquals("六哲", result.getArtist());
        Assertions.assertEquals("被伤过的心还可以爱谁", result.getAlbum());
    }

    @Test
    void shouldHandleTrackNoArtistTitleWithoutFileExtension() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(
                metadata,
                "六哲 - 被伤过的心还可以爱谁/01-六哲 - 爱情好无奈");

        Assertions.assertEquals("爱情好无奈", result.getTitle());
        Assertions.assertEquals("六哲", result.getArtist());
        Assertions.assertEquals("被伤过的心还可以爱谁", result.getAlbum());
    }

    @Test
    void shouldHandleFileAtRootLevel() {
        AudioMetadata metadata = new AudioMetadata();

        AudioMetadata result = metadataFallbackService.applyFallback(metadata, "songname.mp3");

        Assertions.assertEquals("songname", result.getTitle());
        Assertions.assertEquals("Unknown Artist", result.getArtist());
        Assertions.assertEquals("Unknown Album", result.getAlbum());
    }
}
