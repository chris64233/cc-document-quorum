# cc-document-quorum

管理文件版本、签署策略和生效记录。

## 主要业务规则

- **版本不可变且递增**：文件每个版本的内容创建后不可修改；版本号在同一文件下从 1 开始严格递增（创建时锁定文件行并取 `max(versionNo) + 1`，并有 `(document_id, version_no)` 唯一约束兜底）。
- **签署策略随版本定义**：创建版本时按角色声明 `requiredApprovals`（至少多少名不同签署人同意）和 `vetoPower`（该角色一票拒绝即否决版本）。策略随版本固化，不可修改。
- **签署人资格**：签署人具有唯一 `externalId` 和角色集合。只能以自己拥有的、且包含在该版本策略中的角色签署；同一签署人对同一版本只能作出一次决定（`(version_id, signer_id)` 唯一约束）。
- **签署事件幂等**：每次决定携带唯一外部事件号 `event_id`（唯一约束，不可修改）。相同事件号相同内容的重放返回首次处理结果（HTTP 200，`replayed=true`）；相同事件号不同内容返回 `EVENT_CONFLICT`（409）。
- **状态机**：`PENDING → EFFECTIVE / REJECTED`，生效版本被新版本取代后变为 `SUPERSEDED`。所有角色同意数达到门槛时版本自动生效；任何具否决权角色的有效拒绝立即使版本进入 `REJECTED` 终态。终态后不再接受新决定（`TERMINAL_STATE`，409），但事件重放仍返回原结果。
- **唯一生效版本**：同一文件同时只有一个生效版本，由 `effective_version` 表（主键为文件 ID）保证。最后一票签署、旧版本置为 `SUPERSEDED`、新版本置为 `EFFECTIVE` 在同一事务内完成；通过版本行悲观锁（`PESSIMISTIC_WRITE`）串行化同一版本的决定、通过文件行悲观锁串行化生效切换，并发最终签署不会重复生效或错误取代。
- **审计与进度**：可查询文件全部版本及状态、每个版本按角色的达成进度（要求数/已同意数/是否满足）、以及完整的签署决定审计列表。
- **错误分类**：`ELIGIBILITY`（403，无资格）、`DUPLICATE_DECISION`（409，重复决定）、`EVENT_CONFLICT`（409，事件冲突）、`TERMINAL_STATE`（409，终态）、`NOT_FOUND` 系列（404）、`VALIDATION`（400）。

## API 概览

- `POST /api/documents` 创建文件；`GET /api/documents/{docCode}` 查询文件
- `POST /api/documents/{docCode}/versions` 创建版本（含签署策略）；`GET /api/documents/{docCode}/versions` 版本列表
- `GET /api/documents/{docCode}/versions/{versionNo}/progress` 策略达成进度
- `GET /api/documents/{docCode}/versions/{versionNo}/decisions` 签署审计
- `POST /api/documents/{docCode}/versions/{versionNo}/decisions` 提交签署决定（`eventId`、`signerExternalId`、`role`、`decision`）
- `POST /api/signers` 注册签署人（含角色集合）；`GET /api/signers/{externalId}` 查询签署人

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
