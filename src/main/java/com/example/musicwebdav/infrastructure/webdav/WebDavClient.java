package com.example.musicwebdav.infrastructure.webdav;

import com.example.musicwebdav.domain.model.WebDavConnectResult;
import com.example.musicwebdav.domain.model.WebDavFileObject;
import java.io.File;
import java.io.IOException;
import java.util.List;

public interface WebDavClient {

    WebDavConnectResult testConnection(String baseUrl, String username, String password, String rootPath);

    List<WebDavFileObject> listFiles(String baseUrl, String username, String password, String rootPath);

    File downloadToTempFile(String username, String password, String fileUrl) throws IOException;
}
