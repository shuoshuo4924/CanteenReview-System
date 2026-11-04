/**
 * AI 对话面板的公共交互（首页 / 建议箱共用）。
 * 页面只要提供一个 id="aiPanel" 的容器，接口地址写在容器的 data-endpoint 上：
 *   <div class="ai-panel" id="aiPanel" data-endpoint="/ai/chat">
 * 没有这个容器（比如非管理员页面）时脚本直接退出，不会报错。
 */
(function () {
    var panel = document.getElementById('aiPanel');
    if (!panel) {
        return;
    }

    var list = document.getElementById('aiMessages');
    var chips = document.getElementById('aiChips');
    var form = document.getElementById('aiForm');
    var input = document.getElementById('aiInput');
    var sendBtn = document.getElementById('aiSend');
    var endpoint = panel.getAttribute('data-endpoint') || '/ai/chat';
    var history = [];   // 对话历史，每次请求带给后端

    function toBottom() {
        list.scrollTop = list.scrollHeight;
    }

    // role: 'bot' | 'me'
    function bubble(role, text) {
        var row = document.createElement('div');
        row.className = 'ai-msg-row ' + role;

        if (role === 'bot') {
            var av = document.createElement('span');
            av.className = 'ai-avatar sm';
            av.textContent = 'AI';
            row.appendChild(av);
        }

        var msg = document.createElement('div');
        msg.className = 'ai-msg ' + role;
        msg.textContent = text;   // 用 textContent，AI 回复里的 HTML 不会被执行
        row.appendChild(msg);

        list.appendChild(row);
        toBottom();
        return row;
    }

    // 三个跳动的点，等待回复时显示
    function typing() {
        var row = document.createElement('div');
        row.className = 'ai-msg-row bot';

        var av = document.createElement('span');
        av.className = 'ai-avatar sm';
        av.textContent = 'AI';

        var box = document.createElement('div');
        box.className = 'ai-msg bot ai-typing';
        box.innerHTML = '<span></span><span></span><span></span>';

        row.appendChild(av);
        row.appendChild(box);
        list.appendChild(row);
        toBottom();
        return row;
    }

    function send(text) {
        text = (text || '').trim();
        if (!text || sendBtn.disabled) {
            return;
        }

        // 第一次发消息后收起快捷问题
        if (chips && chips.parentNode) {
            chips.parentNode.removeChild(chips);
        }

        bubble('me', text);
        history.push({ role: 'user', content: text });
        input.value = '';
        sendBtn.disabled = true;

        var waiting = typing();

        fetch(endpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(history)
        })
            .then(function (res) {
                return res.json();
            })
            .then(function (data) {
                if (waiting.parentNode) {
                    waiting.parentNode.removeChild(waiting);
                }
                var reply = (data && data.reply) ? data.reply : '我没听清，再说一次？';
                bubble('bot', reply);
                history.push({ role: 'assistant', content: reply });
            })
            .catch(function () {
                if (waiting.parentNode) {
                    waiting.parentNode.removeChild(waiting);
                }
                bubble('bot', '网络开小差了，稍后再试一次吧');
            })
            .finally(function () {
                sendBtn.disabled = false;
            });
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        send(input.value);
    });

    if (chips) {
        chips.addEventListener('click', function (e) {
            var chip = e.target.closest('.ai-chip');
            if (chip) {
                send(chip.textContent);
            }
        });
    }
})();
