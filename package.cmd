@echo off
rem Builds this project's app with the RenGL packager. The settings are in rengl-package.properties.
rem Set RENGL_PACKAGER to the rengl-packager folder of your RenGL checkout, for example:
rem   set RENGL_PACKAGER=C:\dev\RenGL\rengl-packager
rem Parameters pass through: package.cmd -Installer true   or   package.cmd -Platform linux-x64
if "%RENGL_PACKAGER%"=="" (
    echo RENGL_PACKAGER is not set. Point it at the rengl-packager folder of your RenGL checkout.
    exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%RENGL_PACKAGER%\package-app.ps1" %*
exit /b %errorlevel%
