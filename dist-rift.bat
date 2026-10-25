setlocal
set RIFT_VERSION=0.9.8
mkdir dist
mkdir dist\rift
xcopy simwrift\simwrift\rtdir\* dist\rift\ /E /I
del /q /s "dist\rift\log\*"
del /q /s "dist\rift\maps\cache\*"
copy out\artifacts\simweaverrift\simw*.jar dist\rift\lib\
powershell -Command "Compress-Archive -Path 'dist\rift\*' -DestinationPath 'dist\simweaverrift-%RIFT_VERSION%.zip' -Force"
pause
endlocal
 