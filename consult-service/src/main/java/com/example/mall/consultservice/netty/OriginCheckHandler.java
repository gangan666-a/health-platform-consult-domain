package com.example.mall.consultservice.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * 握手期 Origin 基础校验（P2-02，fail-closed）：浏览器 WS 握手携带 Origin（页面来源），
 * 仅放行本地演示来源（localhost/127.0.0.1/[::1]），其余一律 403 拒绝。
 * 每 channel 新建实例（非 @Sharable），放在 HttpObjectAggregator 之后、WebSocketServerProtocolHandler 之前。
 */
public class OriginCheckHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(OriginCheckHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest req) {
            String origin = req.headers().get(HttpHeaderNames.ORIGIN);
            if (origin != null && !isAllowed(origin)) {
                log.warn("拒绝非本地 Origin 的 WS 握手: {}", origin);
                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN));
                ctx.close();
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    private boolean isAllowed(String origin) {
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            return host != null && ("localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host) || "[::1]".equals(host));
        } catch (Exception ex) {
            return false;
        }
    }
}
