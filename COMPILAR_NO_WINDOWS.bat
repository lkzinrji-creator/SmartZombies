@echo off
echo Smart Zombies - Forge 1.20.1
echo.
echo Este projeto usa Gradle e baixara as dependencias do Forge automaticamente.
echo.
gradlew.bat build
echo.
echo Se der certo, o JAR estara em build\libs\
pause
