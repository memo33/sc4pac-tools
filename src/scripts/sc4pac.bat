@ECHO OFF
REM Invocation of sc4pac command-line interface on Windows.
REM In Windows cmd.exe, call:
REM
REM     sc4pac
REM
REM In Windows PowerShell, call:
REM
REM     .\sc4pac


SET SCRIPTDIR=%~dp0.
java -jar "%SCRIPTDIR%\sc4pac-cli.jar" %*
