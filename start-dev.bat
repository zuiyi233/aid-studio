@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
title AID Studio - 开发模式一键启动

REM ============================================================
REM  AID Studio (AI影视/AI漫剧) Windows 开发模式一键启动脚本
REM  功能：启动 MySQL+Redis 中间件 -> 构建并启动后端(8080)
REM        -> 启动管理端(5173) 与 用户创作端(3000)
REM  环境要求：JDK 17+、Docker Desktop（首次使用需已安装）
REM  所有依赖（Maven 本体/Java 依赖仓库/前端 node_modules）
REM  均位于项目内，不污染系统环境。
REM ============================================================

set "ROOT=%~dp0"
for %%I in ("%ROOT%.") do set "ROOT=%%~fI"
set "DEV_ENV=%ROOT%\dev-env"
set "MAVEN_HOME=%DEV_ENV%\tools\apache-maven-3.9.9"
set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
set "MAVEN_REPO=%DEV_ENV%\maven-repo"
set "LOG_DIR=%DEV_ENV%\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

echo ============================================================
echo  AID Studio 开发模式启动
echo  项目目录: %ROOT%
echo ============================================================

REM ---------- 1. 检查 Java ----------
java -version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到 Java，请安装 JDK 17+ 并加入 PATH
    pause
    exit /b 1
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%v"
echo [1/6] Java 版本: %JAVA_VER% ^(要求 17+^)

REM ---------- 2. 准备项目内 Maven（首次自动下载） ----------
if not exist "%MVN%" (
    echo [2/6] 未找到项目内 Maven，正在下载 apache-maven-3.9.9 ...
    if not exist "%DEV_ENV%\tools" mkdir "%DEV_ENV%\tools"
    cd /d "%DEV_ENV%\tools"
    curl -fsSL -o maven.zip https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip
    if errorlevel 1 (
        echo [错误] Maven 下载失败，请检查网络后重试
        pause
        exit /b 1
    )
    tar -xf maven.zip
    del maven.zip
)
echo [2/6] Maven: %MAVEN_HOME%

REM ---------- 3. 检查/启动 Docker ----------
docker info >nul 2>&1
if errorlevel 1 (
    echo [3/6] Docker 未运行，正在启动 Docker Desktop ...
    start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    set /a WAIT=0
    :wait_docker
    timeout /t 5 /nobreak >nul
    docker info >nul 2>&1
    if errorlevel 1 (
        set /a WAIT+=5
        if !WAIT! GEQ 180 (
            echo [错误] Docker Desktop 启动超时，请手动打开 Docker Desktop 后重试
            pause
            exit /b 1
        )
        goto wait_docker
    )
)
echo [3/6] Docker 已就绪

REM ---------- 4. 启动 MySQL + Redis 中间件 ----------
echo [4/6] 启动 MySQL+Redis 中间件 ^(首次会拉取镜像并导入初始化SQL，耗时较长^) ...
cd /d "%DEV_ENV%"
docker compose -f docker-compose.dev.yml up -d
if errorlevel 1 (
    echo [错误] 中间件启动失败
    pause
    exit /b 1
)

REM 等待 MySQL 健康
set /a WAIT=0
:wait_mysql
docker inspect -f "{{.State.Health.Status}}" aid-dev-mysql >nul 2>&1
if errorlevel 1 (
    timeout /t 5 /nobreak >nul
    set /a WAIT+=5
    if !WAIT! GEQ 600 (
        echo [错误] MySQL 初始化超时，请查看: docker logs aid-dev-mysql
        pause
        exit /b 1
    )
    goto wait_mysql
)
for /f "delims=" %%s in ('docker inspect -f "{{.State.Health.Status}}" aid-dev-mysql') do set "MYSQL_ST=%%s"
if not "%MYSQL_ST%"=="healthy" (
    timeout /t 10 /nobreak >nul
    set /a WAIT+=10
    if !WAIT! GEQ 600 (
        echo [错误] MySQL 初始化超时，请查看: docker logs aid-dev-mysql
        pause
        exit /b 1
    )
    goto wait_mysql
)
echo [4/6] 中间件就绪 ^(MySQL healthy + Redis 已启动^)

REM ---------- 5. 构建并启动后端 ----------
if not exist "%ROOT%\aid-admin\target\aid-admin.jar" (
    echo [5/6] 构建后端 jar ^(首次构建较慢，依赖已缓存到项目内) ...
    cd /d "%ROOT%"
    call "%MVN%" -Dmaven.repo.local="%MAVEN_REPO%" clean package -DskipTests -B
    if errorlevel 1 (
        echo [错误] 后端构建失败
        pause
        exit /b 1
    )
) else (
    echo [5/6] 后端 jar 已存在，跳过构建
)

REM 启动后端（独立窗口）
if exist "%LOG_DIR%\backend.log" del "%LOG_DIR%\backend.log"
set "AID_PROFILE=%DEV_ENV%\aid-upload"
if not exist "%AID_PROFILE%" mkdir "%AID_PROFILE%"
start "AID-Backend :8080" cmd /c "cd /d %ROOT% && set DB_HOST=localhost && set DB_PORT=3306 && set DB_NAME=aid_test && set DB_USERNAME=root && set DB_PASSWORD=123456 && set REDIS_HOST=localhost && set REDIS_PORT=6379 && set ROCKETMQ_ENABLED=false && set AID_PROFILE=%AID_PROFILE% && java -jar aid-admin\target\aid-admin.jar > %LOG_DIR%\backend.log 2>&1"

REM 等待后端就绪
set /a WAIT=0
:wait_backend
timeout /t 5 /nobreak >nul
set /a WAIT+=5
curl -s -o nul "http://localhost:8080/" 
if not errorlevel 1 (
    goto backend_ok
)
if exist "%LOG_DIR%\backend.log" (
    findstr /i "APPLICATION FAILED TO START Error starting ApplicationContext" "%LOG_DIR%\backend.log" >nul 2>&1
    if not errorlevel 1 (
        echo [错误] 后端启动失败，查看日志: %LOG_DIR%\backend.log
        pause
        exit /b 1
    )
)
if !WAIT! GEQ 240 (
    echo [错误] 后端启动超时，查看日志: %LOG_DIR%\backend.log
    pause
    exit /b 1
)
goto wait_backend
:backend_ok
echo [5/6] 后端已就绪: http://localhost:8080

REM ---------- 6. 启动前端（管理端 5173 + 用户创作端 3000） ----------
echo [6/6] 启动前端 ...

if not exist "%ROOT%\frontend\admin\node_modules" (
    echo   安装管理端依赖 ...
    cd /d "%ROOT%\frontend\admin"
    call npm install --no-audit --no-fund
)
REM 覆盖 .env.development 中的 VITE_BACKEND_HOST（默认指向官方线上后端），
REM 让管理端开发代理指向本地后端，实现全本地联调
start "AID-Admin :5173" cmd /c "cd /d %ROOT%\frontend\admin && set VITE_BACKEND_HOST=http://127.0.0.1:8080 && npm run dev"

if not exist "%ROOT%\frontend\web\node_modules" (
    echo   安装用户端依赖 ...
    cd /d "%ROOT%\frontend\web"
    call npm install --no-audit --no-fund
)
start "AID-Web :3000" cmd /c "cd /d %ROOT%\frontend\web && npm run dev"

echo.
echo ============================================================
echo  启动完成！访问地址：
echo    后端接口  : http://localhost:8080
echo    管理端    : http://localhost:5173   ^(账号 admin / admin123，首次登录请改密^)
echo    用户创作端: http://localhost:3000
echo  日志目录: %LOG_DIR%
echo  停止：关闭对应窗口，或运行 docker compose -f dev-env\docker-compose.dev.yml down
echo ============================================================
pause
