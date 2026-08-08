package com.example.mall.consultservice.netty;

import com.example.mall.consultservice.config.ConsultProperties;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Netty WS 流水线：HttpServerCodec → HttpObjectAggregator → OriginCheck → WebSocketServerProtocolHandler("/ws")
 * → IdleStateHandler(读空闲探活) → WsFrameHandler。事件循环线程池由 ServerBootstrap 创建。
 */
@Component
public class WsPipelineInitializer extends ChannelInitializer<SocketChannel> {

    private final ConsultProperties props;
    private final WsFrameHandler frameHandler;

    public WsPipelineInitializer(ConsultProperties props, WsFrameHandler frameHandler) {
        this.props = props;
        this.frameHandler = frameHandler;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast("httpCodec", new HttpServerCodec())
                .addLast("httpAggregator", new HttpObjectAggregator(65536))
                .addLast("originCheck", new OriginCheckHandler())
                .addLast("wsProtocol", new WebSocketServerProtocolHandler("/ws", null, true, 4096))
                .addLast("idle", new IdleStateHandler(
                        props.getWsHeartbeatIntervalSeconds(), 0, 0, TimeUnit.SECONDS))
                .addLast("frame", frameHandler);
    }
}
