# cc-document-quorum

管理文件版本、签署策略、策略受控修订和生效记录。

## 主要业务规则

### 文件版本与签署

- **版本不可变且递增**：文件每个版本的内容创建后不可修改；版本号在同一文件下从 1 开始严格递增（创建时锁定文件行并取 `max(versionNo) + 1`，并有 `(document_id, version_no)` 唯一约束兜底）。
- **签署策略随版本定义**：创建版本时按角色声明 `requiredApprovals`（至少多少名不同签署人同意）和 `vetoPower`（该角色一票拒绝即否决版本）。初始策略为策略版本 1，之后只能通过受控修订生成新策略版本，历史策略版本完整保留。
- **管理角色**：创建版本时可选声明 `amendmentRole` 与 `amendmentThreshold`。只有持有管理角色的签署人可以提出/批准修订；管理配置随策略版本固化，修订生效时原样继承。
- **签署人资格**：签署人具有唯一 `externalId` 和角色集合。只能以自己拥有的、且包含在该版本**当前策略**中的角色签署；同一签署人对同一版本只能作出一次决定（`(version_id, signer_id)` 唯一约束）。
- **签署事件幂等**：每次决定携带唯一外部事件号 `event_id`（唯一约束，不可修改）。相同事件号相同内容的重放返回首次处理结果（HTTP 200，`replayed=true`）；相同事件号不同内容返回 `EVENT_CONFLICT`（409）。
- **状态机**：`PENDING → EFFECTIVE / REJECTED / SUPERSEDED`。所有角色同意数达到当前策略门槛时版本自动生效；任何具否决权角色的有效拒绝立即使版本进入 `REJECTED` 终态。终态后不再接受新决定（`TERMINAL_STATE`，409），但事件重放仍返回原结果。
- **唯一生效版本**：同一文件同时只有一个生效版本，由 `effective_version` 表（主键为文件 ID）保证。最后一票签署、旧版本置为 `SUPERSEDED`、新版本置为 `EFFECTIVE` 在同一事务内完成；通过版本行悲观锁（`PESSIMISTIC_WRITE`）串行化同一版本的决定与修订、通过文件行悲观锁串行化生效切换。较旧的版本即使后来才达成法定人数，也只会进入 `SUPERSEDED`，不会错误取代已经生效的较新版本。

### 策略受控修订

- **创建即冻结**：创建修订时冻结当前文件版本、基线策略版本号（`basePolicyVersionNo`）、管理角色、修订门槛和已有决定。修订内容为一套完整的新角色策略（可调整角色门槛、否决规则、增删角色）。
- **不能复活已拒绝版本**：只有 `PENDING` 版本可以创建修订。已被明确拒绝（`REJECTED`）或已生效/被取代的版本不能通过降低门槛的修订重新生效（`TERMINAL_STATE`，409）。
- **独立修订门槛**：修订需要基线策略中指定的管理角色（`amendmentRole`）的签署人投票，同意数达到独立的修订门槛（`amendmentThreshold`）才生效；同一签署人对同一修订只能投一票，投票事件号 `event_id` 全局唯一保证幂等。
- **基线失效**：一个修订生效后，同版本其余基于旧策略的未决修订自动变为 `STALE`（终态），不能再投票或生效，避免一个版本按两套互不相干的策略演进。
- **生效与决定沿用**：修订达到门槛时，在版本行悲观锁内原子完成——
  1. 生成下一代策略版本（版本号严格递增，`policy_version_meta` 冻结其来源修订号与管理配置）；
  2. 重算每个已有决定的 `counted` 标记：只有**签署人在新策略下仍具备相同角色**的**同意**才沿用；角色被新策略移除、签署人已失去该角色、以及所有拒绝，一律保留审计但不计入进度（`ROLE_NOT_IN_CURRENT_POLICY` / `SIGNER_ROLE_LOST` / `REJECT_NOT_COUNTED`）；
  3. 立即按新策略重算法定人数：若沿用的同意已经满足新门槛，版本在同一事务内直接生效，生效依据记录为新策略版本。
- **明确策略版本的并发保证**：策略修订最后一票、普通签署最后一票都先取版本行悲观锁再计算，因此一个版本的生效只按某一个明确策略版本完成；后到的操作会看到终态并收到 `TERMINAL_STATE`，不会让同一版本按两套策略各生效一次。生效依据（`effective_policy_version_no`、生效时间、当时的策略与计入决定）随版本固化。
- **幂等**：修订号 `(version_id, amendment_no)` 唯一，相同修订号相同内容的并发创建/重放返回同一修订（`replayed=true`），不同内容返回 `EVENT_CONFLICT`；投票事件号语义与签署决定一致。

### 审计与查询

- 版本列表（含当前策略版本号与当前策略）、版本详情、按角色的达成进度（只统计 `counted` 决定，标注当前策略版本号）、完整签署决定审计（含决定作出时的策略版本号与是否仍计入进度）。
- 策略版本列表（标注哪一代当前生效、来源修订号、管理配置）、任意两代策略版本的差异（`ADDED` / `REMOVED` / `MODIFIED` / `UNCHANGED`，含门槛与否决权新旧值）。
- 决定沿用明细（每条决定在当前策略下是否沿用及原因）、生效版本的生效依据（生效策略版本、生效时间、当时策略要求、实际计入的同意）。
- **错误分类**：`ELIGIBILITY`（403）、`DUPLICATE_DECISION`（409）、`EVENT_CONFLICT`（409）、`TERMINAL_STATE`（409）、`NOT_FOUND` 系列（404，含 `AMENDMENT_NOT_FOUND`、`POLICY_VERSION_NOT_FOUND`、`VERSION_NOT_EFFECTIVE`）、`VALIDATION`（400）。

## API 概览

- `POST /api/documents` 创建文件；`GET /api/documents/{docCode}` 查询文件
- `POST /api/documents/{docCode}/versions` 创建版本（`content`、`policy`，可选 `amendmentRole`、`amendmentThreshold`）；`GET /api/documents/{docCode}/versions` 版本列表
- `GET /api/documents/{docCode}/versions/{versionNo}/progress` 各角色达成进度（按当前策略版本）
- `GET /api/documents/{docCode}/versions/{versionNo}/decisions` 签署审计（含策略版本与计票标记）
- `GET /api/documents/{docCode}/versions/{versionNo}/decisions/carry-over` 决定沿用明细
- `GET /api/documents/{docCode}/versions/{versionNo}/policy` 策略版本列表
- `GET /api/documents/{docCode}/versions/{versionNo}/policy/diff?from=1&to=2` 策略版本差异
- `GET /api/documents/{docCode}/versions/{versionNo}/effectiveness` 生效依据（未生效返回 404）
- `POST /api/documents/{docCode}/versions/{versionNo}/decisions` 提交签署决定（`eventId`、`signerExternalId`、`role`、`decision`）
- `POST /api/documents/{docCode}/versions/{versionNo}/amendments` 创建修订（`amendmentNo`、新 `policy`）；`GET .../amendments` 修订列表；`GET .../amendments/{amendmentNo}` 修订详情（含投票）
- `POST /api/documents/{docCode}/versions/{versionNo}/amendments/{amendmentNo}/votes` 管理角色投票（`eventId`、`signerExternalId`、`decision`）
- `POST /api/signers` 注册签署人（含角色集合）；`GET /api/signers/{externalId}` 查询签署人

## 数据模型要点

- `document_version` 记录当前策略版本号 `current_policy_version_no`、生效策略版本号 `effective_policy_version_no` 与生效时间。
- `policy_version_meta`（每代策略的管理配置与来源修订）与 `policy_requirement`（按 `(version_id, policy_version_no, role)` 保存各代策略内容）共同构成不可变的策略版本链。
- `policy_amendment` / `amendment_requirement` / `amendment_vote` 保存修订、修订内容与管理投票。
- `sign_decision` 记录决定作出时的策略版本号 `policy_version_no` 和是否计入当前进度 `counted`（修订生效时重算，审计行不删除）。

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

测试包含服务层规则测试（`SigningServiceTest`、`PolicyAmendmentTest`）、HTTP 集成测试
（`ApiIntegrationTest`、`AmendmentApiIntegrationTest`）以及并发测试
（`ConcurrencyTest`、`AmendmentConcurrencyTest`，覆盖修订最后一票/普通签署最后一票/新版本创建并发、
幂等重放等场景）。
