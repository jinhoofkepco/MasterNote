# MasterNote

학생은 문제집을 풀듯 사용하고, 선생과 학생은 종이책을 함께 보듯 사용하는 Android 학습 앱입니다.

## 현재 구현 범위

- 두 학생 프로필과 학생별 앱 소유 PDF 책장
- AndroidX PDF 기반 단일 페이지 열기, 확대/축소, 이동
- Jetpack Ink 기반 저지연 wet ink
- 펜, 형광펜, 색상·굵기·투명도 조절
- S펜 물리 버튼 위치에서 열리는 반원형 빠른 메뉴
- 파란 미리보기 구간만 제거하는 부분 지우개
- 메모리 전용 실행 취소/다시 실행
- 한 페이지 단위 이동과 손가락 확대·이동
- `(Book UUID, page)` append-only 연산 로그, 체크포인트, 손상 격리
- 원본 PDF와 완전히 분리된 불변 필기 자산
- 페이지별 제출 회차, 선생 첨삭, 채점 표시
- NSD 기반 같은 Wi-Fi 페이지 구독 동기화 기반

앱은 책장으로 시작합니다. 책장에서 학생을 선택하고 PDF를 가져온 뒤 페이지를 엽니다. Reader 안의 액션 버튼은 S펜만 실행하며 손가락은 PDF 확대와 이동에만 사용됩니다.

## 최신 테스트 APK

[MasterNote.apk 다운로드](https://github.com/jinhoofkepco/MasterNote/releases/download/dev-latest/MasterNote.apk)

GitHub Release의 `MasterNote.apk`는 항상 같은 인증서로 서명되고 이전 배포본보다 높은
`versionCode`로 생성되므로 기존 앱을 지우지 않고 업데이트됩니다. Android Studio의
debug 앱은 `com.studyink.app.debug`로 분리되어 배포 앱을 덮어쓰지 않습니다.

`main`에 변경 사항이 올라오면 GitHub Actions가 빌드와 단위 테스트를 실행한 뒤 위 파일을 교체합니다. APK는 고정된 테스트 인증서로 서명되며, 인증서 지문이 달라지면 배포 단계가 실패하도록 검사합니다.

## 빌드

```bash
./gradlew :app:assembleDebug
```

## 모듈

- `core:model`: Canonical Page Space(가로 1000) 데이터 모델
- `annotation:engine`: 불변 자산, 연산 기록, undo/redo, 부분 지우개 계산
- `annotation:storage`: 페이지별 append-only 연산 로그와 체크포인트
- `library:data`: 학생·책·풀이 회차·채점 메타데이터와 앱 소유 PDF
- `sync:lan`: NSD/TCP 기반 같은 Wi-Fi 연산 로그 전송
- `document:pdf-androidx`: AndroidX PDF와 공개 좌표 API를 감싼 어댑터
- `feature:reader`: PDF/필기 레이어 조립 및 사용자 화면
- `feature:library`: 학생 전환, 교재 가져오기, 책·페이지 목록
- `app`: 설치 패키지
