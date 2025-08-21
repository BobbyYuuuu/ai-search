package com.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
    import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class ApiHandler implements HttpHandler {
    private static final String CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String IMAGE_GEN_URL = "https://api.openai.com/v1/images/generations";
    private static final String DEFAULT_MODEL = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
    private static final String IMAGE_MODEL = System.getenv().getOrDefault("OPENAI_IMAGE_MODEL", "gpt-image-1");
    private static final File UPLOAD_DIR = new File("./public/uploads");

    private static byte[] bytes(String s){ return s.getBytes(StandardCharsets.UTF_8); }
    private static void write(HttpExchange ex,int code,String ctype,String body) throws IOException {
    ex.getResponseHeaders().add("Content-Type", ctype);
    ex.getResponseHeaders().add("Access-Control-Allow-Origin","*");
    ex.getResponseHeaders().add("Access-Control-Allow-Headers","Content-Type, Authorization");
    ex.getResponseHeaders().add("Access-Control-Allow-Methods","GET,POST,OPTIONS,HEAD");
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    boolean isHead = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
    ex.sendResponseHeaders(code, isHead ? 0 : b.length);
    try (OutputStream os = ex.getResponseBody()) {
        if (!isHead) os.write(b); // no body for HEAD
    }
    }

    @Override public void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        if (method.equalsIgnoreCase("OPTIONS")) { write(ex,204,"application/json; charset=utf-8",""); return; }
        if ("/api/hello".equals(path)) { write(ex,200,"application/json; charset=utf-8","{\"message\":\"ok\"}"); return; }
        if ("/api/chat".equals(path) && method.equalsIgnoreCase("GET")) { handleChat(ex); return; }
        if ("/api/vision".equals(path) && method.equalsIgnoreCase("POST")) { handleVision(ex); return; }
        if ("/api/gen-image".equals(path) && method.equalsIgnoreCase("POST")) { handleGen(ex); return; }
        write(ex,404,"application/json; charset=utf-8","{\"error\":\"not found\"}");
    }

    private void handleChat(HttpExchange ex) throws IOException {
        String q = Optional.ofNullable(ex.getRequestURI().getQuery()).orElse("");
        String query = "";
        for (String kv : q.split("&")) {
            if (kv.startsWith("q=")) { query = java.net.URLDecoder.decode(kv.substring(2), StandardCharsets.UTF_8); break; }
        }
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) { write(ex,501,"application/json; charset=utf-8","{\"error\":\"Missing OPENAI_API_KEY\"}"); return; }
        try{
            String sys = "You are a helpful search-style assistant. Keep answers concise and clear.";
            String json = "{"
              + "\"model\":\""+esc(DEFAULT_MODEL)+"\","
              + "\"temperature\":0.3,"
              + "\"messages\":[{\"role\":\"system\",\"content\":\""+esc(sys)+"\"},{\"role\":\"user\",\"content\":\""+esc(query)+"\"}]}";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
              .uri(URI.create(CHAT_URL)).header("Authorization","Bearer "+apiKey)
              .header("Content-Type","application/json").timeout(Duration.ofSeconds(45))
              .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String out = "{\"query\":"+toJson(query)+",\"model\":"+toJson(DEFAULT_MODEL)+",\"openai\":"+resp.body()+"}";
            write(ex, resp.statusCode()>=200 && resp.statusCode()<300 ? 200 : resp.statusCode(), "application/json; charset=utf-8", out);
        } catch (HttpTimeoutException t){ write(ex,504,"application/json; charset=utf-8","{\"error\":\"timeout\"}"); }
          catch (Exception e){ write(ex,502,"application/json; charset=utf-8","{\"error\":\"request failed\",\"detail\":\""+esc(e.toString())+"\"}"); }
    }

    private void handleVision(HttpExchange ex) throws IOException {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) { write(ex,501,"application/json; charset=utf-8","{\"error\":\"Missing OPENAI_API_KEY\"}"); return; }
        String ctype = ex.getRequestHeaders().getFirst("Content-Type");
        if (ctype == null || !ctype.contains("multipart/form-data")) { write(ex,400,"application/json; charset=utf-8","{\"error\":\"Expected multipart/form-data\"}"); return; }
        String boundary = null;
        for (String part : ctype.split(";")) { part = part.trim(); if (part.startsWith("boundary=")) { boundary = part.substring(9).replaceAll("^\"|\"$",""); } }
        if (boundary == null){ write(ex,400,"application/json; charset=utf-8","{\"error\":\"Missing boundary\"}"); return; }

        byte[] body = ex.getRequestBody().readAllBytes();
        MP mp = MP.parse(body, boundary);
        MP.Part img = mp.getFirstFile("image");
        String userPrompt = Optional.ofNullable(mp.getField("q")).orElse("Please analyze this image and summarize key details.");
        if (img == null) { write(ex,400,"application/json; charset=utf-8","{\"error\":\"No image provided\"}"); return; }
        if (!UPLOAD_DIR.exists()) UPLOAD_DIR.mkdirs();
        String ext = img.extOr("jpg");
        String base = "img_" + System.currentTimeMillis();
        File saved = new File(UPLOAD_DIR, base+"."+ext);
        try(FileOutputStream fos = new FileOutputStream(saved)){ fos.write(img.data); }

        String dataUrl = "data:" + (img.contentType!=null?img.contentType:"image/"+ext) + ";base64," + java.util.Base64.getEncoder().encodeToString(img.data);
        try{
            String sys = "You are an expert visual analyst. Be concise but precise; use short bullets if helpful.";
            String json = "{"
              + "\"model\":\""+esc(DEFAULT_MODEL)+"\","
              + "\"temperature\":0.2,"
              + "\"messages\":[{\"role\":\"system\",\"content\":\""+esc(sys)+"\"},{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\""+esc(userPrompt)+"\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\""+esc(dataUrl)+"\"}}]}]}";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
              .uri(URI.create(CHAT_URL)).header("Authorization","Bearer "+apiKey)
              .header("Content-Type","application/json").timeout(Duration.ofSeconds(60))
              .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String assistant = extractAssistant(resp.body());
            String caption = firstLine(assistant);
            File annotated = new File(UPLOAD_DIR, base + "_annotated.jpg");
            captionImage(saved, annotated, caption);
            String out = "{\"ok\":true,\"model\":\""+esc(DEFAULT_MODEL)+"\",\"original_url\":\"/uploads/"+saved.getName()+"\",\"annotated_url\":\"/uploads/"+annotated.getName()+"\",\"analysis\":"+toJson(assistant)+",\"openai\":"+resp.body()+"}";
            write(ex, resp.statusCode()>=200 && resp.statusCode()<300 ? 200 : resp.statusCode(), "application/json; charset=utf-8", out);
        } catch (HttpTimeoutException t){ write(ex,504,"application/json; charset=utf-8","{\"error\":\"vision timeout\"}"); }
          catch (Exception e){ write(ex,502,"application/json; charset=utf-8","{\"error\":\"vision failed\",\"detail\":\""+esc(e.toString())+"\"}"); }
    }

    private void handleGen(HttpExchange ex) throws IOException {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) { write(ex,501,"application/json; charset=utf-8","{\"error\":\"Missing OPENAI_API_KEY\"}"); return; }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String prompt = findJson(body, "prompt"); if (prompt==null||prompt.isEmpty()) prompt = "a simple black square";
        String size = findJson(body, "size"); if (size==null||size.isEmpty()) size = "1024x1024";
        String json = "{\"model\":\""+esc(IMAGE_MODEL)+"\",\"prompt\":\""+esc(prompt)+"\",\"size\":\""+esc(size)+"\",\"n\":1,\"response_format\":\"b64_json\"}";
        try{
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
              .uri(URI.create(IMAGE_GEN_URL)).header("Authorization","Bearer "+apiKey)
              .header("Content-Type","application/json").timeout(Duration.ofSeconds(60))
              .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String b64 = firstB64(resp.body());
            if (b64==null || b64.isEmpty()) { write(ex,502,"application/json; charset=utf-8","{\"error\":\"no image returned\"}"); return; }
            if (!UPLOAD_DIR.exists()) UPLOAD_DIR.mkdirs();
            File out = new File(UPLOAD_DIR, "gen_"+System.currentTimeMillis()+".png");
            try(FileOutputStream fos = new FileOutputStream(out)){ fos.write(java.util.Base64.getDecoder().decode(b64)); }
            write(ex, resp.statusCode()>=200 && resp.statusCode()<300 ? 200 : resp.statusCode(), "application/json; charset=utf-8", "{\"ok\":true,\"url\":\"/uploads/"+out.getName()+"\"}");
        } catch (HttpTimeoutException t){ write(ex,504,"application/json; charset=utf-8","{\"error\":\"image timeout\"}"); }
          catch (Exception e){ write(ex,502,"application/json; charset=utf-8","{\"error\":\"image failed\",\"detail\":\""+esc(e.toString())+"\"}"); }
    }

    // helpers
    private static String esc(String s){ return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"); }
    private static String toJson(String s){ return "\""+esc(s)+"\""; }
    private static String extractAssistant(String json){
        int i = json.indexOf("\"role\":\"assistant\""); if (i<0) return json;
        int c = json.indexOf("\"content\":", i); if (c<0) return json;
        int q1 = json.indexOf('"', c+10); if (q1<0) return json;
        StringBuilder sb = new StringBuilder(); boolean esc=false;
        for (int k=q1+1;k<json.length();k++){
            char ch = json.charAt(k);
            if (esc){ sb.append(ch); esc=false; continue; }
            if (ch=='\\'){ esc=true; continue; }
            if (ch=='"'){ break; }
            sb.append(ch);
        }
        return sb.toString();
    }
    private static String firstLine(String s){ int i=s.indexOf('\n'); String t=(i>=0?s.substring(0,i):s).trim(); if (t.length()>180) t=t.substring(0,180)+"…"; return t.isEmpty()? "Analysis" : t; }
    private static void captionImage(File src, File out, String caption) throws IOException {
        BufferedImage img = ImageIO.read(src); if (img==null) throw new IOException("bad image");
        int w = img.getWidth(), h = img.getHeight();
        int bar = Math.max(40, h/10);
        BufferedImage outImg = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D g = outImg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(img,0,0,null);
        g.setColor(new Color(0,0,0,150)); g.fillRect(0,h-bar,w,bar);
        g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, Math.max(14, bar/3)));
        g.drawString(caption, 12, h - bar/2);
        g.dispose();
        ImageIO.write(outImg, "jpg", out);
    }
    private static String findJson(String body,String field){
        String p="\""+field+"\""; int i=body.indexOf(p); if(i<0) return null;
        int c=body.indexOf(':', i+p.length()); if(c<0) return null;
        int q1=body.indexOf('"', c+1); if(q1<0) return null;
        int q2=body.indexOf('"', q1+1); if(q2<0) return null;
        return body.substring(q1+1, q2);
    }
    private static String firstB64(String json){
        String p="\"b64_json\""; int i=json.indexOf(p); if(i<0) return null;
        int c=json.indexOf(':', i+p.length()); if(c<0) return null;
        int q1=json.indexOf('"', c+1); if(q1<0) return null;
        int q2=json.indexOf('"', q1+1); if(q2<0) return null;
        return json.substring(q1+1, q2);
    }

    // Simple multipart parser
    static class MP {
        static class Part {
            String name, filename, contentType; byte[] data;
            String extOr(String def){ if(filename==null) return def; int i=filename.lastIndexOf('.'); return (i>=0 && i<filename.length()-1) ? filename.substring(i+1).toLowerCase() : def; }
        }
        java.util.List<Part> parts = new java.util.ArrayList<>();
        String getField(String name){ for(Part p:parts) if(name.equals(p.name) && p.filename==null) return new String(p.data, StandardCharsets.UTF_8); return null; }
        Part getFirstFile(String name){ for(Part p:parts) if(name.equals(p.name) && p.filename!=null) return p; return null; }
        static MP parse(byte[] body, String boundary) throws IOException {
            MP mp = new MP(); byte[] sep=("--"+boundary).getBytes(StandardCharsets.UTF_8); byte[] end=("--"+boundary+"--").getBytes(StandardCharsets.UTF_8);
            int pos=0; 
            while(true){
                int start=indexOf(body, sep, pos); if(start<0) break; start+=sep.length; if(start+2<=body.length && body[start]=='\r' && body[start+1]=='\n') start+=2;
                int next=indexOf(body, sep, start), nextEnd=indexOf(body, end, start), stop=(nextEnd>=0 && (next<0 || nextEnd<next))? nextEnd : next; if(stop<0) break;
                int headerEnd=indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), start); if(headerEnd<0 || headerEnd>stop) break;
                String headers=new String(body, start, headerEnd-start, StandardCharsets.UTF_8);
                int dataStart=headerEnd+4, dataEnd=stop-2; if(dataEnd<dataStart) dataEnd=dataStart;
                Part p=new Part();
                for(String line: headers.split("\r\n")){
                    String low=line.toLowerCase();
                    if(low.startsWith("content-disposition:")){
                        for(String token: line.split(";")){
                            token = token.trim();
                            if(token.startsWith("name=")) p.name = strip(token.substring(5));
                            else if(token.startsWith("filename=")) p.filename = strip(token.substring(9));
                        }
                    } else if(low.startsWith("content-type:")){
                        p.contentType = line.split(":",2)[1].trim();
                    }
                }
                p.data = java.util.Arrays.copyOfRange(body, dataStart, dataEnd);
                mp.parts.add(p);
                pos=stop;
            }
            return mp;
        }
        private static String strip(String s){ if(s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length()-1); return s; }
        private static int indexOf(byte[] data, byte[] pat, int from){
            outer: for (int i=from;i<=data.length-pat.length;i++){ for(int j=0;j<pat.length;j++) if(data[i+j]!=pat[j]) continue outer; return i; } return -1;
        }
    }
}