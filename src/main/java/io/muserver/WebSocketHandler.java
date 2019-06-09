package io.muserver;

import java.util.Map;

public class WebSocketHandler implements MuHandler, RouteHandler {

    private final MuWebSocketFactory factory;
    private final String path;
    private final long idleReadTimeoutMills;
    private final long pingAfterWriteMillis;
    private final int maxFramePayloadLength;

    WebSocketHandler(MuWebSocketFactory factory, String path, long idleReadTimeoutMills, long pingAfterWriteMillis, int maxFramePayloadLength) {
        this.factory = factory;
        this.path = path;
        this.idleReadTimeoutMills = idleReadTimeoutMills;
        this.pingAfterWriteMillis = pingAfterWriteMillis;
        this.maxFramePayloadLength = maxFramePayloadLength;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        if (request.method() != Method.GET) {
            return false;
        }
        if (Mutils.hasValue(path) && !path.equals(request.relativePath())) {
            return false;
        }

        boolean isUpgradeRequest = request.headers().contains(HeaderNames.UPGRADE, HeaderValues.WEBSOCKET, true);
        if (!isUpgradeRequest) {
            return false;
        }

        MuWebSocket muWebSocket = factory.create(request);
        if (muWebSocket == null) {
            return false;
        }
        NettyRequestAdapter reqImpl = (NettyRequestAdapter) request;
        boolean upgraded = reqImpl.websocketUpgrade(muWebSocket, idleReadTimeoutMills, pingAfterWriteMillis, maxFramePayloadLength);
        if (upgraded) {
            ((NettyResponseAdaptor) response).setWebsocket();
        }
        return upgraded;
    }

    @Override
    public void handle(MuRequest request, MuResponse response, Map<String, String> pathParams) throws Exception {
        handle(request, response);
    }
}
