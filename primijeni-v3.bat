@echo off
setlocal EnableExtensions
REM ==========================================================
REM  Primjenjuje v3 (bez ikakvog servisa) na lokalni projekt.
REM  Stavi ovu datoteku i v3-update.zip u korijensku mapu
REM  projekta (gdje su gradlew.bat i sync-github.bat) i pokreni.
REM ==========================================================
cd /d "%~dp0"

if not exist "gradlew.bat" (
    echo [GRESKA] Ovo nije korijenska mapa projekta - nema gradlew.bat.
    goto :end
)
if not exist "v3-update.zip" (
    echo [GRESKA] U ovoj mapi nema v3-update.zip.
    goto :end
)

set "J=app\src\main\java\com\tpn\adbautoenable"

echo Raspakiravam v3 datoteke...
tar -xf v3-update.zip || goto :fail

if exist "%J%\AdbConfigService.java" (
    del /q "%J%\AdbConfigService.java" || goto :fail
    echo Obrisan %J%\AdbConfigService.java
)

echo.
echo Provjera:
set "OK=1"
if exist "%J%\AdbConfigService.java"     (echo   [X] AdbConfigService.java jos postoji & set "OK=0") else (echo   [OK] AdbConfigService.java uklonjen)
if exist "%J%\AppController.java"        (echo   [OK] AppController.java) else (echo   [X] nema AppController.java & set "OK=0")
if exist "%J%\BootConfigJobService.java" (echo   [OK] BootConfigJobService.java) else (echo   [X] nema BootConfigJobService.java & set "OK=0")
if exist "%J%\BootConfigurator.java"     (echo   [OK] BootConfigurator.java) else (echo   [X] nema BootConfigurator.java & set "OK=0")
findstr /C:"NoBG" "app\build.gradle.kts" >nul && (echo   [OK] naziv "ADB Auto-Enable NoBG") || (echo   [X] build.gradle.kts nije v3 & set "OK=0")

if "%OK%"=="0" (
    echo.
    echo [GRESKA] Projekt nije ispravno azuriran. Nista nije poslano.
    goto :end
)

del /q v3-update.zip
echo.
echo Sve je ispravno. Pokrecem sync-github.bat ...
if exist "sync-github.bat" (
    call sync-github.bat
) else (
    echo Nema sync-github.bat - posalji promjene na GitHub rucno.
)
goto :end

:fail
echo [GRESKA] Naredba nije uspjela.

:end
echo.
pause
endlocal
