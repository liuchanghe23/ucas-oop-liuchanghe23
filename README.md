# 面向对象程序设计课程仓库

保留 Assignment1、课程资料和期中汇报；大作业选择 **4.2 基于异构系统的缺陷迁移**。

- `course-project/platform/`：课程平台源码，基于上游 **main** 的 `e000f5c0e07d929be06f81b4616841690ad513d3`，未引入 dev/iscas。
- `course-project/docs/`：4.2 范围、现状与环境说明。
- `course-project/scripts/`：WSL 安装、构建和本地启动脚本。
- `course-project/infra/`：本地数据库与 Redis 配置。
- `期中汇报/`：已有报告初稿、架构分析与 PPT。

先阅读 [开发环境](course-project/docs/开发环境.md)。4.2 第一阶段已新增独立服务 [svc-defect-transfer](course-project/platform/svc-defect-transfer/README.md)：模板导入、带证据的画像、T10/T11 条件匹配和实验计划生成。真实故障执行与动态判定仍待后续实现。

在 WSL 中可用 `bash course-project/scripts/dev.sh test-defect` 测试，启动 `svc-defect-transfer` 后运行 `bash course-project/scripts/dev.sh demo-defect` 验证接口。
