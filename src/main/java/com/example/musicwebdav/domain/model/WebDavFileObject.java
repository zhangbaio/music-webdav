package com.example.musicwebdav.domain.model;

import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebDavFileObject {

    private String relativePath;

    private String fileUrl;

    private String etag;

    private Date lastModified;

    private Long size;

    private String mimeType;
}
