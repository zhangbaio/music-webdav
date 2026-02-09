package com.example.musicwebdav.infrastructure.webdav;

import com.example.musicwebdav.domain.model.WebDavConnectResult;
import com.example.musicwebdav.domain.model.WebDavDirectoryInfo;
import com.example.musicwebdav.domain.model.WebDavFileObject;
import com.github.sardine.Sardine;
import java.io.File;
import java.io.IOException;
import java.util.List;

public interface WebDavClient {

    WebDavConnectResult testConnection(String baseUrl, String username, String password, String rootPath);

    List<WebDavFileObject> listFiles(String baseUrl, String username, String password, String rootPath);

    File downloadToTempFile(String username, String password, String fileUrl) throws IOException;

    /** Create a reusable Sardine session for connection pooling within a scan task. */
    Sardine createSession(String username, String password);

    /** List direct children (files and subdirectories) of a single directory using an existing session. */
    WebDavDirectoryInfo listDirectory(Sardine session, String directoryUrl, String rootUrl);

    /** Download using an existing session. */
    File downloadToTempFile(Sardine session, String fileUrl) throws IOException;

    /**
     * Download only the head and tail bytes of a file for metadata parsing.
     * Returns a temp file containing the head bytes, zero-fill, and tail bytes.
     * Returns null if Range requests are not supported by the server.
     */
    File downloadPartialToTempFile(Sardine session, String fileUrl, long fileSize,
                                    int headBytes, int tailBytes) throws IOException;

    /** Build the full root URL from baseUrl and rootPath. */
    String buildRootUrl(String baseUrl, String rootPath);

    /** Safely close a Sardine session. */
    void closeSession(Sardine session);
}
