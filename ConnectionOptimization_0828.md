# Connection Optimization Handoff — 2026-08-28

이 문서는 다음 AI가 MasterNote의 LAN/Telegram 통신을 적대적으로 검증하고 최적화할 때 지켜야 할 경계와 현재 기준 동작을 정리한 인수인계 문서다.

기준 브랜치는 `feature/telegram-page-delta-sync`, 복구 기준 태그는 `connection-optimization-baseline-20260828`이다. 최적화 전후 비교와 롤백은 이 태그를 기준으로 한다.

## 1. 현재 구조와 소유권

페이지 데이터에는 동시에 한 전송 경로만 소유권을 가진다.

```text
학생의 append-only 필기 로그
        |
        +-- LAN READY --------> 구독 페이지 operation/PAGE_STATE 전송
        |
        +-- LAN 없음 ---------> Telegram manifest
                                  -> teacher request
                                  -> delta 또는 checkpoint 조각
                                  -> application ACK

선생 첨삭 초안 -- 사용자가 '반영' --> 불변 publication artifact
                                      -> 현재 소유 전송 경로
                                      -> 정확한 교재/페이지/회차에 적용
```

- LAN 서비스는 프로세스 전체에서 한 세션만 소유한다. 학생은 `STUDENT_SERVER`, 선생폰은 `TEACHER_CLIENT`다.
- LAN 소켓이 연결 중이거나 페이지 catch-up 중이면 `LAN_GRACE`, 완전히 준비되면 `LAN_OWNS`, 명확히 끊기면 Telegram이 소유한다.
- `LAN_GRACE` 동안 Telegram 페이지 문서는 적용하지 않고 암호화 inbox에 보류한다. `LAN_OWNS`가 되면 해당 Telegram 페이지 문서는 폐기한다.
- 텍스트 채팅과 연결 제어는 페이지 데이터 소유권과 별개다. 페이지 manifest/request/annotation만 이 단일 소유권 규칙을 따른다.
- 이전의 렌더링 이미지 기반 `PAGE_SNAPSHOT`, `TEACHER_FEEDBACK`, `REMOTE_GRADE` peer 경로는 퇴역했다. 오래된 Telegram 문서가 최신 델타 상태를 덮지 못하도록 의도적으로 소비·폐기한다.

핵심 파일:

- `app/src/main/kotlin/com/studyink/app/MasterNoteRemoteReviewCoordinator.kt`
- `app/src/main/kotlin/com/studyink/app/RemotePageSyncController.kt`
- `app/src/main/kotlin/com/studyink/app/RemotePageSyncStore.kt`
- `monitor/core/src/main/kotlin/com/studyink/monitor/core/HybridLinkStateMachine.kt`
- `monitor/core/src/main/kotlin/com/studyink/monitor/core/RemoteReviewCodec.kt`
- `monitor/core/src/main/kotlin/com/studyink/monitor/core/RemoteReviewProtocol.kt`
- `monitor/telegram/src/main/kotlin/com/studyink/monitor/telegram/RemoteMonitorGateway.kt`
- `monitor/telegram/src/main/kotlin/com/studyink/monitor/telegram/TelegramOutbox.kt`
- `monitor/telegram/src/main/kotlin/com/studyink/monitor/telegram/TelegramPeerInbox.kt`
- `sync/lan/src/main/kotlin/com/studyink/sync/lan/LanSyncService.kt`
- `sync/lan/src/main/kotlin/com/studyink/sync/lan/LanSyncProtocol.kt`

## 2. 최적화 작업에서 건드리지 말아야 할 빨간선

아래 항목은 단순 성능 개선으로 변경하면 안 된다. 변경이 꼭 필요하면 양쪽 기기 동시 마이그레이션, 저장 형식 호환성, 실패 주입 테스트를 별도 작업으로 먼저 설계해야 한다.

### 2.1 append-only 필기와 불변 스냅샷

- `operations.log`는 필기 원본이며 append-only다. `checkpoint.json`은 로딩 가속용이지 진실의 원본을 대체하지 않는다.
- 파싱 실패나 손상 파일을 빈 페이지로 덮어쓰면 안 된다. 기존 격리 및 복구 동작을 유지한다.
- `PageOperationLogStore.get(context)` 싱글턴을 Reader와 LAN 서비스가 함께 써야 한다. 서로 다른 인메모리 page index를 만들면 수신 필기가 Reader에서 보이지 않는다.
- `AnnotationSnapshot`/`StrokeAsset`의 불변성을 전제로 짧은 락 안에서 스냅샷을 잡고, 큰 canonical JSON·SHA·checkpoint 인코딩은 락 밖에서 수행한다.
- 학생 레이어 digest 캐시는 반드시 `(bookId, pageNumber, revision)`에 묶여야 하며 restore 시 비워야 한다. revision 없는 캐시나 영구 캐시는 금지한다.

관련 코드: `annotation/storage/.../PageOperationLogStore.kt`

### 2.2 원격 대상의 정확한 신원

원격 첨삭이나 필기를 다음 중 일부만으로 추측해서 적용하면 안 된다.

- `pairId`
- `syncGeneration`
- `workbookToken`
- PDF `contentSha256`
- 로컬 `bookId`의 양방향 고정 mapping
- `pageToken`
- 0-based 내부 페이지와 1-based wire 페이지의 정확한 변환
- `attemptNo`

같은 PDF를 두 번 가져오면 content hash가 같을 수 있다. 따라서 content hash가 같다는 이유로 현재 선택 학생이나 임의의 교재에 자동 재매핑하면 안 된다. 기존 mapping이 삭제된 교재를 가리키거나 후보가 중복되면 사용자의 명시적 매핑을 요구한다.

`resolveLocalWorkbook`, `canAssignLocalWorkbook`, `resolveExactPublishedReviewWorkbookToken`, `resolveDeferredReviewWorkbookToken`, `selectTransmittableTeacherReview`의 조건을 완화하지 않는다.

### 2.3 열린 회차 첨삭 규칙

- 제출 여부는 원격 페이지 신원이 아니다.
- 학생이 현재 풀고 있는 열린 회차도 정확한 `교재 + 페이지 + 회차`가 manifest/저장소에 존재하면 첨삭 대상이다.
- 선생 첨삭은 실시간 초안을 자동 전송하지 않는다. 사용자가 `반영`을 누를 때 만든 불변 publication artifact만 전송한다.
- 학생 수신부는 회차가 실제로 존재하는지 확인하지만 `locked/submitted`를 요구하면 안 된다.
- 같은 회차를 여러 번 반영하면 각각을 시간순 이력으로 쌓는 것이 아니라 최신 전체 첨삭 상태가 해당 회차를 대체한다.
- 동일 revision에 다른 payload/hash가 오면 반드시 거부한다. 오래된 revision은 duplicate로 처리한다.

2026-08-28 기준으로 전송부와 학생 적용부 모두 열린 회차를 허용하도록 맞춰져 있다. `submittedAttemptNos` 또는 `Attempt.locked` 조건을 다시 첨삭 전송 게이트에 넣지 않는다.

### 2.4 전송 ACK와 적용 ACK를 합치지 말 것

- Telegram 서버가 문서를 받았다는 상태와 상대 앱이 해독·검증·적용했다는 상태는 다르다.
- outbox의 `SENT`만으로 페이지나 첨삭을 완료 처리하면 안 된다.
- `PAGE_SYNC_ACK`는 대상 앱이 semantic validation과 durable apply를 끝낸 뒤 보낸다.
- checkpoint가 여러 조각이면 조각별 Telegram 문서는 inbox에서 소비할 수 있지만, 페이지 application ACK는 모든 조각을 조립·검증·적용한 뒤 `chunkGroupId`에 대해 한 번만 보낸다.
- Telegram 서버 수락 상태가 durable하게 기록된 문서는 peer receipt를 기다리는 동안 같은 outbox 항목을 다시 업로드하지 않는다. 다만 API 응답 전후 프로세스 종료처럼 서버 수락 여부가 모호한 실패에서는 같은 stable transfer가 중복 업로드될 수 있으므로, 수신자는 `transferId`와 page revision/digest로 의미 적용을 한 번만 수행해야 한다.
- transport receipt 뒤 `PAGE_SYNC_ACK`만 잃은 경우에는 같은 서버 문서를 재업로드하는 방식이 아니라 새 page request/논리 전송으로 복구를 재구동한다. 상대는 이미 적용한 revision/digest를 duplicate로 판정하고 application ACK를 다시 보낼 수 있어야 한다.

`TelegramEnqueueResult.ENQUEUED`, `ALREADY_PENDING`, `ALREADY_DELIVERED`는 “durable queue accepted”이지 “상대 앱 적용 완료”가 아니다.

### 2.5 idempotency, high-water, 재시작 복구

- stable transfer ID, outbox `DONE/DEAD/SUPERSEDED`, peer receipt, manifest generation/sequence, page revision, origin cursor를 제거하거나 매 실행마다 초기화하지 않는다.
- Telegram update offset, outbox journal, peer inbox journal을 연결 시마다 지우면 오래된 메시지 반복 재생 또는 새 메시지 유실이 생긴다.
- inbound document는 암호문 다운로드·검증 뒤 `TelegramPeerDocumentInbox.offer()`가 payload와 `PUT`을 fsync한 다음에만 Telegram update offset을 commit한다. 이 순서를 뒤집지 않는다.
- 수신 완료 시에도 durable `RECEIVED` control enqueue가 먼저고 inbox 원문 삭제가 나중이다. ACK enqueue 실패 상태에서 원문을 삭제하면 안 된다.
- manifest의 window/sequence는 `reserveStudentManifest()`로 예약하되, inventory 문서의 transport ACK를 확인한 뒤에만 `acknowledgeOutstandingStudentManifest()`로 inventory window를 전진한다. 단순 enqueue나 Telegram server accept를 window 완료로 간주하지 않으며, 5초 fast manifest의 ACK는 window ordinal을 전진시키지 않는다.
- 같은 page request ID를 재수신하면 기존에 예약한 동일 응답 artifact를 재사용한다. 같은 request ID로 더 최신 필기를 다시 캡처하면 ACK correlation과 payload 불변성이 깨진다.
- 선생 수신부는 exact active request 또는 exact completed duplicate만 허용한다. chunk는 조각 파일 영속화 뒤 transport ACK, 전체 조립·hash·원자 적용 뒤 application ACK 순서를 유지한다.
- duplicate/stale manifest는 교재 조회나 annotation log 재생보다 먼저 high-water로 분류한다.
- duplicate manifest의 durable side effects는 idempotent하게 재실행한다. 저장 직후 프로세스가 죽은 경우를 복구하기 위한 동작이다.
- generation high-water는 일반 page-sync JSON과 별도 `AtomicFile`에 저장한다. 본 journal의 손상이나 rollback이 과거 generation을 부활시키면 안 된다.
- 책 삭제·재가져오기 또는 pair 변경은 opaque workbook/page identity를 바꾸므로 generation 경계를 유지한다.
- manifest의 `inventoryPageCount`는 실제 entries보다 작을 수 없다. Attempt catalog에 없지만 durable annotation row가 있는 페이지도 있으므로 현재 구현은 `max(discovered, durable rows)`를 사용한다.
- `sourceRevision`은 `(studentLayerSha256, attemptNos, submittedAttemptNos)`의 의미 fingerprint가 바뀔 때 전진하는 page revision이다. delta 연속성을 증명하는 `originDeviceHighWater/acknowledgedOriginCursor`와 합치거나 operation logical clock으로 치환하지 않는다.

### 2.6 LAN과 Telegram을 동시에 page writer로 만들지 말 것

- “속도를 높이기 위해 둘 다 보내고 먼저 온 것을 적용”하는 방식은 금지한다. LAN operation stream과 Telegram checkpoint/delta는 하나의 공통 전역 revision journal이 아니므로 경쟁 적용 시 되돌림과 중복이 발생할 수 있다.
- `globalPageSyncTransportRoute`, `processPageSyncIncoming`, `LanSyncBus.withActiveSessionLease`의 check/apply 원자성을 유지한다.
- 선생 Reader 진입 시 실제 서비스 역할이 `TEACHER_CLIENT`인지 확인하는 `ReaderActivity.ensureLanRoleForReader()`를 제거하지 않는다. UI 역할만 바뀌고 기존 학생 서버가 남으면 같은 hotspot에서 영구적으로 연결되지 않을 수 있다.
- `LanSyncBus.sessionRole`은 화면 표시용 role과 서비스가 실제 소유한 role의 불일치를 찾기 위한 상태다.

### 2.7 크기 제한을 임의로 축소하거나 확대하지 말 것

- 한 Telegram page annotation 조각은 약 2 MiB 제한 안에 있어야 한다.
- checkpoint는 최대 8조각이며 현재 assembled 상한은 조각 상한의 8배(약 15.75 MiB)다.
- 실제 14.5 MB / 8조각 페이지 회귀 테스트가 있다. 상한을 5 MB나 8 MB로 다시 낮추면 현재 사용 중인 긴 필기 페이지가 영구적으로 동기화되지 않는다.
- 반대로 assembled 상한을 키우면 현재 수신부가 최종 조립 시 전체 `ByteArray`를 만드는 메모리 모델도 함께 바꿔야 한다. 숫자만 올리지 않는다.
- delta는 요청자의 applied revision과 학생의 acknowledged revision/origin cursor가 정확히 이어지고, 제한 안에서 완전한 operation batch를 만들 수 있을 때만 사용한다. 하나라도 맞지 않으면 full checkpoint로 복구한다.
- checkpoint나 delta를 stroke 수/페이지 수 기준으로 조용히 자르면 안 된다. 잘린 상태는 정상 hash로 보이면 더 위험하다.

### 2.8 메인 스레드와 큰 락

- 네트워크, 파일 읽기, checkpoint 조립, PDF 렌더, 큰 JSON canonicalization을 Android main thread에서 실행하지 않는다.
- coordinator는 1초 tick에서 inbox를 최대 8건만 처리하고, inventory scan은 tick당 페이지 하나만 연다. 이 bounded work를 한 번에 전체 책 스캔으로 되돌리지 않는다.
- 5초 경로는 학생의 현재 페이지·새 필기 이벤트와 자동 대상의 작은 delta만을 위한 fast lane이다. 47페이지 inventory window, checkpoint, 수동 backlog, 실패 retry까지 5초로 일괄 축소하면 안 된다.
- decoded page index LRU는 3개, revision-keyed student layer digest LRU는 32개다. 이를 무제한 캐시로 바꾸거나 모든 manifest 페이지의 decoded index를 장기 보유하지 않는다.
- `RemotePageSyncController.uiState()`는 volatile cached state를 즉시 반환한다. UI attach가 multi-MB decode를 잡고 있는 controller monitor 뒤에서 기다리지 않게 한 장치다.
- 조각 진행률 조회는 활성 요청 한 건에 대해서만 metadata와 파일 길이를 읽는다. 매 렌더마다 모든 chunk payload를 읽거나 hash하지 않는다.
- 한 페이지씩 직렬 전송하는 구조를 병렬 다중 페이지 전송으로 바꾸려면 outbox 용량, 메모리 peak, Telegram rate limit, ACK correlation을 함께 다시 설계해야 한다.

### 2.9 보안·개인정보 경계

- bot token을 소스, Gradle 설정, 문서, 로그에 넣지 않는다. credential은 Android Keystore 기반 저장소만 사용한다.
- pinned peer bot, private chat allowlist, pair binding, payload encryption/인증 검사를 우회하지 않는다.
- sender bot ID, transfer ID, payload type, ciphertext size가 envelope와 맞는지 검사하는 경로를 유지한다.
- `no_backup/master-note-telegram-v1`, 실제 outbox/inbox, QR payload, bot username/chat ID, 기기에서 추출한 JSON 및 화면 캡처를 Git에 올리지 않는다.
- 저장소의 `/work/`는 실기기 진단 산출물 전용이며 `.gitignore` 대상이다.

## 3. 현재 의도된 동작과 수치

### LAN

- 학생 server / 선생 client 구조다.
- 선생이 구독한 한 페이지의 operation과 학생 현재 위치 `PAGE_STATE`를 보낸다.
- stroke flush debounce는 180 ms, 최대 지연은 600 ms다. 페이지 이동은 즉시 flush한다.
- operation ID로 중복 제거하며 원격 operation은 로컬 undo/redo stack에 넣지 않는다.
- 연결됨 표시만으로 READY가 아니다. handshake와 현재 구독 페이지 catch-up이 끝나야 LAN이 페이지 데이터를 소유한다.

### Telegram page sync

- peer freshness는 최근 인증 응답 90초, ping 간격은 30초다.
- 학생의 현재 페이지 이동·heartbeat·새 필기처럼 화면 추적에 직접 필요한 이벤트는 마지막 manifest 전송 뒤 5초 경계부터 다음 manifest를 보낼 수 있게 예약한다. 이는 5초마다 책 전체를 스캔하는 timer가 아니다.
- 47페이지 inventory window의 순환은 기존 60초를 유지한다. 일반 retry는 30초이며, 그 사이 새 펜 이벤트가 생겨도 실패한 manifest lane의 retry gate를 앞당기지 않는다. teacher review 전송 간격은 60초, application ACK recovery는 2분이다.
- 5초 fast manifest는 현재 페이지와 최근 변경 두 페이지, 최대 3행만 전송하고 inventory window ordinal을 소비하지 않는다. inventory와 동시에 due면 inventory를 먼저 보내되, 누락될 수 있는 최근 두 페이지를 위해 fast 예약을 `inventory 전송 + 5초` 이후로 보존한다.
- 60초 inventory manifest는 stable token 47개를 회전시키고, 그 window 밖의 현재 페이지를 하나 추가해 최대 48행을 전송한다. 큰 inventory의 window ordinal은 해당 inventory manifest의 transport ACK 뒤에만 전진한다.
- inventory를 전부 발견하기 전에는 현재 학생 페이지 한 쪽만 자동 대상이다. 완료 뒤에는 현재 페이지와 최근 변경 두 페이지를 합쳐 최대 3페이지가 자동 대상이며, 학생이 다음 페이지로 이동하면 가장 오래된 페이지가 자동 대상에서 밀릴 수 있다.
- 자동 대상(현재 페이지 + 최근 두 페이지)의 작은 DELTA를 성공적으로 적용한 뒤 다음 페이지 요청까지의 cooldown은 5초다. 무변경 revision/hash에는 `PAGE_ANNOTATION`을 만들지 않는다.
- 자동/수동 request lane은 요청 예약 시점에 고정해 journal에 남긴다. Telegram 왕복 중 학생 커서가 여러 쪽 이동해도 완료 시점의 자동 목록으로 cooldown을 재판정하지 않는다.
- 큰 CHECKPOINT와 선생이 시작하는 수동 backlog DELTA는 사용자가 고른 30초/60초 cooldown을 유지한다. 직전 응답이 CHECKPOINT였다면 그 휴지 시간 중 생긴 새 DELTA도 다음 요청까지 기다릴 수 있다.
- 한 번에 페이지 하나만 전송한다. 오랜 오프라인 뒤에도 모든 페이지를 한꺼번에 밀어 넣지 않는다.
- 위 5초는 앱에서 다음 왕복을 시작할 수 있는 가장 빠른 경계다. Telegram의 manifest → request → delta와 서버 polling을 거치므로 실제 화면 도착을 항상 5초 이내로 보장한다는 뜻은 아니다.
- multi-chunk 활성 페이지는 저장 완료된 조각 기준 `n/N`과 byte percentage를 표시한다. 단일 조각은 중간 전송률을 알 수 없으므로 가짜 퍼센트 없이 `응답 대기` 후 완료된다.
- 이미 최신 revision/hash까지 적용된 페이지는 “동기화 필요” 목록에 나오지 않는다. 자동 대상이 실제 전송 중이면 별도 활성 행으로 표시한다.

### Telegram durable queue

- long poll owner는 하나다. poll timeout은 50초다.
- outbox는 fsync append journal과 stable idempotency key를 사용한다.
- 일반 pending 상한은 512건이고 peer control/receipt용 예약 lane은 4건이다. 예약 lane에서 밀어낼 수 있는 것은 오래된 ping/connect probe뿐이며 `RECEIVED`, `PONG`, 핵심 연결 응답은 data backlog 때문에 제거하면 안 된다.
- 송신 대기 peer-document outbox의 디스크 상한은 48건 / 96 MiB이고, 수신 peer-document inbox도 48건으로 제한된다. 둘을 하나의 queue로 오해하지 않는다.
- Telegram server accept 후 상대 receipt를 기다리는 bookkeeping은 최대 24시간 유지될 수 있다.
- API retry는 2초부터 지수 증가해 최대 5분과 양의 jitter를 사용한다. `retry_after`는 모든 송신에 적용되는 전역 gate이며 그보다 일찍 재시도하지 않는다.

### 첨삭

- `반영` 시점의 teacher layer와 해당 회차 mark groups를 immutable artifact로 고정한다.
- 미제출 열린 회차도 exact mapping과 manifest evidence가 있으면 보낸다.
- 전송 중 끊겨도 publication intent와 artifact가 남아 재시작 후 이어진다.
- LAN으로 같은 publication이 이미 ACK되면 Telegram pending intent를 완료 처리해 중복 전송을 막는다.

## 4. 안전하게 최적화할 수 있는 영역

다음은 위 불변조건과 테스트를 유지하는 범위에서 개선 가능하다.

- 같은 revision의 portable layer digest 재계산 감소. 단 revision 기반 무효화와 restore clear 필수.
- manifest 준비 중 cold page log를 여는 횟수 감소. durable page state를 우선 사용하고 unknown은 pending으로 남긴다.
- checkpoint 최종 조립을 streaming hash/streaming decode로 바꿔 peak memory를 낮추는 작업. 단 적용 전 전체 검증과 atomic commit을 보존해야 한다.
- LAN 첨삭 조각의 `fold(ByteArray(0)) { acc + part }`는 O(n²) 복사 후보이므로 정확한 크기 preallocation 또는 streaming 조립으로 바꿀 수 있다. chunk identity/order/size/SHA 검증은 그대로 둔다.
- Telegram document 다운로드와 AES-GCM 복호화가 long-poll thread를 오래 점유하는지 측정한다. bounded worker로 옮기더라도 durable inbox 소유 뒤 offset commit이라는 순서를 보존한다.
- outbox 우선순위 조정과 coalescing 개선. application ACK/control/chat이 큰 page document 뒤에서 굶지 않아야 한다.
- 페이지별 전송 시간, retry 이유, chunk progress, queue depth 같은 구조화된 로컬 진단 지표 추가. 사용자 식별자와 payload는 기록하지 않는다.
- UI 진행 상태와 대기 이유 개선. 프로토콜 상태를 바꾸지 않는 UI-only 변경은 상대적으로 안전하다.
- gzip 효율 및 delta operation encoding 개선. 양쪽 decoder 호환성과 digest 의미가 그대로여야 한다.
- `PageAnnotationEnvelope`의 반복 gzip decode/copy와 상태 변경마다 전체 page-sync JSON을 저장하는 비용을 계측한다. limit/hash/atomic reservation을 유지한 범위에서만 줄인다.
- `bindWorkbookMapping()`이 controller lock 안에서 sibling page digest를 계산하는 구간은 background capture/compute/commit 후보다. commit 직전에 mapping generation과 page identity를 다시 검증한다.
- controller의 큰 synchronized 영역 축소. 상태 전이는 여전히 단일 직렬 executor 또는 동등한 원자성을 가져야 한다.

## 5. 적대적 검증 시나리오

| 시나리오 | 주입 지점 | 반드시 만족할 결과 |
|---|---|---|
| Wi-Fi/LTE/hotspot 반복 전환 | LAN handshake, catch-up, Telegram fallback 각 단계 | 페이지 writer는 항상 하나, 표시가 최종 소유 경로와 일치 |
| upload 직전 프로세스 종료 | manifest/request/annotation/review enqueue 전후 | 재시작 후 유실 없이 재개, 폭주·중복 적용 없음 |
| Telegram server accept 전후 종료 | API 결과가 모호하거나 peer receipt/application ACK 전 | durable server-accepted 항목은 재업로드하지 않음. 결과가 모호해 중복 업로드되더라도 같은 stable transfer는 의미 적용 1회, 누락된 application ACK는 새 request/논리 전송으로 복구 |
| inbox 저장 경계에서 종료 | peer inbox `PUT` 전후와 update offset commit 전후 | payload를 잃은 offset 전진 없음, 같은 update 재생 시 중복 보관·적용 없음 |
| 수신 완료 경계에서 종료 | durable `RECEIVED` enqueue 전후와 inbox 삭제 전후 | ACK를 만들지 못한 원문 삭제 없음, 재시작 뒤 안전하게 완료 |
| manifest 순서 뒤집기·중복 | generation/sequence N-1, N, N 재전송 | stale은 무거운 IO 없이 소비, duplicate side effect는 안전하게 복구 |
| manifest 예약 경계에서 종료 | reserve, enqueue, server accept, peer receipt 각각 직후 | ACK 전 window ordinal 전진 없음, 같은 window 유실·건너뜀 없음 |
| checkpoint 조각 순서 뒤집기 | 8조각의 7, 0, 3… 순서 | 디스크 진행률 복원, 전부 모이기 전 페이지 적용·application ACK 없음 |
| 조각 누락·중복·hash 변조 | 중간 조각과 동일 index 다른 bytes | 기존 조각 보존, 잘못된 그룹 거부, 다음 full checkpoint로 회복 |
| 14.5 MB 페이지 | 8조각 checkpoint | OOM/ANR 없이 적용, 결과 layer SHA 일치 |
| 20페이지 이상 오프라인 backlog | 재연결 직후 | 자동 최근 3쪽만 우선, 나머지는 수동 목록·용량 표시, 한 페이지씩 처리 |
| 동일 PDF 두 권 | 서로 다른 local book ID | 자동 오매핑 없음, 잘못된 페이지·첨삭 적용 0건 |
| 책 삭제 후 재가져오기 | 기존 mapping/generation 유지 상태 | 새 opaque identity 또는 명시적 재매핑, 옛 데이터가 새 책에 유입되지 않음 |
| 열린 회차 첨삭 | 학생 4회차 미제출 상태에서 선생 `반영` | 정확한 4회차에 도착, 3회차나 다른 페이지로 이동하지 않음 |
| 첨삭 직후 학생 제출 | review와 새 manifest/필기 순서 뒤집기 | exact attempt 유지, review 유실·다른 회차 적용 없음 |
| 오래된 Telegram 문서 잔류 | 퇴역 payload와 최신 delta 혼합 | legacy 문서는 소비·폐기, 최신 page sync만 적용 |
| application ACK만 유실 | 적용 완료 후 ACK drop | 새 request/논리 전송으로 복구하고 동일 revision/digest는 duplicate 처리·재ACK, 결과는 한 번 적용된 것과 동일 |
| queue 포화 | 일반 512건 + 예약 4건, peer document 48건/96 MiB | CHAT/RECEIVED/PING/PONG starvation 없음, 핵심 ACK를 ping으로 교체하지 않음 |
| clock 이상 | wall clock rollback, 미래 Telegram server Date, `retry_after` | logical ordering 불변, busy retry 없음, 전역 rate gate 준수 |
| 디스크 부족/손상 | outbox append, manifest persist, chunk write | 기존 데이터 유지, false success 금지, backoff 후 재시도 가능 |
| 빠른 UI 재진입 | 큰 페이지 decode 중 TEL 패널 열기 | main thread block/ANR 없음, cached UI state 즉시 표시 |

## 6. 최적화 합격 기준

성능 수치가 좋아져도 아래 중 하나라도 깨지면 실패다.

1. 잘못된 교재·페이지·회차 적용 0건.
2. 네트워크/프로세스 중단 후 eventual convergence. 양쪽 layer SHA와 revision이 최종적으로 일치해야 한다.
3. 같은 transfer/revision 재수신 시 중복 stroke, 중복 점수, 반복 메시지 0건.
4. LAN READY와 Telegram page apply가 동시에 활성인 구간 0건.
5. large checkpoint 처리 중 main-thread disk/network 작업과 ANR 0건.
6. 재연결 직후 outbox 폭주 없음. retry 간격과 Telegram `retry_after` 준수.
7. 앱 업데이트는 `adb install -r`로 수행하며 catalog hash와 annotation 저장소 크기/파일을 설치 전후 비교한다. uninstall, clear data, `pm clear` 금지.
8. 모든 변경은 기존 필기 데이터로 검증하기 전에 복제 데이터나 테스트 fixture에서 먼저 실패 주입한다.

## 7. 필수 자동 검증

전체 기준:

```powershell
.\gradlew.bat testDebugUnitTest :app:assembleDebug
git diff --check
```

통신 변경 시 최소 집중 테스트:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.studyink.app.RemotePageSyncPolicyTest
.\gradlew.bat :app:testDebugUnitTest --tests com.studyink.app.RemotePageSyncChunkStoreTest
.\gradlew.bat :app:testDebugUnitTest --tests com.studyink.app.RemotePageSyncUiModelsTest
.\gradlew.bat :monitor:core:testDebugUnitTest
.\gradlew.bat :monitor:telegram:testDebugUnitTest
.\gradlew.bat :sync:lan:testDebugUnitTest
.\gradlew.bat :annotation:storage:testDebugUnitTest
```

특히 아래 회귀 테스트를 삭제하거나 약화하지 않는다.

- duplicate/stale manifest preflight
- durable inventory row 수가 catalog 발견 수보다 큰 경우
- exact bidirectional workbook/attempt review binding
- 열린 회차 첨삭 허용과 unknown attempt 거부
- out-of-order/restart chunk progress 및 8조각 14.5 MB assembly
- same revision/different digest 거부
- LAN global ownership과 role mismatch 복구
- annotation store digest cache bound 및 lock 밖 checkpoint/digest 작업

## 8. 실기기 검증 기록 방법

- 테스트 시작 전 양쪽 기기의 앱 버전, 역할, 연결 경로, 대상 교재/페이지/회차를 기록한다.
- 앱 private 파일 원문이나 bot/chat 식별자를 복사하지 말고, count/revision/hash/queue 상태만 요약한다.
- `logcat`에서는 `FATAL EXCEPTION`, 앱 ANR, manifest validation failure, review reject reason, retry loop를 확인한다.
- Telegram 색상 표시만 보고 성공으로 판단하지 않는다. teacher pending queue, student applied revision, 최종 layer SHA를 함께 확인한다.
- 페이지가 목록에 없으면 먼저 “이미 최신이라 제외됐는가”를 확인한다. 목록 부재만으로 전송 실패라고 판단하지 않는다.
- 같은 회차에서 여러 번 `반영`했을 때는 마지막 publication 전체 상태가 학생에게 보이는지 확인한다.

최소 진단 증거:

- transport route 변화 `TELEGRAM`/`LAN_GRACE`/`LAN_OWNS`, Telegram API 상태, 마지막 인증 peer 응답의 age
- outbox/inbox pending 건수·바이트·oldest age·dead letter와 server-accepted 대기/peer-ACK 대기의 분리 수치
- manifest generation/sequence/window ordinal, expected/discovered inventory 수
- 요청마다 익명화한 page token, requested/source/applied revision, origin cursor, delta/checkpoint, chunk `n/N`, 바이트
- enqueue → server accepted → receiver durable PUT → apply → semantic ACK enqueue → sender resolution의 단계별 monotonic 시간
- API 호출/업로드 바이트/retry/429 횟수, controller tick/store lock/checkpoint export·apply 시간
- Java heap/PSS/GC, main-thread stall, dropped frame, ANR/crash와 설치 전후 catalog 및 annotation 저장소의 hash·크기

현재 유효한 통신 로그 태그는 `RemotePageSync`, `MasterNoteLan`, `MasterNotePeerInbound`, `RemoteMonitorService`다. 식별자는 전체값 대신 hash 앞부분만 기록하고 token, shared key, credential, 학생 필기 payload는 절대 남기지 않는다.

2026-08-28 설치 검증에서는 삭제 없는 debug 덮어쓰기 후 catalog hash와 저장소 크기가 유지됐고, 기존 대기 중이던 선생 첨삭이 재개되어 teacher pending queue가 0으로 줄었으며 학생의 제출 회차와 열린 회차 적용 기록이 모두 확인됐다.

같은 날 오프라인 작성 뒤 97·98·99쪽이 일부 누락돼 보인 사례를 양쪽 private 저장소에서 직접 대조했다. 최종 상태에는 누락이 없었다.

- 학생 활성 획 수는 차례로 46/18/12였고 선생폰의 학생 레이어도 ID 단위로 46/18/12가 일치했다.
- page `sourceRevision`/선생 `appliedRevision`/학생 `acknowledgedRevision`은 각각 48/20/14로 모두 일치했고, origin cursor도 46/18/12까지 ACK됐다.
- 세 페이지 모두 1회차 제출 상태가 일치했다. 97쪽에 있던 선생 레이어 3획도 양쪽에 남아 있었다.
- 학생 변경은 16:56:20~16:56:37에 연속 발생했지만 선생 checkpoint 반영은 97쪽 16:58, 99쪽 17:00, 98쪽 17:01이었다. 즉 이 관찰은 영구 유실이 아니라 단일 요청 슬롯과 page cooldown 때문에 순서가 뒤집혀 수 분 동안 부분적으로 보인 사례다.

위 관찰은 5초 fast lane을 넣기 전의 60초 기본 cooldown 빌드에서 수집한 기록이다. 현재 빌드에서도 reconnect 직후 inventory scan은 1초 tick당 페이지 하나이고 60초 window 순환을 유지하지만, 현재 페이지·최근 변경 metadata는 최대 3행 fast manifest로 분리되고 자동 DELTA 뒤 cooldown은 5초다. 다만 첫 응답이 CHECKPOINT면 설정된 30초/60초 휴지는 그대로 적용되므로 초기 복구가 즉시 끝난다고 가정하면 안 된다. “자동 3쪽”은 최근 작성 3쪽에 current를 별도로 더하는 것이 아니라 `current + 최근 변경 2쪽`, 총 3쪽이다. 학생이 빈 다음 페이지로 이동하면 세 번째 최근 작성 페이지는 수동 목록으로 밀릴 수 있다.

UI는 대기 중인 automatic 행을 일반 목록에서 숨기고 현재 active 한 건만 별도로 보여준다. 따라서 최적화 검증에서는 “최종 수렴”과 “사용자가 중간에 보는 대기 상태”를 구분한다. 자동 대상 3쪽 각각의 대기/요청/수신/적용/ACK 단계와 예상 다음 요청 시각을 UI 또는 익명화 로그로 관찰할 수 있게 하는 것은 안전한 개선 후보지만, 이 사례만 보고 병렬 전송이나 ACK 선처리를 도입하면 안 된다.

2026-08-29에 위 `LAN_GRACE` stale-row 위험과 LAN 성공 뒤 전체 목록 재등장 문제를 다음의 보수적 구조로 수정했다.

- 학생의 local operation·presence·heartbeat·회차 변경은 `LAN_GRACE`에서도 해당 한 페이지 row를 즉시 갱신한다. Telegram manifest 예약/송신만 transport ownership으로 막는다. `LAN_OWNS`에서는 기존 generation fence를 유지한다.
- callback 직후 process death가 난 작은 틈은 기존 durable row만 최근 순서로 한 쪽/틱 검증한다. 비교값은 append-only operation log 길이와 회차/제출 번호이며, 불일치한 쪽만 실제 page replay와 SHA 계산을 한다. 검증 전 row는 manifest에서 제외하고 inventory total도 확정하지 않는다. PDF의 모든 페이지를 한꺼번에 열거나 hash하지 않는다. 읽기·재생 실패한 한 쪽은 검증되지 않은 채 60초 backoff로 다시 시도하되, 이미 발견·검증된 다른 수동 페이지의 요청을 막지는 않는다. 실패한 쪽이 남아 있는 동안 inventory 완료 표시는 거짓으로 유지한다.
- 재시작 때 journal에 열린 학생 generation이 발견되면 새 runtime이 그것을 재사용하지 않는다. 먼저 redundant generation high-water를 한 칸 올려 저장하고 열린 generation, 예약 manifest, 이전 generation의 teacher-review 적용 기록을 닫은 뒤에만 새 generation을 연다. 따라서 LAN READY 직후 process death가 나도 지연 도착한 이전 Telegram 작업이 다시 현재 세대로 인정되지 않는다.
- 선생은 LAN 진입 전에 실제 적용돼 있던 학생 layer SHA만 `(workbookToken, contentSha256, localBookId, pageNumber)` exact identity로 보존한다. generation/page token/revision/request/grade는 이 evidence에 섞지 않는다.
- 새 Telegram generation/처음 발견한 mapped row는 evidence 유무나 SHA 일치 여부만으로 적용 완료 또는 요청 대상으로 확정하지 않는다. `verificationPending`으로 숨긴 뒤 로컬 student layer를 한 쪽/틱 계산하고, generation/token/revision/SHA가 여전히 정확히 같을 때만 새 generation의 `appliedRevision`으로 승격한다. LAN에서 이미 해결된 쪽은 요청하지 않고, 실제로 다른 쪽만 동기화 필요가 된다. 로컬 읽기 실패는 그 행을 즉시 CHECKPOINT 요청 가능 상태로 내려 다음 검증 행을 막지 않는다.
- 연결된 각 pre-READY LAN socket에는 4초 catch-up 기회를 준다. 4초 뒤에도 READY가 아니고 Telegram peer가 실제 사용 가능할 때만 현재 socket generation에 yield를 요청한다. Telegram이 불가능하면 유일한 복구 후보인 LAN을 일부러 끊지 않고 기존 30초 watchdog에 맡긴다. READY 전이와 yield 예약은 같은 service monitor로 직렬화하며, socket reader가 이미 읽은 frame 적용을 끝내고 `DISCONNECTED`를 publish한 뒤에만 Telegram이 소유한다. 따라서 두 writer는 겹치지 않는다.
- teacher review/채점 publication ledger와 generation fence는 그대로 유지한다. GPT 설명/teaching-resource lane은 현재 HEAD에 없으므로 page evidence에 가짜 필드를 추가하지 않았다. 나중에 합칠 때도 독립 resource identity/revision/digest lane으로 다룬다.

구조 근거는 Kubernetes client-go workqueue의 dirty/processing 재삽입 불변식, Git fsmonitor의 key별 invalidation, Syncthing의 connection 수명과 semantic progress 분리 방식을 참고했다. 범용 queue·전역 DB sequence·full invalidation은 가져오지 않고 현재 단일 worker와 bounded window에 필요한 불변식만 적용했다.

- Kubernetes client-go: <https://github.com/kubernetes/client-go/blob/ff4057d4927d4407c0db43974714e33f3b5e0dac/util/workqueue/queue.go#L190-L302>
- Git fsmonitor: <https://github.com/git/git/blob/f78ce2f7b6df702f93d40b85d6bda92a3f65da79/fsmonitor.c#L587-L725>
- Syncthing index resume/batch: <https://github.com/syncthing/syncthing/blob/9af3c75f377c51a7e3f285704025385104d8f428/lib/model/indexhandler.go#L33-L137>
- Kafka stale-generation completion fence: <https://github.com/apache/kafka/blob/be1813e3f85b8c3ad263a68c544cc09f3ac6332c/clients/src/main/java/org/apache/kafka/clients/consumer/internals/AbstractCoordinator.java#L1230-L1283>

## 9. Git 및 롤백 규칙

- 시작점: `connection-optimization-baseline-20260828`
- 작업 브랜치: `feature/telegram-page-delta-sync`에서 별도 최적화 브랜치를 만드는 것을 권장한다.
- `/work/`, 기기 추출물, credential, outbox/inbox 원문은 커밋하지 않는다.
- 프로토콜 wire 형식(`RemoteReviewCodec` version/extension), `RemotePageSyncStore` 저장 version, outbox journal 형식을 바꾸면 구버전 양쪽 기기와 재시작 데이터에 대한 migration 테스트가 필수다.
- 실패 시 사용자 데이터에 손대지 말고 코드를 기준 태그로 되돌린 뒤 `adb install -r`로 덮어쓴다. 앱 삭제나 데이터 초기화로 문제를 숨기지 않는다.
