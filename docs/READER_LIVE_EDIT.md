# Reader UI Live Edit workflow

Reader 상단 UI의 반복 조정 값은 `feature/reader/.../ReaderTheme.kt`의
`readerChromeTokens()` 함수 본문에 모여 있다. 색, 크기, 여백, 투명도, 그림자와
호버·눌림 애니메이션 값은 이 함수에서 조정한다.

## 확인된 빌드 조건

- Android Gradle Plugin: 8.13.2
- Kotlin/Compose plugin: 2.3.21
- Compose BOM: 2026.06.00
- 커스텀 Kotlin `moduleName`: 없음
- debug minify/resource shrink: 명시적으로 비활성화

## 반복 작업

1. `app`의 debug 변형을 실행하고 Reader에 진입한다.
2. Android Studio Live Edit를 활성화한다.
3. `readerChromeTokens()` 함수 본문 값을 바꾸고 저장한다.
4. 구조 확인은 `ReaderControls.kt`의 역할·폭별 Preview를 함께 사용한다.

debug 빌드는 마지막 책·페이지·역할로 자동 복귀하며 선생 PIN을 우회한다. 이 두
단축 기능은 런타임의 `ApplicationInfo.FLAG_DEBUGGABLE`을 확인하므로 release에서는
동작하지 않는다. `DryInkView`의 채점 Canvas 값도 `ReaderTheme.kt`에 모았지만 해당
값은 Compose Live Edit 대상이 아니므로 변경 후 APK를 다시 빌드해야 한다.
