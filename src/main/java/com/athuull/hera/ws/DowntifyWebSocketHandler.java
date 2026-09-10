package com.athuull.hera.ws;

import com.athuull.hera.config.DowntifyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DowntifyWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DowntifyWebSocketHandler.class);

    private final ProgressWebSocketHandler progressHandler;
    private final DowntifyConfig config;
    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private volatile WebSocketSession downtifySession;

    public DowntifyWebSocketHandler(ProgressWebSocketHandler progressHandler,
                                    DowntifyConfig config) {
        this.progressHandler = progressHandler;
        this.config = config;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 3000)
    public void maintainConnection() {
        if (!isConnected() && isConnecting.compareAndSet(false, true)) {
            try {
                String baseUrl = config.getBaseUrl() != null
                        ? config.getBaseUrl().replaceAll("/+$", "")
                        : "http://localhost:8000";
                String wsUrl = baseUrl
                        .replace("http://", "ws://")
                        .replace("https://", "wss://")
                        + "/api/ws?client_id=spring-orchestrator";
                log.debug("Connecting to Downtify WebSocket at {}", wsUrl);
                client.execute(this, wsUrl).whenComplete((session, throwable) -> {
                    isConnecting.set(false);
                    if (throwable != null) {
                        log.debug("Downtify WS connection failed: {}", throwable.getMessage());
                    }
                });
            } catch (Exception e) {
                isConnecting.set(false);
                log.debug("Downtify WS connection attempt failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.downtifySession = session;
        this.isConnecting.set(false);
        log.info("Connected to Downtify WebSocket");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        progressHandler.broadcast(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        this.downtifySession = null;
        this.isConnecting.set(false);
        log.info("Downtify WebSocket disconnected");
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Downtify WS transport error: {}", exception.getMessage());
        this.downtifySession = null;
        this.isConnecting.set(false);
    }

    public boolean isConnected() {
        return downtifySession != null && downtifySession.isOpen();
    }
}