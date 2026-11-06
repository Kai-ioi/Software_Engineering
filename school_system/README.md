# 高校课程项目协作与评审平台

当前版本：课程协作功能整合版（2026-11-06）。课程内固定团队与多项目选题按最新确认规则执行。

## 当前功能

- 登录、退出、会话、课程角色权限与平台基础资料管理。
- 课程项目池；课程内3—5人固定组队、邀请确认、组长统一选题，默认每队完成2个不同项目。
- 各项目独立的团队迭代、任务分工、里程碑及进度记录。
- 材料准备、负责人正式提交、不可覆盖的版本历史、回执、最终指定、截止冻结及授权补交；单文件50 MB、每版文件总量200 MB。
- 助教授权检查、整改复查；项目范围内的互评分配、反馈及复核重评。
- 教师评分、计分名单核对、按项目发布、成绩更正及课程CSV/XLSX导出。
- 项目工作台、上下文一致的导航、站内通知、过程记录、数据留存及备份恢复工具。

**当前互评仍按项目组织；课程内跨项目互评尚待调整。** 已有历史项目团队与成果保留，新课程团队从“组队与选题”入口创建并接受邀请。

- [课程组队与选题操作说明](docs/课程组队与选题操作说明.md)
- [四类用户操作与验收用例](docs/四类用户操作与验收用例.md)（其中早期按项目建队的步骤，以最新组队与选题说明为准）
- [课程组队验证记录](docs/verification/course-teams/核对记录.md)
- [页面与业务衔接验证记录](docs/verification/delivery-ui/本轮核对记录.md)

## 从已有版本更新

先保存自己的未提交修改，停止本机前后端，然后在仓库中执行：

```powershell
git switch main
git pull --ff-only origin main
```

再按下方命令启动。后端会通过Flyway自动升级至V14，保留本机已有数据库和成果文件。代码仓库不包含每个人的本地数据库；在网页创建的团队、项目和提交不会随Git同步。

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
| student01 | 石洁燃，软件工程课学生，可作为组长 |
| student03 | 未宁夏，软件工程课学生 |
| student05、student06 | 陈爽、汪欣芸，软件工程课学生 |
| student04 | 刘洋，软件工程课学生 |
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

## 后续工作

按最新规则调整课程内跨项目互评，并继续按用户用例检查完整流程。`docs/框架与后续接入.md` 保留为早期框架设计记录。


### 项目与团队共享数据（2026-11-03联调修复）

项目、团队、成员统一使用 `course_project`、`project_team`、`project_team_member`。项目管理发布的要求直接供迭代任务引用；成员审批、退出、负责人交接直接影响任务及成果权限。项目状态统一为草稿、已发布、进行中、已结束、已终止、已归档。

项目评分维度和教师/互评权重在项目页配置，发布时生成供评分模块使用的版本快照；正式互评或评分开始后冻结配置。评分页面按所选团队的项目标准加载。成绩发布须完成被评版本、教师评分、互评/复核及名单核对；更正重算成绩并保留旧结果、名单和计算快照，导出采用最新成绩版本。

升级前停止后端并备份 `backend/.runtime/demo-db.mv.db` 及文件目录。正常启动将自动执行V9/V10：原A模块表保留为 `legacy_*`，通过映射表把原项目、团队、成员迁入共享数据；既有成果、评审和成绩引用不变。禁止通过删除数据库、修改已执行迁移脚本或手工替换ID来升级。旧演示项目的评分历史沿用原标准，新建项目按发布的项目标准评分。

回归证据：`docs/verification/shared-workflow/result.json`。测试：`CrossModuleAuditTest`、`SharedWorkflowIntegrationTest`、`SharedDataMigrationTest`。

### 本地 H2 与附件备份

先停止后端，确认没有写入，再从项目目录执行下列命令。按实际运行目录修改路径。备份存放在运行目录之外，恢复使用全新的空目标路径。

```powershell
python scripts/demo_backup.py backup backend/.runtime D:/backup/school-demo.zip
python scripts/demo_backup.py restore D:/backup/recovered-runtime D:/backup/school-demo.zip
```

恢复时先验证解包后的数据库能打开，并核对成果、评审、成绩关联及附件下载，再将应用配置指向恢复目录。脚本校验所有归档文件的 SHA-256。自定义附件存放路径需要在备份前随数据库一致性停机，并将附件目录纳入备份。

### 课程留存管理

由管理员在「课程数据留存」登记课程结束。部署时将 `RETENTION_APPROVER` 设为业务审批人的登录名。满两年且无未结争议的课程可申请批准清理。执行账号须是另一位平台管理员。系统先展示清理范围，执行后记录审批、清理及附件处理结果。请先备份再处理真实课程。
