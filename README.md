# Study Ink Kernel

자녀 학습 앱의 첫 구현 단계인 Android 문서·필기 커널입니다.

## 현재 구현 범위

- AndroidX PDF 기반 PDF 열기, 확대/축소, 이동
- Jetpack Ink 기반 저지연 wet ink
- 펜, 형광펜, 색상·굵기·투명도 조절
- S펜 물리 버튼 위치에서 열리는 반원형 빠른 메뉴
- 파란 미리보기 구간만 제거하는 부분 지우개
- 실행 취소/다시 실행
- 한 페이지 단위 이동과 손가락 확대·이동
- PDF별 자동 저장과 앱 재실행 후 복원
- 원본 PDF와 완전히 분리된 불변 필기 자산

앱을 처음 열면 내장 예제 PDF가 자동으로 표시됩니다. 상단의 `PDF 열기`로 기기의 다른 PDF를 선택할 수 있습니다.

## 빌드

```bash
./gradlew :app:assembleDebug
```

## 모듈

- `core:model`: Canonical Page Space(가로 1000) 데이터 모델
- `annotation:engine`: 불변 자산, 연산 기록, undo/redo, 부분 지우개 계산
- `annotation:storage`: PDF별 원자적 자동 저장
- `document:pdf-androidx`: AndroidX PDF와 공개 좌표 API를 감싼 어댑터
- `feature:reader`: PDF/필기 레이어 조립 및 사용자 화면
- `app`: 설치 패키지
