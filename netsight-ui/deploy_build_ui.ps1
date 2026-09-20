$ErrorActionPreference = 'Continue'
Set-Location 'E:\gitee\NetSight1.0\netsight-ui'
$env:Path = 'D:\node16;' + $env:Path
$env:NODE_OPTIONS = '--max-old-space-size=4096'
$log = 'E:\gitee\NetSight1.0\netsight-ui\deploy_build_ui.log'
"BUILD_START $(Get-Date -Format 'HH:mm:ss')" | Out-File -FilePath $log -Encoding utf8
npm run build:prod *>> $log
"BUILD_END code=$LASTEXITCODE $(Get-Date -Format 'HH:mm:ss')" | Out-File -FilePath $log -Append -Encoding utf8
