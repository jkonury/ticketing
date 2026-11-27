# 노트 만들기

사용자가 브라우저나 CLI에서 제목 있는 노트를 저장하고, 쓰던 초안을 취소하고, 두 번째 사용자 화면에서 저장된 노트를 확인한다.

<!-- 형식 예시다. 실제 기능 파일도 H2 제목은 아래 영어 그대로 쓴다(생성 규약). 본문은 이 저장소의 앱 기준으로 쓴다. -->

## Sub-features

- `create-open`: 각 브라우저 진입점에서 빈 편집기를 연다.
- `create-save`: 제목과 본문을 저장한다.
- `create-cancel`: 끝내지 않은 브라우저 초안을 버린다.
- `create-cli`: 터미널에서 같은 형태의 노트를 만든다.

## How to get to it (user POV)

- 브라우저 도구 모음의 `New note` 버튼을 누른다.
- 편집 가능한 필드 밖에 포커스가 있을 때 `n`을 누른다.
- 터미널에서 `notes create --title <title> --body <body>`를 실행한다.

## Driving it with control-notes

Preconditions:

- Notes가 `http://127.0.0.1:4173`에서 정상이다.
- 제목이 `Release checklist`인 노트가 없다.
- `control-notes doctor`가 기대한 URL과 일회용 데이터 디렉터리를 보고한다.

- **편집기 열기.** `New note`를 누른다. `control-notes browser click --role button --name "New note"`를 실행한다. `Note editor`라는 폼이 나타나고 `Title` 텍스트박스에 포커스가 간다.
- **내용 입력.** 제목과 본문을 입력한다. `control-notes browser fill --role textbox --name "Title" --value "Release checklist"`와 `control-notes browser fill --role textbox --name "Body" --value "Tag and publish"`를 실행한다. `Save note` 버튼이 활성화된다.
- **저장.** `Save note`를 누른다. `control-notes browser click --role button --name "Save note"`를 실행한다. `Note saved`라는 상태가 나타나고 제목 헤딩이 `Release checklist`가 된다.
- **영속성 확인.** 노트 목록으로 돌아가 노트를 다시 연다. 편집기에 저장한 두 값이 보인다.
- **초안 취소.** 새 노트를 열고 `Discard me`를 입력한 뒤 `Cancel`을 누른다. 노트 목록으로 돌아가고 `Discard me` 링크가 없다.
- **CLI 진입.** `control-notes cli -- notes create --title "CLI note" --body "Created from terminal" --format json`을 실행한다. 종료 코드 `0`이고 stdout에 새 노트 ID와 제목이 있다.
- **증명.** `control-notes browser snapshot --aria --path artifacts/create-note/list.aria.txt`와 `control-notes browser screenshot --path artifacts/create-note/list.png`를 실행한다. 증거에 `Release checklist`와 `CLI note`가 보인다.

## Gotchas

- 텍스트박스에 포커스가 있을 때 `n`을 누르면 새 편집기가 열리지 않고 글자가 입력된다.
- 제목은 저장할 때 앞뒤 공백이 잘린다. 입력 필드 값이 아니라 렌더링된 제목을 단언한다.
- 저장 상태 표시만으로는 증명이 부족하다. 목록에서 노트를 다시 연다.
- 픽스처 정리에서 `Release checklist`와 `CLI note`를 지우되 증거 파일은 남긴다.
