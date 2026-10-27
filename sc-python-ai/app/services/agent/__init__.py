"""Single Agent Router（TASK-706）。

模块边界：

* 只做意图识别与（可选）DDL 提取，不执行工具、不写数据库/缓存/队列；
* 工具执行在 Java 侧按用户身份与权限调用 `ddl` / `knowledge` 等领域服务；
* `FORCE_DDL_EXTRACT` 仍走 `services.ddl.extractor`，不经过路由层。
"""
