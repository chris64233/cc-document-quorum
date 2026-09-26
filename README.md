# cc-document-quorum

管理文件版本、签署策略和生效记录。

## 主要业务规则

- **版本不可变**：每个版本的内容创建后不可修改；同一文件下版本号严格递增（数据库唯一约束
  `(document_id, version_number)` 保证）。
- **签署策略随版本定义**：创建版本时给出若干角色要求，每项包含角色名、所需最少同意人数
  （`requiredApprovals`）以及该角色是否持有否决权（`veto`）。
- **签署人资格**：签署人由唯一外部标识（`externalId`）和角色集合组成。每个人在同一版本只能
  作出一次决定（唯一约束 `(version_id, signer_id)`），且只能以自己实际拥有的、且属于该版本
  策略的角色签署同意或拒绝，否则返回 `ELIGIBILITY`（403）。
- **生效与否决**：某版本所有角色的同意人数均达到门槛时，该版本自动进入 `EFFECTIVE`；任何具有
  否决权角色的有效拒绝立即使版本进入 `REJECTED` 终态。`EFFECTIVE` / `REJECTED` / `SUPERSEDED`
  均为终态，终态后不再接受新决定（`TERMINAL_STATE`，409）。
- **事件幂等**：每次决定携带唯一外部事件号（`event_no` 唯一约束）。相同事件号且内容完全一致的
  重放返回首次结果（`replayed=true`，即使版本已进入终态）；事件号相同但内容不同返回
  `EVENT_CONFLICT`（409）。
- **单一生效版本**：同一文件同时最多一个 `EFFECTIVE` 版本。新版本生效时，原生效版本在同一事务
  内自动转为 `SUPERSEDED`。决定提交时对版本行和文件行加悲观写锁（先版本后文件的固定顺序），
  最后一票签署与生效/取代切换处于同一事务，并发最终签署不会重复生效或错误取代；唯一约束冲突
  在回滚后于新事务中被区分为重放、事件冲突或重复决定（`DUPLICATE_DECISION`，409）。

## API 概览

- `POST /api/documents` 创建文件；`POST /api/documents/{id}/versions` 创建版本及签署策略；
  `GET /api/documents/{id}/versions` 查询文件全部版本及状态。
- `POST /api/signers` 注册签署人及其角色集合。
- `POST /api/versions/{id}/decisions` 提交签署决定（`eventNo` / `signerExternalId` / `role` /
  `decision`）。
- `GET /api/versions/{id}/progress` 查询各角色门槛达成进度；
  `GET /api/versions/{id}/audit` 查询完整签署审计记录。
- 错误响应统一为 `{"code": ..., "message": ...}`，`code` 区分 `NOT_FOUND`、`VALIDATION`、
  `ELIGIBILITY`、`DUPLICATE_DECISION`、`EVENT_CONFLICT`、`TERMINAL_STATE`。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test
