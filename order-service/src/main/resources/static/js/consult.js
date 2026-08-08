/**
 * 问诊聊天页 WS 客户端（BFF 侧）。
 * 帧协议与 consult-service 对齐：客户端 join/message/end/ping；服务端 joined/message/system/
 * prescription_issued/session_ended/ack/pong/error。
 * - 断线重连：指数退避（1s→15s 封顶），join 成功重置；重连后按 lastSeq 增量补拉历史。
 * - 心跳：本地 15s 一次 ping；收到服务端 ping 立即回 ping（服务端按入站帧判活）。
 * - prescription_issued：用户视角展示处方号并取明细构建「去下单」跳转（数量与处方一致）。
 */
(function () {
    var app = document.getElementById('consult-app');
    if (!app) {
        return;
    }
    var sessionId = app.getAttribute('data-session-id');
    var role = app.getAttribute('data-role');
    var token = app.getAttribute('data-token');
    var wsUrl = app.getAttribute('data-ws-url');
    var productId = app.getAttribute('data-product-id');
    var productName = app.getAttribute('data-product-name');

    var chatWindow = document.getElementById('chat-window');
    var msgInput = document.getElementById('msg-input');
    var sendBtn = document.getElementById('send-btn');
    var endBtn = document.getElementById('end-btn');
    var statusEl = document.getElementById('consult-status');
    var tokenWarning = document.getElementById('token-warning');
    var prescriptionCard = document.getElementById('prescription-card');
    var goOrderBtn = document.getElementById('go-order-btn');

    var ws = null;
    var reconnectAttempt = 0;
    var tokenInvalid = false;
    var heartbeatTimer = null;
    var lastSeq = 0;
    var renderedSeqs = {};
    var closed = false;

    /* ---------- 消息渲染 ---------- */

    function appendBubble(senderType, text) {
        var item = document.createElement('div');
        var label = senderType === 'SYSTEM' ? '系统' : (senderType === 'DOCTOR' ? '医生' : '用户');
        item.className = 'chat-msg ' + (senderType === 'SYSTEM' ? 'system' : (senderType === 'DOCTOR' ? 'doctor' : 'user'));
        item.innerHTML = '<span class="chat-sender">' + label + '</span><span class="chat-bubble">' + escapeHtml(text) + '</span>';
        chatWindow.appendChild(item);
        chatWindow.scrollTop = chatWindow.scrollHeight;
    }

    function appendTip(text) {
        var tip = document.createElement('div');
        tip.className = 'chat-tip';
        tip.textContent = text;
        chatWindow.appendChild(tip);
        chatWindow.scrollTop = chatWindow.scrollHeight;
    }

    function escapeHtml(text) {
        var div = document.createElement('div');
        div.textContent = text == null ? '' : String(text);
        return div.innerHTML;
    }

    function renderMessageFrame(senderType, content) {
        appendBubble(senderType, content);
    }

    function markSeq(seq) {
        if (seq != null && seq > lastSeq) {
            lastSeq = seq;
        }
    }

    /* ---------- 历史补拉（断线重连增量） ---------- */

    function loadHistory(afterSeq) {
        var url = '/api/orders/consult/sessions/' + sessionId + '/messages' +
            (afterSeq != null ? '?afterSeq=' + afterSeq : '');
        fetch(url)
            .then(function (resp) { return resp.json(); })
            .then(function (list) {
                if (!Array.isArray(list)) {
                    return;
                }
                list.forEach(function (m) {
                    var seq = m.seq;
                    if (seq != null && renderedSeqs[seq]) {
                        return; // 去重
                    }
                    if (seq != null) {
                        renderedSeqs[seq] = true;
                    }
                    renderMessageFrame(m.senderType, m.content);
                    markSeq(seq);
                });
            })
            .catch(function () { /* 历史拉取失败不阻断聊天 */ });
    }

    /* ---------- WebSocket ---------- */

    function sendFrame(obj) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(obj));
        }
    }

    function handleFrame(frame) {
        var type = frame.type;
        switch (type) {
            case 'joined':
                reconnectAttempt = 0; // 连接成功，重置退避
                tokenWarning.classList.add('hidden');
                if (frame.status) {
                    setStatusLabel(frame.status);
                }
                loadHistory(null); // 全量历史
                break;
            case 'message':
            case 'system':
                renderedSeqs[frame.seq] = true;
                renderMessageFrame(frame.senderType || 'SYSTEM', frame.content);
                markSeq(frame.seq);
                break;
            case 'prescription_issued':
                onPrescriptionIssued(frame.prescriptionNo, frame.validUntil);
                break;
            case 'session_ended':
                setStatusLabel(frame.status || '已结束');
                disableInput();
                appendTip('本次问诊已结束' + (frame.endReason ? '（' + frame.endReason + '）' : ''));
                break;
            case 'ping':
                sendFrame({type: 'ping'}); // 服务端按入站帧判活，收到 ping 立即回
                break;
            case 'pong':
            case 'ack':
                break;
            case 'error':
                onErrorFrame(frame);
                break;
        }
    }

    function onErrorFrame(frame) {
        if (frame.code === 'TOKEN_INVALID') {
            tokenInvalid = true;
            tokenWarning.classList.remove('hidden');
            disableInput();
            closeWs();
            return;
        }
        appendTip(frame.message || '连接异常');
    }

    function connect() {
        if (tokenInvalid) {
            return;
        }
        var url = wsUrl;
        try {
            ws = new WebSocket(url);
        } catch (e) {
            scheduleReconnect();
            return;
        }
        ws.onopen = function () {
            sendFrame({type: 'join', sessionId: Number(sessionId), token: token});
            startHeartbeat();
        };
        ws.onmessage = function (evt) {
            try {
                handleFrame(JSON.parse(evt.data));
            } catch (e) {
                /* 忽略非法帧 */
            }
        };
        ws.onclose = function () {
            stopHeartbeat();
            if (!tokenInvalid && !closed) {
                scheduleReconnect();
            }
        };
        ws.onerror = function () {
            try { ws.close(); } catch (e) { /* ignore */ }
        };
    }

    function scheduleReconnect() {
        var delay = Math.min(1000 * Math.pow(2, reconnectAttempt), 15000);
        reconnectAttempt++;
        appendTip('连接断开，' + Math.round(delay / 1000) + 's 后自动重连…');
        setTimeout(connect, delay);
    }

    function closeWs() {
        if (ws) {
            ws.onclose = null;
            try { ws.close(); } catch (e) { /* ignore */ }
            ws = null;
        }
    }

    /* ---------- 心跳 ---------- */

    function startHeartbeat() {
        stopHeartbeat();
        heartbeatTimer = setInterval(function () {
            sendFrame({type: 'ping'});
        }, 15000);
    }

    function stopHeartbeat() {
        if (heartbeatTimer) {
            clearInterval(heartbeatTimer);
            heartbeatTimer = null;
        }
    }

    /* ---------- 交互 ---------- */

    function sendText() {
        var content = msgInput.value.trim();
        if (!content) {
            return;
        }
        sendFrame({
            type: 'message',
            sessionId: Number(sessionId),
            clientMsgId: 'c' + Date.now() + Math.floor(Math.random() * 1000),
            content: content
        });
        msgInput.value = '';
        msgInput.focus();
    }

    function disableInput() {
        msgInput.disabled = true;
        sendBtn.disabled = true;
    }

    function setStatusLabel(status) {
        if (!statusEl) {
            return;
        }
        var labels = {WAITING: '等待医生接入', ONGOING: '进行中', PRESCRIPTION_ISSUED: '已开具处方', ENDED: '已结束', EXPIRED: '已过期'};
        statusEl.textContent = labels[status] || status;
    }

    /* ---------- 处方：医生开具 / 用户去下单 ---------- */

    function onPrescriptionIssued(prescriptionNo, validUntil) {
        appendTip('医生已开具处方（有效期至 ' + (validUntil || '') + '）');
        if (role === 'user') {
            // 取处方明细（数量须与处方一致）构建「去下单」跳转
            fetch('/api/orders/consult/prescriptions/' + prescriptionNo)
                .then(function (resp) { return resp.json(); })
                .then(function (p) {
                    var qty = 1;
                    if (p && Array.isArray(p.items) && p.items.length > 0 && p.items[0].quantity) {
                        qty = p.items[0].quantity;
                    }
                    goOrderBtn.href = '/order/confirm?productId=' + encodeURIComponent(productId) +
                        '&quantity=' + qty + '&prescriptionNo=' + encodeURIComponent(prescriptionNo);
                })
                .catch(function () {
                    goOrderBtn.href = '/order/confirm?productId=' + encodeURIComponent(productId) +
                        '&quantity=1&prescriptionNo=' + encodeURIComponent(prescriptionNo);
                });
            document.getElementById('prescription-no').textContent = prescriptionNo;
            prescriptionCard.classList.remove('hidden');
        }
    }

    function prescribe() {
        var dosage = document.getElementById('dosage').value.trim();
        var frequency = document.getElementById('frequency').value.trim();
        var qty = parseInt(document.getElementById('rx-quantity').value, 10);
        if (!dosage || !frequency || isNaN(qty) || qty <= 0) {
            appendTip('请填写完整用法、频率与数量');
            return;
        }
        var payload = {
            sessionId: Number(sessionId),
            items: [{
                productId: Number(productId),
                productName: productName,
                dosage: dosage,
                frequency: frequency,
                quantity: qty
            }],
            note: ''
        };
        fetch('/api/orders/consult/prescriptions', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        }).then(function (resp) {
            return resp.json().then(function (data) {
                if (!resp.ok) {
                    throw new Error(data.message || '开具处方失败');
                }
                appendTip('处方已开具：' + data.no);
            });
        }).catch(function (err) {
            appendTip(err.message || '开具处方失败');
        });
    }

    /* ---------- 绑定事件 ---------- */

    sendBtn.addEventListener('click', sendText);
    msgInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            sendText();
        }
    });
    endBtn.addEventListener('click', function () {
        sendFrame({type: 'end', sessionId: Number(sessionId)});
        appendTip('已发起结束问诊…');
    });
    if (role === 'doctor') {
        document.getElementById('prescribe-btn').addEventListener('click', prescribe);
    }

    connect();
})();
