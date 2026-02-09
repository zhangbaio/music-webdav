package com.example.musicwebdav.infrastructure.parser;

import com.example.musicwebdav.domain.model.AudioMetadata;
import java.io.File;

public interface AudioMetadataParser {

    AudioMetadata parse(File audioFile) throws Exception;
}
