@echo off
rem SimWeaver Junction - start the server in this console.
rem
rem   run.bat                                   the simple way: this folder is also the site (etc\, logs\)
rem   run.bat --root=C:\SimWeaver\site          a site folder of its own (settings, logs, key store)
rem   run.bat --root=<site> --init              make the site and stop
rem   run.bat --root=<site> --new-token=<name>  make an API token and stop
rem   run.bat --root=<site> --health            does the server answer? (exit 0 / 1)
rem
rem The site can also be given as the environment variable JUNCTION_SITE.
rem RTI_JAR: the HLA RTI's Java library (several: separate with ';'). Only HLA datasources need it;
rem set it empty for DIS only. An environment variable RTI_JAR overrides the default below
rem (a placeholder path: change it to where your RTI is installed).
rem JAVA_HOME (optional): the Java to use, else java from the PATH. JAVA_OPTS: options for Java.

setlocal
title SimWeaver Junction

set "APP_JAR=%~dp0lib\simweaverrift.jar"
if not defined RTI_JAR set "RTI_JAR=C:\Program Files\anrti\lib\hlaimpl.jar"

set "CP=%APP_JAR%"
if not "%RTI_JAR%"=="" set "CP=%APP_JAR%;%RTI_JAR%"

set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"

"%JAVA%" %JAVA_OPTS% -cp "%CP%" com.simweaver.rift.Server %*
