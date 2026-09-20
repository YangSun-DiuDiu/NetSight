$ErrorActionPreference = "Stop"
Set-Location "E:\gitee\NetSight1.0\netsight-ui"
$env:Path = "D:\node16;" + $env:Path
$env:NODE_OPTIONS = "--max-old-space-size=4096"
& npm run build:prod *> "E:\gitee\NetSight1.0\deploy\_build_ui.log"
"BUILD_EXIT=$LASTEXITCODE" | Out-File -Append -Encoding utf8 "E:\gitee\NetSight1.0\deploy\_build_ui.log"
