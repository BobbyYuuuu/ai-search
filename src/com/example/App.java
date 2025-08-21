package com.example;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

public class App {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api", new ApiHandler());
        server.createContext("/", new StaticFileHandler("./public"));
        server.setExecutor(null);
        System.out.println("SimpleSearch + AI => http://localhost:" + port + "/");
        server.start();
    }
}