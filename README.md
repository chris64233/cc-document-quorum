# cc-document-quorum

管理文件版本、签署策略和生效记录，支持签署过程中对审批策略的受控修订。

## 主要业务规则

- **版本不可变且递增**：文件每个版本的内容创建后不可修改；版本号在同一文件下从 1 开始严格递增（创建时锁定文件行并取 `max(versionNo) + 1`，并有 `(document_id, version_no)` 唯一约束兜底）。
- **签署策略版本化**：每个文件版本下策略也有版本（`policyNo`，从 1 开始）。创建文件版本时的初始策略即 1 号策略版本（直接 `ACTIVE`）。策略要求按角色声明 `requiredApprovals`（至少多少名不同签署人同意）和 `vetoPower`（该角色一票拒绝即否决版本）。任一时刻同一文件版本至多一个 `ACTIVE` 策略版本，签署资格与法定人数都按它计算。
- **签署人资格**：签署人具有唯一 `externalId` 和角色集合（可更新）。只能以自己拥有的、且包含在当前 `ACTIVE` 策略中的角色签署；同一签署人对同一版本只能作出一次决定（`(version_id, signer_id)` 唯一约束）。
- **签署事件幂等**：每次决定携带唯一外部事件号 `event_id`（唯一约束，不可修改）。相同事件号相同内容的重放返回首次处理结果（HTTP 200，`replayed=true`）；相同事件号不同内容返回 `EVENT_CONFLICT`（409）。
- **状态机**：`PENDING → EFFECTIVE / REJECTED`，生效版本被更新版本取代后变为 `SUPERSEDED`。所有角色同意数达到门槛时版本自动生效；任何具否决权角色的有效拒绝立即使版本进入 `REJECTED` 终态。终态后不再接受新决定（`TERMINAL_STATE`，409），但事件重放仍返回原结果。
- **唯一生效版本**：同一文件同时只有一个生效版本，由 `effective_version` 表（主键为文件 ID）保证。最后一票签署、旧版本置为 `SUPERSEDED`、新版本置为 `EFFECTIVE` 在同一事务内完成；通过版本行悲观锁（`PESSIMISTIC_WRITE`）串行化同一版本的决定、通过文件行悲观锁串行化生效切换。**防取代守卫**：若一个较新的文件版本已经生效，较旧版本之后达成法定人数不会错误取代它，而是直接置为 `SUPERSEDED`（生效指针只向更新的版本移动）。
- **审计与进度**：可查询文件全部版本及状态、每个版本按当前 `ACTIVE` 策略计算的达成进度（要求数/已同意数/是否满足）、以及完整的签署决定审计列表（含决定作出时的策略版本号与作废标记）。
- **错误分类**：`ELIGIBILITY`（403，无资格）、`DUPLICATE_DECISION`（409，重复决定）、`EVENT_CONFLICT`（409，事件冲突）、`AMENDMENT_CONFLICT`（409，修订号冲突）、`TERMINAL_STATE`（409，终态）、`NOT_FOUND` 系列（404）、`VALIDATION`（400）。

## 策略修订

签署进行中（版本处于 `PENDING`）可以对审批策略做受控修订：

- **修订治理随原策略固化**：创建文件版本时可通过 `amendment: {role, requiredApprovals}` 指定修订管理角色与修订门槛。未指定的版本不允许修订。修订本身不能修改这套治理规则。
- **创建修订即冻结**：`POST .../amendments` 创建修订时冻结所属文件版本与当时的 `ACTIVE` 策略版本号（`basePolicyNo`），并把完整替换策略保存为新的 `PENDING` 策略版本。修订是整体替换而非增量补丁，因此多份修订并存时先生效者先切换，后生效者整体覆盖，语义明确。
- **终态不可修订**：已 `REJECTED`/`EFFECTIVE`/`SUPERSEDED` 的版本不能创建修订（`TERMINAL_STATE`）——已被明确拒绝的版本不能通过降低门槛复活生效。
- **修订投票**：只有原策略指定的管理角色可以投票，同一签署人对同一修订只能投一次。管理角色中不同签署人的同意数达到修订门槛时修订生效；拒绝票仅记录审计，不阻止后续同意达成门槛。
- **修订号与事件号幂等**：`amendmentNo` 由调用方给定、在文件版本内唯一，相同修订号相同策略内容的重复创建返回首次结果（`replayed=true`），内容不同返回 `AMENDMENT_CONFLICT`；修订投票与普通签署共用 `eventId` 幂等语义（`EVENT_CONFLICT`）。
- **决定沿用**：修订生效瞬间对彼时已有决定逐条评估并落库（`carry_over_record`，写入后不再变化）：
  - `APPROVE` 且角色仍在新策略中、且签署人当前仍具备该角色 → `CARRIED_OVER`，计入新策略进度；
  - 角色不在新策略中（`ROLE_NOT_IN_NEW_POLICY`）、签署人已不再具备该角色（`SIGNER_LOST_ROLE`）、拒绝票（`REJECT_NOT_CARRIED`）、此前已作废（`ALREADY_VOIDED`）→ `VOIDED`，保留审计但不计入进度（决定上的 `voidedPolicyNo` 记录其失效于哪个策略版本）。
- **生效重估**：修订生效后立刻按新策略与沿用后的决定重新评估法定人数，达成即生效；评估、策略切换（旧 `ACTIVE` → `SUPERSEDED`，新策略 → `ACTIVE`）与版本生效在同一事务内完成。
- **并发安全**：修订最后一票、普通签署最后一票、新文件版本创建可能并发。普通签署、修订创建、修订投票都先锁定同一个文件版本行的悲观写锁，因此资格判断、法定人数计算、策略切换与版本生效全部串行，一个版本不可能同时按两套策略生效；生效切换再取文件行锁并带防取代守卫，不会错误取代较新的文件版本。锁顺序固定为版本行 → 文件行，新文件版本创建只取文件行锁，无死锁环。

## 查询能力

- **策略版本差异**：`GET .../versions/{versionNo}/policies/diff?from=1&to=2` 按角色对比两个策略版本，返回 `ADDED`/`REMOVED`/`CHANGED`（门槛与否决规则变化）。
- **决定沿用明细**：`GET .../amendments/{amendmentNo}/carry-over` 返回修订生效时每条决定的沿用/作废结果与原因。
- **各版本达成进度**：`GET .../versions/{versionNo}/progress` 按当前 `ACTIVE` 策略版本返回各角色进度（含 `policyNo`）。
- **生效依据**：`GET .../versions/{versionNo}/effective-basis` 返回版本状态、据以生效的策略版本号、触发来源（`INITIAL_POLICY_QUORUM` / `AMENDED_POLICY_QUORUM` / `NOT_EFFECTIVE`）、触发修订号与生效时间。

## API 概览

- `POST /api/documents` 创建文件；`GET /api/documents/{docCode}` 查询文件
- `POST /api/documents/{docCode}/versions` 创建版本（含签署策略，可选 `amendment` 修订治理）；`GET /api/documents/{docCode}/versions` 版本列表
- `GET /api/documents/{docCode}/versions/{versionNo}/progress` 策略达成进度
- `GET /api/documents/{docCode}/versions/{versionNo}/decisions` 签署审计
- `POST /api/documents/{docCode}/versions/{versionNo}/decisions` 提交签署决定（`eventId`、`signerExternalId`、`role`、`decision`）
- `GET /api/documents/{docCode}/versions/{versionNo}/policies` 策略版本历史
- `GET /api/documents/{docCode}/versions/{versionNo}/policies/diff?from=&to=` 策略版本差异
- `GET /api/documents/{docCode}/versions/{versionNo}/effective-basis` 生效依据
- `POST /api/documents/{docCode}/versions/{versionNo}/amendments` 创建策略修订（`amendmentNo` + 完整替换策略）
- `GET /api/documents/{docCode}/versions/{versionNo}/amendments` / `.../amendments/{amendmentNo}` 修订列表/详情
- `POST /api/documents/{docCode}/versions/{versionNo}/amendments/{amendmentNo}/decisions` 修订投票（`eventId`、`signerExternalId`、`decision`）
- `GET /api/documents/{docCode}/versions/{versionNo}/amendments/{amendmentNo}/carry-over` 决定沿用明细
- `POST /api/signers` 注册签署人（含角色集合）；`GET /api/signers/{externalId}` 查询签署人；`PUT /api/signers/{externalId}/roles` 更新角色集合

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
