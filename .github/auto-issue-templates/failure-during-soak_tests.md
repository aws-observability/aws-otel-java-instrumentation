---
title: Performance Threshold breached DURING Soak Tests execution for the ({{ env.APP_PLATFORM }}, {{ env.INSTRUMENTATION_TYPE }}) Sample App
# assignees: open-telemetry/opentelemetry-<LANGUAGE>-approvers
labels: bug #, enhancement
---
# Description

During Soak Tests execution, a performance degradation was revealed for commit {{ sha }} of the `{{ ref }}` branch for the ({{ env.APP_PLATFORM }}, {{ env.INSTRUMENTATION_TYPE }}) Sample App. Check out the Action Logs from the `{{ workflow }}` [workflow run on GitHub]({{ env.GITHUB_SERVER_URL }}/{{ env.GITHUB_REPOSITORY }}/actions/runs/{{ env.GITHUB_RUN_ID }}) to view the threshold violation.

# Useful Links

Snapshots of the Soak Test run are available [on the gh-pages branch](https://github.com/{{ env.GITHUB_REPOSITORY }}/tree/gh-pages/soak-tests/snapshots/{{ sha }}). These are the snapshots for the violating commit:

![CPU Load Soak Test SnapShot Image](https://github.com/{{ env.GITHUB_REPOSITORY }}/blob/gh-pages/soak-tests/snapshots/commits/{{ sha }}/runs/{{ env.GITHUB_RUN_ID }}/{{ env.APP_PLATFORM }}/{{ env.INSTRUMENTATION_TYPE }}-cpu-load.png?raw=true)
![Total Memory Soak Test SnapShot Image](https://github.com/{{ env.GITHUB_REPOSITORY }}/blob/gh-pages/soak-tests/snapshots/commits/{{ sha }}/runs/{{ env.GITHUB_RUN_ID }}/{{ env.APP_PLATFORM }}/{{ env.INSTRUMENTATION_TYPE }}-total-memory.png?raw=true)
