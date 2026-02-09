package com.example.musicwebdav.domain.model;

import java.util.Date;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebDavDirectoryInfo {

    private String relativePath;

    private String directoryUrl;

    private String etag;

    private Date lastModified;

    private int childCount;

    private List<WebDavFileObject> files;

    private List<String> subdirectoryUrls;
}
