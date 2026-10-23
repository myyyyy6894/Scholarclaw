"""受控模型网关层（`02` §5.1 第 4 层 `SsrfSafeModelGateway`）。

包含：

* `transport.py`：出站客户端构造、超时、退避重试、失败分类、禁止跟随重定向、
  日志用 URL 脱敏；
* `ssrf_guard.py`：每次出站前的同步/异步 DNS 校验（硬拒绝 + 本地白名单 + 全部解析结果检查）；
* `ssrf_safe_model_gateway.py`：`SsrfSafeModelGateway` 本体、`post_json` / `stream_chat`
  两条受控出站路径、2 MiB 流式上限与显式重试策略；
* `model_probe.py`：CHAT / EMBEDDING / BOTH 探测、真实维度与响应结构校验。

所有外部模型出站必须经 :class:`SsrfSafeModelGateway`，不得另建裸 `httpx` 客户端。
"""
