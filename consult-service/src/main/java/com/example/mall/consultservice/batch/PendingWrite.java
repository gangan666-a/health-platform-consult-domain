package com.example.mall.consultservice.batch;

import com.example.mall.consultservice.model.ChatMessage;

import java.util.concurrent.CompletableFuture;

/**
 * 待落库消息 + 完成信号。
 * <p>
 * {@code done} 在批量 INSERT 提交成功后完成（先落库后 ack/转发，NFR-6/M10）；
 * 调用方（MessageService）在 done 上挂接广播/ack 回调——回调运行在完成该 future 的线程
 * （批量写线程），按会话 FIFO 顺序触发，天然保证同会话 seq 顺序转发。
 */
public class PendingWrite {

    private final ChatMessage message;
    private final CompletableFuture<Void> done = new CompletableFuture<>();

    public PendingWrite(ChatMessage message) {
        this.message = message;
    }

    public ChatMessage getMessage() {
        return message;
    }

    public CompletableFuture<Void> done() {
        return done;
    }
}
