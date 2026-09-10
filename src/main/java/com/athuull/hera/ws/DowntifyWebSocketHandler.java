package com.athuull.hera.ws;

import com.athuull.hera.config.DowntifyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

@Component
public class DowntifyWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DowntifyWebSocketHandler.class);

    private final ProgressWebSocketHandler progressHandler;
    private final DowntifyConfig config;
    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private volatile WebSocketSession downtifySession;

    public DowntifyWebSocketHandler(ProgressWebSocketHandler progressHandler,
                                    DowntifyConfig config) {
        this.progressHandler = progressHandler;
        this.config = config;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 3000)
    public void maintainConnection() {
        if (!isConnected()) {
            try {
                String wsUrl = config.getBaseUrl()
                        .replace("http://", "ws://")
                        .replace("https://", "wss://")
                        + "/api/ws?client_id=spring-orchestrator";
                log.debug("Connecting to Downtify WebSocket at {}", wsUrl);
                client.doHandshake(this, wsUrl);
            } catch (Exception e) {
                log.debug("Downtify WS connection attempt failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.downtifySession = session;
        log.info("Connected to Downtify WebSocket");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        progressHandler.broadcast(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        this.downtifySession = null;
        log.info("Downtify WebSocket disconnected");
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Downtify WS transport error: {}", exception.getMessage());
        this.downtifySession = null;
    }

    public boolean isConnected() {
        return downtifySession != null && downtifySession.isOpen();
    }
}