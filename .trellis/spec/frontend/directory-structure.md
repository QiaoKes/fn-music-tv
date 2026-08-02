# Directory Structure

> How frontend code is organized in this project.

---

## Overview

<!--
Document your project's frontend directory structure here.

Questions to answer:
- Where do components live?
- How are features/modules organized?
- Where are shared utilities?
- How are assets organized?
-->

Authenticated TV UI is organized by responsibility while keeping package-local Compose access.
Moving declarations between these files must preserve the Composable tree, focus graph, dimensions,
text, and callback ordering.

---

## Directory Layout

```text
app/src/main/java/com/fnmusic/tv/ui/
├── FnMusicApp.kt          # session shell and login
├── AuthenticatedApp.kt    # authenticated navigation and library rendering
├── LibraryState.kt        # route and retained-state lifecycle
├── PlayerPresentation.kt  # pure player presentation projections
├── PlayerScreen.kt        # player visuals, controls, lyrics, and queue
├── SettingsScreen.kt      # preferences and cache settings
├── Theme.kt
└── TouchButton.kt
```

---

## Module Organization

<!-- How should new features be organized? -->

- Keep route/retained state free of Composable rendering.
- Keep player state projection separate from Media3 control and from player rendering.
- Cross-module actions belong to the app coordinator; UI files invoke narrow actions.
- A file split is an equal refactor only when moved bodies remain unchanged apart from the minimum
  package visibility required for same-package consumers.

---

## Naming Conventions

<!-- File and folder naming rules -->

- Screen/rendering files use the surface name (`PlayerScreen`, `SettingsScreen`).
- Pure state ownership uses the domain plus `State`/`Presentation`.
- Application assembly contracts live outside the `ui` package.

---

## Examples

<!-- Link to well-organized modules as examples -->

- `LibraryState.kt` is the model for route-owned cleanup and retained paging state.
- `PlayerPresentation.kt` is the model for identity validation and retry/status projection.
