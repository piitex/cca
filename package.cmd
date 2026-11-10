@echo off
rem Builds this project's app with the RenGL packager. The settings are in rengl-package.properties.
rem The packager is found in this order:
rem   1. the RENGL_PACKAGER environment variable (the rengl-packager folder of your RenGL checkout)
rem   2. a RenGL checkout next to this project: ..\RenGL\rengl-packager
rem Parameters pass through: package.cmd -Installer true   or   package.cmd -Platform linux-x64
set "PACKAGER=%RENGL_PACKAGER%"
if "%PACKAGER%"=="" set "PACKAGER=%~dp0..\RenGL\rengl-packager"
if not exist "%PACKAGER%\package-app.ps1" (
    echo Could not find the RenGL packager at "%PACKAGER%".
    echo Set RENGL_PACKAGER to the rengl-packager folder of your RenGL checkout, or clone RenGL next to this project.
    exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%PACKAGER%\package-app.ps1" %*
exit /b %errorlevel%
