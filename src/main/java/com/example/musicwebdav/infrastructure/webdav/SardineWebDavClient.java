package com.example.musicwebdav.infrastructure.webdav;

import com.example.musicwebdav.domain.model.WebDavConnectResult;
import com.example.musicwebdav.domain.model.WebDavDirectoryInfo;
import com.example.musicwebdav.domain.model.WebDavFileObject;
import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;
import com.github.sardine.impl.SardineException;
import com.github.sardine.impl.SardineImpl;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SardineWebDavClient implements WebDavClient {

    private static final Logger log = LoggerFactory.getLogger(SardineWebDavClient.class);

    @Override
    public WebDavConnectResult testConnection(String baseUrl, String username, String password, String rootPath) {
        long start = System.currentTimeMillis();
        String targetUrl;
        try {
            targetUrl = buildTargetUrl(baseUrl, rootPath);
        } catch (IllegalArgumentException e) {
            return new WebDavConnectResult(false, e.getMessage());
        }
        Sardine sardine = SardineFactory.begin(username, password);
        try {
            List<DavResource> resources = sardine.list(ensureDirectoryUrl(targetUrl), 1);
            long cost = System.currentTimeMillis() - start;
            return new WebDavConnectResult(true, "连接成功，目录可访问，耗时 " + cost + " ms，返回资源数 " + resources.size());
        } catch (SardineException e) {
            String message = mapSardineException(e);
            log.warn("WebDAV test failed, status={}, url={}", e.getStatusCode(), targetUrl);
            return new WebDavConnectResult(false, message);
        } catch (IOException e) {
            log.warn("WebDAV test IO error, url={}", targetUrl, e);
            return new WebDavConnectResult(false, "WebDAV网络异常：" + e.getMessage());
        } finally {
            shutdownSafely(sardine);
        }
    }

    @Override
    public List<WebDavFileObject> listFiles(String baseUrl, String username, String password, String rootPath) {
        String rootUrl = ensureDirectoryUrl(buildTargetUrl(baseUrl, rootPath));
        URI rootUri = URI.create(rootUrl);
        String hostPrefix = rootUri.getScheme() + "://" + rootUri.getRawAuthority();

        Sardine sardine = SardineFactory.begin(username, password);
        Deque<String> dirs = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        List<WebDavFileObject> files = new ArrayList<>();
        dirs.push(rootUrl);
        try {
            while (!dirs.isEmpty()) {
                String currentDir = ensureDirectoryUrl(dirs.pop());
                String currentKey = normalizeUrl(currentDir);
                if (!visited.add(currentKey)) {
                    continue;
                }

                List<DavResource> resources = sardine.list(currentDir, 1);
                for (DavResource resource : resources) {
                    String href = resolveHref(currentDir, hostPrefix, resource.getHref());
                    String normalizedHref = normalizeUrl(href);
                    if (currentKey.equals(normalizedHref)) {
                        continue;
                    }

                    if (resource.isDirectory()) {
                        dirs.push(ensureDirectoryUrl(href));
                        continue;
                    }

                    String relativePath = toRelativePath(rootUrl, href);
                    if (relativePath.isEmpty()) {
                        continue;
                    }

                    files.add(new WebDavFileObject(
                            relativePath,
                            href,
                            resource.getEtag(),
                            resource.getModified(),
                            resource.getContentLength(),
                            resource.getContentType()));
                }
            }
            return files;
        } catch (SardineException e) {
            throw new IllegalStateException(mapSardineException(e), e);
        } catch (IOException e) {
            throw new IllegalStateException("遍历WebDAV目录失败：" + e.getMessage(), e);
        } finally {
            shutdownSafely(sardine);
        }
    }

    @Override
    public File downloadToTempFile(String username, String password, String fileUrl) throws IOException {
        Sardine sardine = SardineFactory.begin(username, password);
        try {
            return doDownloadToTempFile(sardine, fileUrl);
        } finally {
            shutdownSafely(sardine);
        }
    }

    @Override
    public void downloadToOutputStream(String username, String password, String fileUrl, OutputStream outputStream) throws IOException {
        Sardine sardine = SardineFactory.begin(username, password);
        try (InputStream in = sardine.get(fileUrl)) {
            copyStream(in, outputStream);
            outputStream.flush();
        } finally {
            shutdownSafely(sardine);
        }
    }

    @Override
    public void delete(String username, String password, String targetUrl) {
        Sardine sardine = SardineFactory.begin(username, password);
        try {
            sardine.delete(ensureDirectoryUrl(targetUrl));
        } catch (SardineException e) {
            throw new IllegalStateException(mapSardineException(e), e);
        } catch (IOException e) {
            throw new IllegalStateException("删除WebDAV目录失败：" + e.getMessage(), e);
        } finally {
            shutdownSafely(sardine);
        }
    }

    @Override
    public Sardine createSession(String username, String password) {
        return SardineFactory.begin(username, password);
    }

    @Override
    public WebDavDirectoryInfo listDirectory(Sardine session, String directoryUrl, String rootUrl) {
        String dirUrl = ensureDirectoryUrl(directoryUrl);
        String dirKey = normalizeUrl(dirUrl);
        URI rootUri = URI.create(ensureDirectoryUrl(rootUrl));
        String hostPrefix = rootUri.getScheme() + "://" + rootUri.getRawAuthority();

        try {
            List<DavResource> resources = session.list(dirUrl, 1);
            List<WebDavFileObject> files = new ArrayList<>();
            List<String> subdirs = new ArrayList<>();
            String dirEtag = null;
            java.util.Date dirLastModified = null;

            for (DavResource resource : resources) {
                String href = resolveHref(dirUrl, hostPrefix, resource.getHref());
                String normalizedHref = normalizeUrl(href);

                if (dirKey.equals(normalizedHref)) {
                    // This is the directory itself
                    dirEtag = resource.getEtag();
                    dirLastModified = resource.getModified();
                    continue;
                }

                if (resource.isDirectory()) {
                    subdirs.add(ensureDirectoryUrl(href));
                } else {
                    String relativePath = toRelativePath(rootUrl, href);
                    if (!relativePath.isEmpty()) {
                        files.add(new WebDavFileObject(
                                relativePath, href,
                                resource.getEtag(), resource.getModified(),
                                resource.getContentLength(), resource.getContentType()));
                    }
                }
            }

            WebDavDirectoryInfo info = new WebDavDirectoryInfo();
            info.setRelativePath(toRelativePath(rootUrl, dirUrl));
            info.setDirectoryUrl(dirUrl);
            info.setEtag(dirEtag);
            info.setLastModified(dirLastModified);
            info.setChildCount(files.size() + subdirs.size());
            info.setFiles(files);
            info.setSubdirectoryUrls(subdirs);
            return info;
        } catch (SardineException e) {
            throw new IllegalStateException(mapSardineException(e), e);
        } catch (IOException e) {
            throw new IllegalStateException("列出WebDAV目录失败：" + e.getMessage(), e);
        }
    }

    @Override
    public File downloadToTempFile(Sardine session, String fileUrl) throws IOException {
        return doDownloadToTempFile(session, fileUrl);
    }

    @Override
    public File downloadPartialToTempFile(Sardine session, String fileUrl, long fileSize,
                                           int headBytes, int tailBytes) throws IOException {
        if (fileSize <= 0 || fileSize <= headBytes) {
            // File is small enough, just download fully
            return doDownloadToTempFile(session, fileUrl);
        }

        // Sardine uses Apache HttpClient internally; we need direct access for Range requests
        HttpClient httpClient = extractHttpClient(session);
        if (httpClient == null) {
            log.debug("Cannot extract HttpClient from Sardine, falling back to full download");
            return doDownloadToTempFile(session, fileUrl);
        }

        String suffix = guessSuffix(fileUrl);
        File tempFile = File.createTempFile("webdav-partial-", suffix);

        try {
            // Download head bytes
            byte[] headData = downloadRange(httpClient, fileUrl, 0, headBytes - 1);
            if (headData == null) {
                // Range not supported, fall back to full download
                if (!tempFile.delete()) {
                    log.debug("Failed to delete partial temp file: {}", tempFile.getAbsolutePath());
                }
                return doDownloadToTempFile(session, fileUrl);
            }

            // Download tail bytes
            byte[] tailData = null;
            if (tailBytes > 0 && fileSize > headBytes) {
                long tailStart = fileSize - tailBytes;
                if (tailStart < headBytes) {
                    tailStart = headBytes;
                }
                tailData = downloadRange(httpClient, fileUrl, tailStart, fileSize - 1);
            }

            // Compose the temp file: head + zero-fill + tail
            try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
                raf.write(headData);
                if (tailData != null && tailData.length > 0) {
                    // Seek to tail position and write
                    long tailPosition = fileSize - tailData.length;
                    raf.seek(tailPosition);
                    raf.write(tailData);
                }
                // Set file length to match original (zero-fills the gap)
                raf.setLength(fileSize);
            }

            return tempFile;
        } catch (Exception e) {
            if (tempFile.exists() && !tempFile.delete()) {
                log.debug("Failed to delete partial temp file after error: {}", tempFile.getAbsolutePath());
            }
            throw new IOException("Range 部分下载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String buildRootUrl(String baseUrl, String rootPath) {
        return ensureDirectoryUrl(buildTargetUrl(baseUrl, rootPath));
    }

    @Override
    public void closeSession(Sardine session) {
        shutdownSafely(session);
    }

    // --- Private methods ---

    private byte[] downloadRange(HttpClient httpClient, String fileUrl, long start, long end) throws IOException {
        HttpGet request = new HttpGet(fileUrl);
        request.setHeader("Range", "bytes=" + start + "-" + end);
        HttpResponse response = httpClient.execute(request);
        int statusCode = response.getStatusLine().getStatusCode();

        if (statusCode == HttpStatus.SC_PARTIAL_CONTENT) {
            try (InputStream in = response.getEntity().getContent()) {
                return readAllBytes(in, (int) (end - start + 1));
            }
        } else if (statusCode == HttpStatus.SC_OK) {
            // Server returned full content (doesn't support Range)
            // Read and truncate to requested range
            try (InputStream in = response.getEntity().getContent()) {
                if (start == 0) {
                    return readBytes(in, (int) (end - start + 1));
                }
            }
            return null; // Range not supported
        } else if (statusCode == 416) {
            // Range not satisfiable
            return null;
        } else {
            throw new IOException("Range 请求失败，状态码: " + statusCode);
        }
    }

    private byte[] readAllBytes(InputStream in, int expectedSize) throws IOException {
        byte[] buffer = new byte[expectedSize];
        int totalRead = 0;
        int len;
        while (totalRead < expectedSize && (len = in.read(buffer, totalRead, expectedSize - totalRead)) != -1) {
            totalRead += len;
        }
        if (totalRead < expectedSize) {
            byte[] result = new byte[totalRead];
            System.arraycopy(buffer, 0, result, 0, totalRead);
            return result;
        }
        return buffer;
    }

    private byte[] readBytes(InputStream in, int maxBytes) throws IOException {
        byte[] buffer = new byte[maxBytes];
        int totalRead = 0;
        int len;
        while (totalRead < maxBytes && (len = in.read(buffer, totalRead, maxBytes - totalRead)) != -1) {
            totalRead += len;
        }
        if (totalRead < maxBytes) {
            byte[] result = new byte[totalRead];
            System.arraycopy(buffer, 0, result, 0, totalRead);
            return result;
        }
        return buffer;
    }

    private HttpClient extractHttpClient(Sardine session) {
        if (session instanceof SardineImpl) {
            try {
                java.lang.reflect.Field field = SardineImpl.class.getDeclaredField("client");
                field.setAccessible(true);
                return (HttpClient) field.get(session);
            } catch (Exception e) {
                log.debug("Failed to extract HttpClient from SardineImpl", e);
            }
        }
        return null;
    }

    private File doDownloadToTempFile(Sardine sardine, String fileUrl) throws IOException {
        String suffix = guessSuffix(fileUrl);
        File tempFile = File.createTempFile("webdav-audio-", suffix);
        try (InputStream in = sardine.get(fileUrl);
             FileOutputStream out = new FileOutputStream(tempFile)) {
            copyStream(in, out);
            out.flush();
            return tempFile;
        }
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
    }

    private String resolveHref(String currentDir, String hostPrefix, URI href) {
        if (href == null) {
            return currentDir;
        }
        String trimmed = href.toString().trim();
        if (trimmed.isEmpty()) {
            return currentDir;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("/")) {
            return hostPrefix + trimmed;
        }
        return URI.create(ensureDirectoryUrl(currentDir)).resolve(trimmed).toString();
    }

    private String buildTargetUrl(String baseUrl, String rootPath) {
        try {
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("WebDAV地址不合法：baseUrl不能为空");
            }
            URI baseUri = new URI(baseUrl.trim());
            if (baseUri.getScheme() == null || baseUri.getRawAuthority() == null) {
                throw new IllegalArgumentException("WebDAV地址不合法：需包含协议和主机");
            }

            String mergedPath = mergePath(baseUri.getPath(), rootPath);
            URI target = new URI(baseUri.getScheme(), baseUri.getRawAuthority(), mergedPath, null, null);
            return target.toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("WebDAV地址不合法");
        }
    }

    private String mergePath(String basePath, String rootPath) {
        String safeBasePath = basePath == null ? "" : basePath.trim();
        if (safeBasePath.endsWith("/") && safeBasePath.length() > 1) {
            safeBasePath = safeBasePath.substring(0, safeBasePath.length() - 1);
        }
        if (rootPath == null || rootPath.trim().isEmpty() || "/".equals(rootPath.trim())) {
            return safeBasePath.isEmpty() ? "/" : safeBasePath;
        }

        String normalizedRootPath = rootPath.trim().replace('\\', '/');
        while (normalizedRootPath.startsWith("//")) {
            normalizedRootPath = normalizedRootPath.substring(1);
        }
        if (!normalizedRootPath.startsWith("/")) {
            normalizedRootPath = "/" + normalizedRootPath;
        }

        String merged = safeBasePath + normalizedRootPath;
        return merged.isEmpty() ? "/" : merged;
    }

    private String toRelativePath(String rootUrl, String href) {
        try {
            URI rootUri = URI.create(ensureDirectoryUrl(rootUrl));
            URI hrefUri = URI.create(href);
            String rootPath = rootUri.getPath();
            String hrefPath = hrefUri.getPath();
            String relativePath;
            if (hrefPath.startsWith(rootPath)) {
                relativePath = hrefPath.substring(rootPath.length());
            } else {
                relativePath = hrefPath;
            }
            relativePath = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
            return URLDecoder.decode(relativePath, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            log.debug("Failed to resolve relative path from rootUrl={}, href={}", rootUrl, href);
            return "";
        }
    }

    private String ensureDirectoryUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return url.endsWith("/") ? url : url + "/";
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String normalized = url;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void shutdownSafely(Sardine sardine) {
        try {
            sardine.shutdown();
        } catch (IOException e) {
            log.debug("WebDAV client shutdown failed", e);
        }
    }

    private String mapSardineException(SardineException e) {
        int status = e.getStatusCode();
        if (status == 401 || status == 403) {
            return "WebDAV鉴权失败，请检查用户名或密码";
        }
        if (status == 404) {
            return "WebDAV目录不存在，请检查rootPath";
        }
        if (status >= 500) {
            return "WebDAV服务端异常，状态码：" + status;
        }
        return "WebDAV请求失败，状态码：" + status;
    }

    private String guessSuffix(String fileUrl) {
        if (fileUrl == null || fileUrl.trim().isEmpty()) {
            return ".tmp";
        }
        int idx = fileUrl.lastIndexOf('.');
        if (idx < 0 || idx == fileUrl.length() - 1) {
            return ".tmp";
        }
        String suffix = fileUrl.substring(idx);
        if (suffix.length() > 10) {
            return ".tmp";
        }
        return suffix;
    }
}
