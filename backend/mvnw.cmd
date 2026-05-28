@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements. See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership. The ASF licenses this file
@REM to you under the Apache License, Version 2.0.
@REM ----------------------------------------------------------------------------
@echo off
setlocal

set WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar
set WRAPPER_PROPS=%~dp0.mvn\wrapper\maven-wrapper.properties

if exist "%WRAPPER_JAR%" goto run

for /f "tokens=2 delims==" %%A in ('findstr /b "wrapperUrl=" "%WRAPPER_PROPS%"') do set WRAPPER_URL=%%A
echo Downloading Maven Wrapper from %WRAPPER_URL%
curl.exe -fsSL "%WRAPPER_URL%" -o "%WRAPPER_JAR%"
if errorlevel 1 (
  echo Failed to download Maven Wrapper jar.
  exit /b 1
)

:run
set MAVEN_PROJECTBASEDIR=%CD%
java "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
endlocal
