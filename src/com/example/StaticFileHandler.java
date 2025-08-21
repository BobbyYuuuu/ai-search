package com.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class StaticFileHandler implements HttpHandler {
    private final File baseDir;
    private final Map<String,String> mime = new HashMap<>();

    public StaticFileHandler(String base) {
        this.baseDir = new File(base);
        mime.put("html","text/html; charset=utf-8");
        mime.put("css","text/css; charset=utf-8");
        mime.put("js","application/javascript; charset=utf-8");
        mime.put("json","application/json; charset=utf-8");
        mime.put("png","image/png");
        mime.put("jpg","image/jpeg");
        mime.put("jpeg","image/jpeg");
        mime.put("webp","image/webp");
        mime.put("svg","image/svg+xml");
        mime.put("ico","image/x-icon");
        mime.put("txt","text/plain; charset=utf-8");
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) { return fis.readAllBytes(); }
    }

    private void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        if (type != null) ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private String contentType(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        if (i > 0 && i < n.length()-1) {
            String ext = n.substring(i+1).toLowerCase();
            return mime.getOrDefault(ext, "application/octet-stream");
        }
        return "application/octet-stream";
    }

    @Override public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) {
            send(ex, 400, "text/plain; charset=utf-8", "Bad path".getBytes(StandardCharsets.UTF_8)); return;
        }
        File f = new File(baseDir, path);
        if (f.isDirectory()) f = new File(f, "index.html");
        if (f.exists() && f.isFile()) {
            send(ex, 200, contentType(f), readAll(f)); return;
        }
        File nf = new File(baseDir, "404.html");
        if (nf.exists()) send(ex, 404, "text/html; charset=utf-8", readAll(nf));
        else send(ex, 404, "text/plain; charset=utf-8", "404".getBytes(StandardCharsets.UTF_8));
    }
}