@echo off
setlocal EnableExtensions
REM ==========================================================
REM  Sinkronizacija lokalnog projekta s GitHubom
REM  Repo: https://github.com/mgrubisic/adb-auto-enable
REM  Stavi ovu datoteku u korijensku mapu projekta (gdje je gradlew.bat)
REM  i pokreni je dvoklikom.
REM ==========================================================

set "REPO_URL=https://github.com/mgrubisic/adb-auto-enable.git"
set "BRANCH=main"

cd /d "%~dp0"

where git >nul 2>&1
if errorlevel 1 (
    echo [GRESKA] Git nije instaliran. Preuzmi ga s https://git-scm.com/download/win
    goto :end
)

REM --- Prvo pokretanje: mapa jos nije Git repozitorij ---
if not exist ".git" (
    echo Prvo pokretanje: povezujem mapu s %REPO_URL% ...
    git init -b %BRANCH% || goto :fail
    git remote add origin %REPO_URL% || goto :fail
    git fetch origin %BRANCH% || goto :fail
    REM Postavi povijest na online verziju, a lokalne datoteke ostavi netaknute
    git reset --mixed origin/%BRANCH% || goto :fail
    git branch --set-upstream-to=origin/%BRANCH% %BRANCH% >nul
)

REM --- Provjera da je remote ispravan ---
git remote get-url origin >nul 2>&1 || git remote add origin %REPO_URL%

REM --- Lokalne promjene ---
git add -A
git diff --cached --quiet
if errorlevel 1 (
    echo.
    echo Lokalne promjene koje ce biti poslane na GitHub:
    echo ---------------------------------------------
    git status --short
    echo ---------------------------------------------
    set "ANSWER="
    set /p "ANSWER=Poslati ove promjene na GitHub? (D/N): "
    call :checkanswer || goto :abort
    set "MSG="
    set /p "MSG=Opis promjene (Enter = automatski): "
    call :commit || goto :fail
) else (
    echo Nema lokalnih promjena.
)

REM --- Preuzmi promjene s GitHuba i spoji ih s lokalnima ---
echo.
echo Preuzimam promjene s GitHuba...
git pull --rebase origin %BRANCH%
if errorlevel 1 (
    echo.
    echo [GRESKA] Sukob izmedju lokalnih i online promjena.
    echo Rijesi sukobe u navedenim datotekama, zatim pokreni:
    echo     git add -A
    echo     git rebase --continue
    echo ili odustani s:  git rebase --abort
    goto :end
)

REM --- Posalji na GitHub ---
echo.
echo Saljem na GitHub...
git push -u origin %BRANCH% || goto :fail

echo.
echo [OK] Lokalni projekt i GitHub su sinkronizirani.
goto :end

:checkanswer
if /i "%ANSWER%"=="D" exit /b 0
if /i "%ANSWER%"=="Y" exit /b 0
exit /b 1

:commit
if "%MSG%"=="" set "MSG=Sinkronizacija %date% %time:~0,5%"
git commit -m "%MSG%"
exit /b %errorlevel%

:abort
git reset >nul
echo Odustao si. Nista nije poslano.
goto :end

:fail
echo.
echo [GRESKA] Naredba nije uspjela. Pogledaj poruku iznad.

:end
echo.
pause
endlocal
