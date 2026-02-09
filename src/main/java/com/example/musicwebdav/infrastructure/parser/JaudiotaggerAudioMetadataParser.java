package com.example.musicwebdav.infrastructure.parser;

import com.example.musicwebdav.domain.model.AudioMetadata;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.springframework.stereotype.Component;

@Component
public class JaudiotaggerAudioMetadataParser implements AudioMetadataParser {

    private static final Pattern FIRST_INTEGER_PATTERN = Pattern.compile("(\\d+)");

    @Override
    public AudioMetadata parse(File audioFile) throws Exception {
        AudioFile parsed = AudioFileIO.read(audioFile);
        Tag tag = parsed.getTag();
        AudioHeader header = parsed.getAudioHeader();

        AudioMetadata metadata = new AudioMetadata();
        metadata.setTitle(safeTagValue(tag, FieldKey.TITLE));
        metadata.setArtist(safeTagValue(tag, FieldKey.ARTIST));
        metadata.setAlbum(safeTagValue(tag, FieldKey.ALBUM));
        metadata.setAlbumArtist(safeTagValue(tag, FieldKey.ALBUM_ARTIST));
        metadata.setTrackNo(parseInteger(safeTagValue(tag, FieldKey.TRACK)));
        metadata.setDiscNo(parseInteger(safeTagValue(tag, FieldKey.DISC_NO)));
        metadata.setYear(parseInteger(safeTagValue(tag, FieldKey.YEAR)));
        metadata.setGenre(safeTagValue(tag, FieldKey.GENRE));

        if (header != null) {
            metadata.setDurationSec(header.getTrackLength());
            metadata.setBitrate(parseInteger(header.getBitRate()));
            metadata.setSampleRate(parseInteger(header.getSampleRate()));
            metadata.setChannels(parseInteger(header.getChannels()));
        }

        boolean hasCover = tag != null && tag.getFirstArtwork() != null;
        metadata.setHasCover(hasCover);
        return metadata;
    }

    private String safeTagValue(Tag tag, FieldKey fieldKey) {
        if (tag == null) {
            return null;
        }
        String value = tag.getFirst(fieldKey);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Integer parseInteger(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = FIRST_INTEGER_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
