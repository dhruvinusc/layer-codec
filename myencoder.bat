@echo off
rem Usage is defined by the brief; all arguments pass through unchanged.
setlocal
set "JAR=%~dp0build\libs\layer-codec.jar"
if not exist "%JAR%" (
  echo error: %JAR% not found. Build first: gradlew.bat build 1>&2
  exit /b 1
)
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
if defined LAYER_CODEC_JAVA set "JAVA=%LAYER_CODEC_JAVA%"
"%JAVA%" -Xmx4g -cp "%JAR%" codec.encoder.EncoderMain %*
