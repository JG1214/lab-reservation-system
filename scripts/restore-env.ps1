
# =====================================================================
# 环境恢复脚本 —— 高校实验室预约管理系统（V4.0）
# 用途：一键恢复「数据库 + 后端 + 前端」可运行/可演示/可复测环境
# 用法（在项目根执行）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore-env.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore-env.ps1 -Mode status
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore-env.ps1 -Mode stop
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore-env.ps1 -InitDb
# 说明：
#   - 本机直跑模式：后端 mvn spring-boot:run（8080），前端 npm run dev（5173）
#   - 数据库默认使用本机 MySQL 3306（application.yml 默认）；密码默认 123456，可用 $env:MYSQL_PASSWORD 覆盖
#   - Redis 为可选加分项：默认不要求在线（后端自动降级）；-WithRedis 时探活 6379
#   - 若 8080/5173 已被占用，脚本不会重复启动，只探活并提示（避免打断已有服务）
#   - 脚本记录的 PID 写入 scripts\.restore-env.pid，-Mode stop 可关闭本脚本启动的服务
# =====================================================================

param(
    [ValidateSet('all','backend','frontend','db','status','stop')]
    [string]$Mode = 'all',
    [switch]$InitDb,
    [switch]$WithRedis
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot   # scripts 的上一级 = 项目根

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn2($msg){ Write-Host "  [WARN] $msg" -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host "  [ERR] $msg" -ForegroundColor Red }

function Test-Port($port) {
    # Get-NetTCPConnection 在部分受限环境下返回空，改用 netstat 解析（两者本机实测差异已确认）
    $hit = & netstat -ano 2>$null | Select-String 'LISTENING' | Select-String (':' + $port + '\s')
    return ($null -ne $hit)
}
function Find-Command($name, $fallback) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($fallback -and (Test-Path $fallback)) { return $fallback }
    return $null
}
function Invoke-Probe($url, $timeoutSec = 5) {
    try {
        $r = Invoke-WebRequest -Uri $url -TimeoutSec $timeoutSec -UseBasicParsing
        return [int]$r.StatusCode
    } catch {
        # 4xx/5xx 会抛异常，但服务在线——从响应里取状态码，取不到才视为未响应
        $resp = $_.Exception.Response
        if ($resp) { return [int]$resp.StatusCode }
        return $null
    }
}
function Invoke-LoginProbe($timeoutSec = 5) {
    # 业务级探活：/api/user/login 是唯一免鉴权端点，可同时验证后端与数据库连通
    try {
        $body = '{"username":"admin","password":"admin123","role":1}'
        $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/user/login' -Method POST -ContentType 'application/json' -Body $body -TimeoutSec $timeoutSec -UseBasicParsing
        if ([int]$r.StatusCode -eq 200 -and $r.Content -match '"code"\s*:\s*200') { return 'OK' }
        return ('HTTP ' + [int]$r.StatusCode)
    } catch {
        $resp = $_.Exception.Response
        if ($resp) { return ('HTTP ' + [int]$resp.StatusCode) }
        return $null
    }
}
function Start-Background($cmdPath, $argsList, $logFile, $pidFile) {
    # 用 Start-Process 后台启动，写 PID 文件
    $p = Start-Process -FilePath $cmdPath -ArgumentList $argsList -RedirectStandardOutput $logFile -RedirectStandardError ($logFile + '.err') -WindowStyle Hidden -PassThru
    if ($p) {
        "$($p.Id)" | Out-File -FilePath $pidFile -Encoding utf8
        Write-Ok ("已启动 PID=" + $p.Id + "（日志：" + $logFile + "）")
    }
    return $p
}

Write-Step "环境恢复脚本 · Mode=$Mode"
Write-Step ("项目根: " + $ProjectRoot)

# ---------- 0. 前置检查 ----------
$java = Find-Command 'java' $null
$mvn  = Find-Command 'mvn' 'D:\work\code\apache-maven-3.9.6\bin\mvn.cmd'
$node = Find-Command 'node' 'D:\nodejs\node.exe'
$npm  = Find-Command 'npm'  'D:\nodejs\npm.cmd'
$mysql= Find-Command 'mysql' 'D:\work\mysql\bin\mysql.exe'

Write-Step "工具可用性"
if (-not $mvn)  { Write-Err "未找到 Maven（mvn）——请安装或配置 PATH" } else { Write-Ok ("Maven: " + $mvn) }
if (-not $node) { Write-Err "未找到 Node.js（node）" } else { Write-Ok ("Node: " + $node) }
if (-not $mysql){ Write-Err "未找到 MySQL 客户端（mysql）" } else { Write-Ok ("MySQL: " + $mysql) }

# ---------- 0.5 JDK / JAVA_HOME ----------
# mvn 依赖 JAVA_HOME 找 java；本机存在 JAVA_HOME 指向父目录（C:\Program Files\Zulu）的失效情况，
# 这里校验一次，失效则用 PATH 上的 java 反推并写回当前进程环境（只影响本脚本启动的子进程）
$javaExe = Find-Command 'java' $null
if (-not $javaExe) {
    Write-Err "未找到 java（PATH 与 JAVA_HOME 均不可用）——请安装 JDK 21 或配置 JAVA_HOME"
} else {
    $javaHome = Split-Path -Parent (Split-Path -Parent $javaExe)
    if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        Write-Warn2 ("JAVA_HOME 无效（当前: " + $env:JAVA_HOME + "），改用 " + $javaHome)
        $env:JAVA_HOME = $javaHome
    } else {
        Write-Ok ("JAVA_HOME: " + $env:JAVA_HOME)
    }
    # java -version 输出在 stderr，配合 $ErrorActionPreference='Stop' 会抛 NativeCommandError，这里单独降级
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $verText = (& $javaExe -version 2>&1 | Out-String)
    $ErrorActionPreference = $prevEap
    $verLine = ($verText -split "`r?`n" | Where-Object { $_ -match 'version' } | Select-Object -First 1)
    if ($verLine) { Write-Ok ("Java: " + $verLine.Trim()) } else { Write-Warn2 "无法读取 Java 版本" }
}

# ---------- 1. 端口探测 ----------
Write-Step "端口占用探测"
$portDb = 3306; $portRedis = 6379; $portApi = 8080; $portWeb = 5173
$dbUp = Test-Port $portDb
$redisUp = Test-Port $portRedis
$apiUp = Test-Port $portApi
$webUp = Test-Port $portWeb
Write-Host ("  MySQL 3306: " + $(if ($dbUp) {'已监听'} else {'空闲'}))
Write-Host ("  Redis 6379: " + $(if ($redisUp) {'已监听'} else {'未监听（后端自动降级）'}))
Write-Host ("  后端 8080: " + $(if ($apiUp) {'已监听'} else {'空闲'}))
Write-Host ("  前端 5173: " + $(if ($webUp) {'已监听'} else {'空闲'}))

if ($Mode -eq 'status') {
    Write-Step "状态汇总"
    $login = Invoke-LoginProbe 5
    Write-Host ("  后端探活 POST /api/user/login: " + $(if ($login) { $login } else {'未响应'}))
    $web = Invoke-Probe 'http://127.0.0.1:5173/' 3
    Write-Host ("  前端探活 http://127.0.0.1:5173/: " + $(if ($web) { 'HTTP '+$web } else {'未响应'}))
    Write-Ok "状态检查完成"
    return
}

if ($Mode -eq 'stop') {
    Write-Step "停止本脚本启动的服务"
    $pidFile = Join-Path $PSScriptRoot '.restore-env.pid'
    $stopped = @()
    if (Test-Path $pidFile) {
        $pids = Get-Content $pidFile | Where-Object { $_ -match '^\d+$' }
        foreach ($procId in $pids) {
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            if ($proc) {
                Write-Host ("  停止 PID " + $procId + "（" + $proc.ProcessName + "）")
                Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
                $stopped += $procId
            }
        }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    } else {
        Write-Warn2 "未找到 PID 文件（脚本可能未启动过服务，或服务已手动关闭）"
    }
    # mvn spring-boot:run / npm run dev 会再派生 java、node 子进程，父进程被杀后子进程可能仍占端口，
    # 因此再按端口定位一次归属进程（仅 8080 / 5173 两个本项目端口，不动其它服务）
    foreach ($port in @($portApi, $portWeb)) {
        if (Test-Port $port) {
            $owner = (& netstat -ano 2>$null | Select-String 'LISTENING' | Select-String (':' + $port + '\s') | ForEach-Object { ($_.Line -split '\s+')[-1] } | Select-Object -First 1)
            if ($owner -and $owner -match '^\d+$') {
                Write-Host ("  端口 " + $port + " 仍被 PID " + $owner + " 占用，一并停止")
                Stop-Process -Id ([int]$owner) -Force -ErrorAction SilentlyContinue
                $stopped += [int]$owner
            }
        }
    }
    if ($stopped.Count -gt 0) { Write-Ok ("已停止 " + $stopped.Count + " 个进程") }
    else { Write-Warn2 "没有需要停止的进程" }
    return
}

if ($Mode -eq 'all' -or $Mode -eq 'db') {
    Write-Step "数据库检查"
    $mysqlPwd = $env:MYSQL_PASSWORD
    if (-not $mysqlPwd) { $mysqlPwd = '123456' }
    $env:MYSQL_PWD = $mysqlPwd
    # 注意：本机 MySQL 8.0.28 客户端不认 -h127.0.0.1 这类连写短选项（会打印 Usage 并退出），
    # 统一改用长选项 --host/--port/--user，实测可用
    $mysqlArgs = @('--host=127.0.0.1', ('--port=' + $portDb), '--user=root', '--default-character-set=utf8mb4')
    $dbTest = & $mysql @mysqlArgs reservation --execute="SELECT COUNT(*) AS ok FROM sys_user;" 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Ok "MySQL 连接正常（库 reservation）"
        if ($InitDb) {
            Write-Step "初始化数据库（init_db.sql，可重复执行）"
            $initSql = Join-Path $ProjectRoot 'database\init_db.sql'
            # 中文路径下 mysql 的 source 与 < 重定向都可能失败，先复制到 ASCII 临时路径再整库导入
            $tmpSql = Join-Path $env:TEMP 'reservation-init_db.sql'
            Copy-Item -Path $initSql -Destination $tmpSql -Force
            $cmdLine = '"' + $mysql + '" ' + ($mysqlArgs -join ' ') + ' < "' + $tmpSql + '"'
            & cmd.exe /c $cmdLine
            $initExit = $LASTEXITCODE
            Remove-Item $tmpSql -Force -ErrorAction SilentlyContinue
            if ($initExit -eq 0) { Write-Ok "数据库初始化完成（7 表 + 种子数据）" }
            else { Write-Err "数据库初始化失败（退出码 $initExit）" }
        }
    } else {
        Write-Err "MySQL 连接失败：$($dbTest -join ' ')"
        if ($Mode -eq 'db') { return }
    }
}

# ---------- 3. 后端 ----------
if ($Mode -eq 'all' -or $Mode -eq 'backend') {
    if ($apiUp) {
        Write-Warn2 "端口 8080 已被占用，跳过启动后端（如非本脚本进程，请勿强杀）"
    } else {
        Write-Step "启动后端（mvn spring-boot:run）"
        $logDir = Join-Path $ProjectRoot 'logs'; New-Item -ItemType Directory -Force -Path $logDir | Out-Null
        $logFile = Join-Path $logDir 'backend.log'
        $pidFile = Join-Path $PSScriptRoot '.restore-env.pid'
        # 注入 MYSQL_PASSWORD 环境变量（application.yml 默认 123456，兼容本机）
        if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD = $env:MYSQL_PASSWORD } else { $env:MYSQL_PASSWORD = '123456' }
        $env:REDIS_PORT = '6379'
        # 在 server 目录下执行 mvn spring-boot:run
        $p = Start-Process -FilePath $mvn -ArgumentList @('-f', (Join-Path $ProjectRoot 'server\pom.xml'), 'spring-boot:run') -WorkingDirectory (Join-Path $ProjectRoot 'server') -RedirectStandardOutput $logFile -RedirectStandardError ($logFile + '.err') -WindowStyle Hidden -PassThru
        if ($p) {
            "$($p.Id)" | Out-File -FilePath $pidFile -Encoding utf8
            Write-Ok ("后端启动中 PID=" + $p.Id + "，日志 " + $logFile)
        }
        # 等待探活
        $ok = $false
        for ($i = 0; $i -lt 90; $i++) {
            Start-Sleep -Seconds 1
            $code = Invoke-LoginProbe 3
            if ($code -eq 'OK') { $ok = $true; break }
            if ($i -ge 60 -and (Test-Path ($logFile + '.err'))) {
                $tail = Get-Content ($logFile + '.err') -Tail 5 -ErrorAction SilentlyContinue
                if ($tail -match 'APPLICATION FAILED TO START|BindException|Communications link failure') { break }
            }
        }
        if ($ok) { Write-Ok "后端启动成功（HTTP 200）" } else { Write-Err "后端启动超时/失败，请查看日志" }
    }
}

# ---------- 4. 前端 ----------
if ($Mode -eq 'all' -or $Mode -eq 'frontend') {
    if ($webUp) {
        Write-Warn2 "端口 5173 已被占用，跳过启动前端"
    } else {
        # 不用 npm.cmd：Start-Process 带输出重定向时 .cmd 无法直接执行，主进程会立刻消失；
        # 直接用 node 跑 vite 入口，行为与 npm run dev 等价。
        Write-Step "启动前端（vite dev server --port 5173 --strictPort）"
        $logDir = Join-Path $ProjectRoot 'logs'; New-Item -ItemType Directory -Force -Path $logDir | Out-Null
        $logFile = Join-Path $logDir 'vite.log'
        $pidFile = Join-Path $PSScriptRoot '.restore-env.pid'
        $webDir = Join-Path $ProjectRoot 'web'
        $viteEntry = Join-Path $webDir 'node_modules\vite\bin\vite.js'
        if (-not (Test-Path $viteEntry)) { Write-Err ("未找到 vite 入口：" + $viteEntry + "，请先在 web 目录执行 npm install"); }
        $p = Start-Process -FilePath $node -ArgumentList @($viteEntry,'--port','5173','--strictPort') -WorkingDirectory $webDir -RedirectStandardOutput $logFile -RedirectStandardError ($logFile + '.err') -WindowStyle Hidden -PassThru
        if ($p) {
            "$($p.Id)" | Out-File -FilePath $pidFile -Encoding utf8 -Append
            Write-Ok ("前端启动中 PID=" + $p.Id + "，日志 " + $logFile)
        }
        $ok = $false
        for ($i = 0; $i -lt 60; $i++) {
            Start-Sleep -Seconds 1
            $code = Invoke-Probe 'http://127.0.0.1:5173/' 3
            if ($code -eq 200) { $ok = $true; break }
        }
        if ($ok) { Write-Ok "前端启动成功（HTTP 200）" } else { Write-Err "前端启动超时/失败，请查看日志" }
    }
}

# ---------- 5. Redis 可选探活 ----------
if ($WithRedis) {
    Write-Step "Redis 探活（可选加分项）"
    if ($redisUp) { Write-Ok "Redis 6379 已监听" } else { Write-Warn2 "Redis 6379 未监听（后端会自动降级为纯 JWT + 直接查库）" }
}

Write-Step "完成"
Write-Host "  后端: http://127.0.0.1:8080  (接口文档 /doc.html)"
Write-Host "  前端: http://127.0.0.1:5173"
Write-Host "  演示: 管理员 admin/admin123 · 教师 teacher/123 · 学生 zhangsan/123456"
Write-Host "  查看状态: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore-env.ps1 -Mode status"
Write-Host "  停止服务: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore-env.ps1 -Mode stop"
