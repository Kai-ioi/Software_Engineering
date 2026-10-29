# 高校课程项目协作与评审平台

当前版本：0.1.0，基础框架。需求依据为上级目录的 `v4（1）.md`，图文冲突时以正文为准。

## 已经可以使用

- 登录、退出、会话保持、当前用户查询、CSRF 保护、BCrypt 密码哈希。
- 我的课程、课程详情、按课程独立的教师/学生/助教身份。
- 后端课程权限校验、教师管理信息、仅教师可查的课程操作记录。
- 私有待提交材料上传及本人下载，50 MB 单文件限制、格式白名单、SHA-256。
- 统一页面布局、菜单、请求封装、错误反馈、登录失效跳转。
- Flyway 数据库迁移、演示数据、PostgreSQL 配置和 Docker Compose 部署文件。

项目、组队、迭代、正式成果版本、互评、评分成绩及通知的业务代码尚未接入。菜单页明确显示未开放，不生成虚构项目、成绩或待办。个人材料上传不构成正式成果提交；200 MB 版本总量将在成果版本模块校验。

## 本机快速启动（Windows PowerShell）

需要 Java 21、Maven 3.9+、Node.js 18.19+（建议新环境使用22）。首次启动下载本项目依赖，不修改全局工具。

在项目根目录打开两个终端：

终端一：
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\backend.ps1
```

终端二：
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\frontend.ps1
```

打开 **http://127.0.0.1:5173**。后端地址为 http://127.0.0.1:8080。使用同一个主机名访问网站，不混用 localhost 与127.0.0.1。

默认 `demo` 模式使用 H2 文件数据库，首次运行自动创建演示账号。数据库和材料保存在 `backend/.runtime/`，重启保留。

| 账号 | 身份及用途 |
|---|---|
| teacher01 | 软件工程课教师、项目实践课学生，验证跨课程身份 |
| student01 | 软件工程课学生 |
| assistant01 | 软件工程课助教 |
| student02 | 项目实践课学生，不能进入软件工程课 |
| admin01 | 平台管理员，默认无课程权限 |

演示账号初始密码均为 `Demo@2026!`。可在首次创建数据前设置环境变量 `DEMO_PASSWORD`；已有账号不会因环境变量改变而重置。演示账号只用于本地开发，生产配置不执行演示数据初始化。

## 浏览器数据库管理

后端运行时，直接打开 **http://127.0.0.1:8080/h2-console/**，不需要停止后端。该入口仅在本地demo模式启用，PostgreSQL模式关闭。

登录参数：驱动 `org.h2.Driver`，用户名 `sa`，密码留空，JDBC URL复制下方完整内容（覆盖页面自带的测试地址）：

```text
jdbc:h2:file:./.runtime/demo-db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;IFEXISTS=TRUE
```

点击Connect后，左侧可展开数据表，右侧输入SQL并点击Run。`IFEXISTS=TRUE`避免输入错误时新建空数据库。课程账号密码与数据库登录参数分别使用；本地控制台可直接修改开发数据库。

详细步骤和常用查询见 [数据库管理说明](docs/数据库管理说明.md)。

## PostgreSQL 模式

Docker 已安装时，先复制 `.env.example` 为 `.env` 并设置数据库密码：

```powershell
Copy-Item .env.example .env
docker compose --env-file .env -f deploy/compose.yml up -d db
$env:DB_PASSWORD='与.env中相同的密码'
.\scripts\backend.ps1 -Database postgres
```

已有 PostgreSQL 时，创建空数据库和应用账号，设置 `DB_URL`、`DB_USER`、`DB_PASSWORD` 后使用同一启动命令。Flyway 自动建表，JPA 只校验结构。

**空 PostgreSQL 数据库没有默认账号。** 若仅用于本地联调，可显式使用 `-Database postgres -SeedDemo`，同时启用演示初始化，勿用于正式环境。

全栈容器部署：
```powershell
docker compose --env-file .env -f deploy/compose.yml up --build -d
```
访问 http://127.0.0.1:8088。默认正式配置不注入演示账号，后续通过账号管理模块配置账号。部署到公网时由入口配置HTTPS，并设置 `SESSION_COOKIE_SECURE=true`。数据库和材料分别使用持久化卷。

## 构建与验证

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

前端产物：`frontend/dist/`。后端产物：`backend/target/school-system-0.1.0.jar`。缓存位于 `.cache/`，不提交仓库。

登录后可在后端 http://127.0.0.1:8080/swagger-ui/index.html 查看接口说明（仅演示模式默认启用）；登录请求使用表单，先获取 `/api/auth/csrf` 并按返回头名携带 token。详细约定见 `docs/接口约定.md`。

## 下一步

先接入项目发布和组队，再开发迭代任务、正式提交与版本、互评检查、评分成绩。详见 `docs/框架与后续接入.md`。
