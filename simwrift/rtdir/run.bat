@echo off
rem SimWeaver Junction - start the server in this console.
rem
rem RTI_JAR: the HLA RTI's Java library (several: separate with ';'). Only HLA datasources need it;
rem set it empty for DIS only. An environment variable RTI_JAR overrides the default below.

setlocal
title SimWeaver Junction
cd /d "%~dp0"

set "APP_JAR=lib\simweaverrift.jar"
if not defined RTI_JAR set "RTI_JAR=C:\Program Files\prti6\lib\prtifull.jar"

set "CP=%APP_JAR%"
if not "%RTI_JAR%"=="" set "CP=%APP_JAR%;%RTI_JAR%"

java %JAVA_OPTS% -cp "%CP%" com.simweaver.rift.Server %*
