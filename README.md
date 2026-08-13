# PlanetMap

플래닛어스 맵을 플래닛어스 웹 지도를 기반으로, 인게임에서 실시간으로 지도를 확인할 수 있도록 제공하는 미니맵 모드입니다.

## 주요 기능

- 웹지도 타일을 플레이어 위치 중심으로 표시하는 미니맵
- 웹지도에서 제공하는 국가·마을 영토 색상, 반투명 내부 채움과 경계 표시
- 온라인 플레이어의 스킨 얼굴과 닉네임, 현재 플레이어 강조 표시
- 실제 16×16 청크 경계를 따르는 그리드
- `M`: 미니맵 편집 화면 열기/닫기
- `N`: 전체 지도 열기/닫기
- 미니맵 위치 이동과 가로·세로 독립 크기 조절
- 지도, 그리드, 플레이어 얼굴·이름, 웹 마커, 웨이포인트 표시 설정
- 전체 지도 드래그 이동·휠 확대/축소, 마커 검색과 클릭 이동
- 우클릭 웨이포인트 등록, 이름·색상·핀/작은 원/머리 모양 지정, 목록 이동과 삭제
- 웨이포인트의 화면 신호기 기둥, 이름과 실시간 거리 표시
- 사이트 마커·웨이포인트 좌클릭 길 안내, 미니맵 점선 방향과 도착 자동 종료음
- LiveAtlas 업데이트 피드 기반 타일 무효화와 화면 주변 선행 로딩
- 한글 IME 조합과 N 문자 입력을 보존하는 마커·플레이어 검색

## 요구 사항

- Fabric Loader
- Fabric API
- Minecraft 버전에 맞는 PlanetMap JAR
- Minecraft 1.20.1~1.20.4: Java 17
- Minecraft 1.20.5~1.21.11: Java 21

JAR 파일명에 표시된 Minecraft 버전과 같은 게임 버전에서 사용하세요. 이 프로젝트는 비공식 클라이언트 모드이며 PlanetEarth 서버 운영진과의 공식 제휴를 의미하지 않습니다.

## 빌드

Gradle은 Java 21로 실행합니다. 기본 설정은 Minecraft 1.20.1이며 결과물은 `build/libs`에 생성됩니다.

```powershell
$env:JAVA_HOME='C:\path\to\jdk-21'
.\gradlew.bat clean build
```

버전별 빌드는 `minecraft_version`, `yarn_mappings`, `fabric_version`, `java_version`, `compat_layer`, `input_layer`, `archives_base_name` 속성을 함께 지정합니다.

설정 파일은 `.minecraft/config/planetearth-minimap.json`에 저장됩니다.
