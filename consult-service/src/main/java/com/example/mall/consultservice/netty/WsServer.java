package com.example.mall.consultservice.netty;

import com.example.mall.consultservice.config.ConsultProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Netty WebSocket 服务端（ADR-IM-01）：与 Spring MVC REST（:8085）分端口共存。
 * <p>
 * SmartLifecycle 管理生命周期：{@code getPhase()=MAX_VALUE-1} 保证关闭顺序在 Tomcat/REST 之后、
 * Hikari 数据源之前（先停 Netty 再停写队列与连接池，不丢在途消息）。事件循环线程池随本服务生命周期创建/回收。
 */
@Component
public class WsServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WsServer.class);

    private final ConsultProperties props;
    private final WsPipelineInitializer pipeline;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;
    private volatile boolean running;

    public WsServer(ConsultProperties props, WsPipelineInitializer pipeline) {
        this.props = props;
        this.pipeline = pipeline;
    }

    @Override
    public void start() {
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();
        try {
            serverChannel = new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(pipeline)
                    .bind(props.getWsPort())
                    .sync()
                    .channel();
            running = true;
            log.info("Netty WebSocket 服务端已启动: ws://localhost:{}/ws", props.getWsPort());
        } catch (Exception ex) {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
            throw new IllegalStateException("Netty WS 启动失败，端口 " + props.getWsPort(), ex);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (boss != null) {
            boss.shutdownGracefully();
        }
        if (worker != null) {
            worker.shutdownGracefully();
        }
        log.info("Netty WebSocket 服务端已关闭");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 优雅停机优先级：晚于 Spring MVC(REST)、早于数据源/Hikari */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
