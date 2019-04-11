@echo off
set "here=%~dp0"
set "fernflower=%here%fernflower.jar"

java -jar "%fernflower%" -rsy=1 "%~1" "%~2"