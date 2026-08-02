# Default Capture and Process List Design

## Goal

When the Android Studio plugin opens the browser inspector, select a running process from the current Android project and capture it once automatically. Keep the process picker useful by hiding obvious non-application processes and placing debuggable processes first.

## Project process detection

The plugin remains independent from the inspected application. `ProjectApplicationIdDetector` reads Gradle build files and returns the application IDs declared by Android application modules. It does not modify the project and does not depend on unstable Android Studio run-configuration implementation classes.

The browser receives the ordered project application IDs in the session response. It selects the first running main process whose `packageName` and `processName` both equal one of those IDs. If no main process exists, it selects the first matching secondary process. If no project process is running, no automatic capture is attempted and the user keeps manual control.

## Process discovery, filtering, and ordering

`ProcessCatalog` continues to read `ps -A`, then keeps processes whose base process name is a valid Android package name. This removes obvious native daemons, kernel workers, zygotes, and framework processes without hiding legitimate application secondary processes or system applications that can own Windows.

Debuggable PIDs are collected in one `adb jdwp` call. The `/api/processes` response includes `debuggable` for every remaining process and is sorted by:

1. current-project main process;
2. current-project secondary processes;
3. other debuggable processes;
4. remaining application-like processes;
5. stable process name and PID tie-breakers.

This ordering keeps the default target deterministic and avoids one `dumpsys package` call per process.

## Browser initialization

Initialization keeps the existing first-ready-device behavior. After the first device's process list loads, the browser selects the preferred current-project process and calls the existing target-process capture path exactly once. Device changes and process-list refreshes do not trigger automatic captures. Capture failures use the current toast and connection-status behavior and are not retried automatically.

## Compatibility and scope

- The inspected application requires no SDK or source-code changes.
- Global capture and manual target selection remain available.
- Secondary app processes remain visible because they may own `WindowManager.addView` Windows.
- No Compose-specific behavior is added.
- No new UI controls or collection backends are introduced.

## Verification

- Java tests cover package-like process filtering, JDWP PID parsing, debuggable ordering, project-process priority, and multiple application IDs.
- JavaScript tests cover deterministic preferred-process selection and the one-time auto-capture decision.
- Existing web tests, Gradle tests, plugin assembly, and Plugin Verifier must pass.
- A browser smoke test confirms opening the page selects the project process and produces a captured snapshot without clicking the capture button.
